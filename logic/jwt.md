# Module 2 — Authentication & Authorization: Implementation Notes

Status: **implemented.** This document explains *why* the code in
`security/`, `service/AuthService.java`, `controller/AuthController.java`,
`dto/`, `model/RefreshToken.java`, and `exception/` looks the way it does -
the tradeoffs, the places where this implementation deliberately deviates
from `plan/authentication.md`, and the reasoning behind each test case.
Line-by-line "what this code does" is covered by comments in the source
files themselves; this file is the "why," which doesn't fit well as inline
comments.

## 1. Files added/changed

| File | Purpose |
|---|---|
| `model/RefreshToken.java` | New entity - durable, DB-backed refresh tokens (hash only, never the raw value) |
| `repository/RefreshTokenRepository.java` | Lookups by hash, bulk delete by user, list active tokens |
| `dto/RegisterRequest.java`, `LoginRequest.java`, `RefreshRequest.java`, `AuthResponse.java`, `ErrorResponse.java` | Request/response shapes, validated with Bean Validation |
| `security/JwtService.java` | All JWT + opaque-refresh-token + hashing logic |
| `security/CustomUserDetailsService.java` | Bridges `User` to Spring Security, used only during login |
| `security/JwtAuthenticationFilter.java` | Populates `SecurityContext` from a Bearer token on protected requests |
| `security/RestAuthenticationEntryPoint.java`, `RestAccessDeniedHandler.java` | JSON 401/403 instead of Spring's default HTML error pages |
| `security/SecurityConfig.java` | Filter chain, session policy, password encoder, `AuthenticationManager` bean |
| `service/AuthService.java` | All business logic: register/login/refresh/logout |
| `controller/AuthController.java` | Thin `/auth/*` endpoints |
| `exception/InvalidRefreshTokenException.java`, `GlobalExceptionHandler.java` | Uniform error responses |
| `application.properties` | JWT secret/TTLs + the DB/Redis config the app needs just to boot |
| `security/JwtServiceTest.java`, `service/AuthServiceTest.java` | Unit tests - see section 4 |

The high-level flows (registration, login, refresh, logout, protected
request) are unchanged from `plan/authentication.md` sections 4.1-4.5 and
aren't repeated here - see that document for the sequence diagrams. This
file only covers what's new, corrected, or worth explaining beyond that plan.

## 2. Decisions worth flagging

### 2.1 `/auth/logout` is authenticated; the filter does NOT blanket-skip `/auth/**`

The plan's file-by-file table said `JwtAuthenticationFilter` "skips
`/auth/**`," while its own endpoint table (section 5) marks `/auth/logout`
as requiring a valid access token. Taken literally, both can't be true at
once: if the filter skipped every `/auth/**` path, it would never populate
the `SecurityContext` for a logout request, so a logout call would always
get rejected as unauthenticated - even with a perfectly valid Bearer token
- because nothing ever ran to recognize that token.

**Resolution implemented:** `JwtAuthenticationFilter.shouldNotFilter()`
only skips the three genuinely public, unauthenticated endpoints -
`/auth/register`, `/auth/login`, `/auth/refresh`. `/auth/logout` runs
through the filter like any other protected route, and `SecurityConfig`
does **not** include it in `permitAll()`. This matches the plan's own
endpoint table and flow diagram (4.5: "requires a valid access token") -
the "skips /auth/**" line in the file-by-file table was the part that
needed correcting.

### 2.2 Registration race condition (Issue #1) - two layers, not one

`AuthService.register()` calls `existsByUsername`/`existsByEmail` first,
but that pre-check is *not* the actual safety guarantee - it's just a fast
path for the common case (typo'd duplicate). Two requests can both pass
that check before either `INSERT` commits. The real guard is the `UNIQUE`
constraint on `users.username`/`email`, enforced by the database itself:
if the pre-check race is lost, `userRepository.save()` throws
`DataIntegrityViolationException`, which `AuthService` deliberately does
**not** catch - it propagates to `GlobalExceptionHandler`, which turns it
into a clean `409 Conflict`. `AuthServiceTest` has one test for each layer
(see section 4) so a future refactor can't silently remove the DB-level
guard while still passing "the tests."

### 2.3 Access tokens are real JWTs; refresh tokens are not

`JwtService` handles two structurally different kinds of "token," and this
is the single most important distinction to keep in mind when reading that
class:

- **Access token** - a signed JWT (`io.jsonwebtoken`/JJWT), carrying
  `sub` (username) and a custom `role` claim, short-lived (30 min
  default). The filter verifies its signature and trusts the embedded
  claims directly - **no DB call** on the request path. This is what makes
  authorizing a request cheap regardless of load (Issue #3 in the plan).
- **Refresh token** - 64 random bytes from `SecureRandom`, Base64url
  encoded. It carries no information of its own; the only thing that gives
  it meaning is the `refresh_tokens` row found by hashing it and looking
  up `token_hash`. It is never parsed, never signed, and JJWT never sees
  it.

Mixing these up (e.g. trying to `Jwts.parser()` a refresh token, or
embedding claims in it) would be a bug - `JwtServiceTest` has separate test
groups for each to keep that boundary visible.

### 2.4 Why the raw refresh token is never stored

`RefreshToken.tokenHash` stores `SHA-256(rawToken)`, never the raw value -
exactly parallel to how `User.password` stores a BCrypt hash, never the
plaintext password. `AuthService.issueTokenPair()` returns the raw value to
the client exactly once (at issuance, in the HTTP response body) and never
writes it to a log line, a DB column, or anywhere else. If the
`refresh_tokens` table were ever leaked (backup exposure, SQL injection
elsewhere, etc.), an attacker would have hashes they cannot turn back into
usable tokens.

### 2.5 Refresh token rotation as a theft signal

Every successful `/auth/refresh` call revokes the token that was just
spent and issues a brand-new one (`AuthService.refresh()`). This means a
refresh token is single-use. If the *same* already-revoked token is ever
presented again, `AuthService` throws `InvalidRefreshTokenException` and
logs a `WARN` - this is the classic sign that a refresh token was stolen
and both the attacker and the legitimate owner have now tried to use
descendants of the same original token. Per the plan, this is logged but
not yet auto-acted-on (no automatic "revoke the whole family" response) -
that's flagged as a future improvement once there's a real alerting path
for it (e.g. once Module 4's Redis is online, or a security dashboard
exists).

### 2.6 Why `CustomUserDetailsService` is barely used

It exists solely so `AuthenticationManager`/`DaoAuthenticationProvider` can
look up a user's password hash during the login call itself. It is
**never** consulted while handling an already-authenticated request -
`JwtAuthenticationFilter` builds the `Authentication` object directly from
JWT claims instead. If a future change makes `CustomUserDetailsService` get
called on every request, that's a regression of Issue #3's whole point
(removing the per-request DB lookup), not a small implementation detail.

### 2.7 Uniform JSON errors across three different mechanisms

Three different pieces of the stack can produce an error response, and all
three had to be pointed at the same `ErrorResponse` shape on purpose,
otherwise API clients would need three different error-parsing code paths:

1. `GlobalExceptionHandler` (`@RestControllerAdvice`) - exceptions thrown
   *inside* a controller method (validation, bad credentials, DB
   conflicts, invalid refresh tokens).
2. `RestAuthenticationEntryPoint` - triggered by Spring Security itself,
   *before* a controller runs, when a protected route is hit with no/an
   invalid JWT.
3. `RestAccessDeniedHandler` - triggered by Spring Security when a JWT is
   valid but the role doesn't satisfy `@PreAuthorize`.

### 2.8 A real, working `application.properties` was a prerequisite, not optional

`claude.md`'s "Known Gaps" section already flagged that
`spring-boot-starter-data-jpa` is on the classpath with zero datasource
configuration - meaning the application would fail to start at all (not
just fail Module 2's features) with only `spring.application.name` set.
Getting Module 2 to actually run required adding MySQL datasource
properties, Redis host/port (Module 4's dependency, but the starter is
already on the classpath and needs *some* value), and the three
`jwt.*` properties, all overridable via environment variables with
local-dev defaults inline. None of these are hardcoded secrets for a real
deployment - see the comments in `application.properties` itself.

### 2.9 A framework-version surprise: Spring Boot 4.1 uses Jackson 3, not Jackson 2

While verifying this module actually compiles, `RestAuthenticationEntryPoint`
and `RestAccessDeniedHandler` initially failed with
`package com.fasterxml.jackson.databind does not exist`. Investigating
`mvnw dependency:tree` showed why: **Spring Boot 4.1's web stack
(`spring-boot-starter-webmvc`) pulls in Jackson 3.x, whose classes live
under the `tools.jackson.*` package**, not the classic Jackson 2
`com.fasterxml.jackson.*`. The only reason `com.fasterxml.jackson:jackson-databind`
(2.21.4) appears anywhere in the dependency tree is as a *runtime-scope*
transitive dependency of `jjwt-jackson` (JJWT's own JSON handling for
building/parsing JWTs) - it was never on the *compile* classpath, and it's
not the ObjectMapper bean Spring Boot auto-configures for the rest of the
app.

**Fix:** both handler classes now import `tools.jackson.databind.ObjectMapper`.
`jackson-annotations` (used by `ErrorResponse`'s `@JsonFormat`) is
unaffected - Jackson 3's own POM comments confirm annotations deliberately
stayed on the Jackson 2.x `com.fasterxml.jackson.core:jackson-annotations`
group ID/package, only `jackson-databind`/`jackson-core` moved.

**Why this is worth recording:** any future class in this codebase that
needs Jackson's `ObjectMapper`, or any Jackson `*Module`
(e.g. `JavaTimeModule`), must import from `tools.jackson.*`, not
`com.fasterxml.jackson.*` - the compiler error is confusing on first read
because a same-named, same-shaped Jackson 2 class genuinely exists on the
classpath (transitively, via JJWT), so autocomplete/muscle memory will
pick the wrong one.

### 2.10 Pre-existing Module 1 bug found while verifying this module builds

Six entity files (`model/booking.java`, `bookingseat.java`, `movie.java`,
`seat.java`, `show.java`, `users.java`) used lowercase filenames that
didn't match their `public class` names (`Booking`, `BookingSeat`, `Movie`,
`Seat`, `Show`, `User`). `javac` requires an exact match and fails the
whole build over it - this had nothing to do with Module 2, but it made it
impossible to compile *anything*, including this module, to verify it
works. `seat.java` additionally was missing its `package` declaration and
all imports entirely (so `@Entity`, `Show`, `List`, etc. didn't resolve).

**Fix applied:** renamed all six files to match their class names exactly
(`Booking.java`, `BookingSeat.java`, `Movie.java`, `Seat.java`,
`Show.java`, `User.java`) and added the missing package/import lines to
`Seat.java`. No class names, fields, or logic were changed - this is a
filename/import fix only. Flagged here since it's outside Module 2's
nominal scope but was necessary to get a working build.

## 3. What's intentionally NOT done yet

- **`@PreAuthorize` on admin routes** - nothing to guard yet; Module 3
  (Movie/Show CRUD) is the first consumer, per plan section 7.
- **Springdoc/OpenAPI annotations** on `AuthController` - the
  `springdoc-openapi-starter-webmvc-ui` dependency isn't in `pom.xml` yet
  (`claude.md`'s "Known Gaps"). Adding `@Operation`/`@ApiResponse`
  annotations now would reference a library not on the classpath and break
  the build. `AuthController` has a `TODO` comment marking exactly where
  these belong once the dependency lands.
- **JWT denylist / revocation-before-expiry** - deliberately deferred to
  Module 4 (Redis), per Issue #2's mitigation plan. Access tokens rely on
  their short TTL in the meantime.
- **CORS configuration** - not needed until Module 8 (frontend); the right
  place for it (`SecurityConfig`) is already established.

## 4. Test case rationale

Unit tests were added for the logic that's genuinely easy to get subtly
wrong - not for framework code (Spring/Hibernate/JJWT's own correctness
isn't what's under test). Every test method has an inline "why this
matters" comment; this section is the narrative index.

### `JwtServiceTest` (6 tests)

| Test | What would break in production if this failed |
|---|---|
| `generateAccessToken_roundTrip_extractsOriginalUsernameAndRole` | Every authenticated request - the filter can't authorize anyone if claims don't round-trip |
| `isAccessTokenValid_returnsFalse_forExpiredToken` | The entire "30-minute exposure window" mitigation for Issue #2 - worthless if expired tokens are still accepted |
| `isAccessTokenValid_returnsFalse_forTamperedSignature` | The core security guarantee of using signed JWTs at all - proves a client can't hand-edit `role` from USER to ADMIN |
| `isAccessTokenValid_returnsFalse_forMalformedToken` | The filter's "never throws" contract - a malformed token must not crash request handling |
| `generateRefreshToken_producesHighEntropyUniqueValues` | Refresh tokens have no signature to fall back on - their only defense against guessing is randomness/length |
| `hashToken_isDeterministicAndOneWay` | `AuthService.refresh()`/`logout()` look up a token purely by re-hashing it - non-deterministic hashing would break every lookup |

### `AuthServiceTest` (10 tests)

| Test | What would break in production if this failed |
|---|---|
| `register_forcesRoleUser_regardlessOfInput` | A client could otherwise attempt to self-grant `ROLE_ADMIN` |
| `register_preCheckCatchesObviousDuplicate_withoutHittingTheDatabaseSave` | The fast-path half of Issue #1 - without it, every duplicate registration attempt would hit the DB and surface a raw constraint violation |
| `register_letsDbConstraintViolationPropagate_forConcurrentDuplicateRace` | The *authoritative* half of Issue #1 - if a future refactor wrapped this in a try/catch that swallowed the exception, two users could silently collide on one username |
| `login_withBadCredentials_neverReachesTokenIssuance` | A bug here could issue valid tokens alongside a "failed" login |
| `login_onSuccess_issuesAccessAndRefreshTokenAndPersistsOnlyTheHash` | Would catch a regression that accidentally persists the raw refresh token instead of its hash (see 2.4) |
| `refresh_withRevokedToken_isRejectedAndNoNewTokenIssued` | The core theft-detection mechanism in 2.5 - a revoked token must never mint a new access token |
| `refresh_withExpiredToken_isRejected` | Refresh tokens must actually expire, not just look like they do |
| `refresh_withValidToken_rotatesOldTokenAndIssuesNewPair` | Rotation must actually flip the old row's `revoked` flag - otherwise a leaked-but-unused old token stays valid forever instead of being single-use |
| `logout_withUnknownToken_isSilentlyANoOp` | Logout must not let a caller probe whether a given token string was ever real |
| `logout_withKnownToken_marksItRevoked` | The whole point of the endpoint - a no-op logout would silently fail to revoke anything |

### Why these are Mockito unit tests, not `@DataJpaTest`/`@SpringBootTest` integration tests

`claude.md` says integration tests against a real DB/Redis are expected
for this project generally (the `-jpa-test`/`-redis-test` starters are
there for a reason). For `AuthService` specifically, everything being
verified above is a **decision `AuthService` makes** (forced role, which
exception to throw, whether to rotate) - none of it depends on Hibernate's
SQL generation or a real MySQL instance behaving correctly. Mocking
`UserRepository`/`RefreshTokenRepository` keeps these tests fast and
focused on that decision logic. A `@DataJpaTest` for `RefreshTokenRepository`
itself (verifying `findByTokenHash`/`deleteAllByUser` actually generate
correct SQL against a real schema) and a `@SpringBootTest` +
`spring-boot-starter-webmvc-test` round-trip through the real filter chain
would be reasonable additions once Module 4's Redis/docker-compose setup
makes spinning up integration infra routine - not added here to avoid
scope creep beyond Module 2's unit-level logic.

## 5. Manual verification performed

Since this sandbox has no MySQL/Redis instance running, full end-to-end
`@SpringBootTest` verification (`MovieticketApplicationTests.contextLoads`)
could not be exercised here - it fails on `Unable to determine Dialect
without JDBC metadata`, i.e. it needs a real MySQL connection, not a code
defect in this module. What *was* verified locally:

- `mvn compile` - clean compile of every file in this module plus the
  Module 1 fixes (section 2.10).
- `mvn test -Dtest=JwtServiceTest,AuthServiceTest` - **16/16 passing.**
- Manual read-through of `SecurityConfig`'s filter chain wiring
  against `plan/authentication.md` sections 3 and 5 to confirm the
  permitAll/authenticated split matches section 2.1's resolution.

Before this is considered fully done, someone with a local MySQL +
`JWT_SECRET`/DB env vars set up should still exercise the real HTTP flow
end-to-end (register -> login -> hit a protected route -> refresh ->
logout -> confirm the old refresh token now fails) - the unit tests cover
the decision logic, not the wiring of the whole running application.
