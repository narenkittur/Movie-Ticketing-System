# Module 2 — Authentication & Authorization

Status: **implemented.** All files in section 3 below exist in code. See
`logic/jwt.md` for the implementation rationale, including two corrections
made to this plan during implementation (marked inline below where
relevant) and a note on a Spring Boot 4.1/Jackson 3 packaging surprise
that affected the JSON error handlers.

## 1. Goal & Requirements

**Goal:** Every request into the system carries a verifiable identity and
role, with no server-side session state.

**Functional requirements**
- Self-service registration → always creates a `ROLE_USER` account. There is
  **no runtime way to become ROLE_ADMIN** — admin accounts are seeded/inserted
  directly into the DB. A client can never grant itself admin by putting
  `"role": "ROLE_ADMIN"` in the register payload; the server ignores that
  field entirely.
- Login exchanges credentials for a short-lived **access token** (JWT) and a
  longer-lived **refresh token** (opaque, DB-backed).
- A refresh endpoint exchanges a valid, unrevoked refresh token for a new
  access token, rotating the refresh token in the process.
- A logout endpoint revokes a refresh token.
- A servlet filter validates the access token on every protected request and
  populates Spring Security's context so `@PreAuthorize("hasRole('ADMIN')")`
  etc. works downstream.
- Passwords are hashed with BCrypt; never stored or logged in plaintext.

**Non-functional requirements**
- Fully stateless — no `HttpSession`, no server-side session store. Session
  creation policy is `STATELESS` in `SecurityConfig`.
- Access-token exposure window stays short (target: 30 minutes) since it
  cannot be revoked before expiry (see Issue #2).
- Every endpoint in this module appears in OpenAPI/Swagger, per the project's
  documentation constraint (`movieticket/claude.md`).
- Every service method logs at INFO/WARN via SLF4J (login success/failure,
  token refresh, revocation) — no PII (raw passwords/tokens) in log lines.

## 2. New Data: `RefreshToken`

Module 1 already has `User` (`username` unique, `email`, `password` hash,
`role` string, `1→N Booking`). Module 2 adds one new table.

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT` (identity) | PK |
| `token_hash` | `VARCHAR`, unique, not null | SHA-256 of the opaque refresh token. The raw token is only ever returned to the client once, at issuance — never stored. Same principle as password hashing. |
| `user_id` | `BIGINT`, FK → `users.id`, not null | Owner |
| `expiry_date` | `TIMESTAMP`, not null | e.g. `created_at + 7 days` |
| `revoked` | `BOOLEAN`, not null, default `false` | Set `true` on logout or on rotation (old token revoked when a new one is issued) |
| `created_at` | `TIMESTAMP`, not null | For audit / cleanup jobs |

`RefreshTokenRepository extends JpaRepository<RefreshToken, Long>`:
- `Optional<RefreshToken> findByTokenHash(String tokenHash)`
- `void deleteAllByUser(User user)` (used on logout-all-devices, or account deletion)
- `List<RefreshToken> findAllByUserAndRevokedFalse(User user)` (optional — lets a future "active sessions" screen list live refresh tokens)

## 3. File-by-file breakdown

| File | Responsibility |
|---|---|
| `security/SecurityConfig.java` | `SecurityFilterChain` bean: `STATELESS` session policy, CSRF disabled (no cookies involved), `permitAll` on `/auth/register`, `/auth/login`, `/auth/refresh` + Swagger paths (**not** `/auth/logout` — see the filter row below and `logic/jwt.md` section 2.1), everything else `authenticated()`. Registers `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter`. Declares `PasswordEncoder` (`BCryptPasswordEncoder`) and `AuthenticationManager` beans. Wires a custom `AuthenticationEntryPoint` (JSON 401) and `AccessDeniedHandler` (JSON 403) so failures don't fall back to Spring's default HTML error pages. |
| `security/JwtService.java` | `generateAccessToken(User)`, `generateRefreshToken()` (SecureRandom opaque string, not a JWT), `hashToken(String)`, `extractUsername(String jwt)`, `extractRole(String jwt)`, `isAccessTokenValid(String jwt)`. Signing key and TTLs read from `application.properties`, not hardcoded. |
| `security/JwtAuthenticationFilter.java` | `OncePerRequestFilter`. Reads `Authorization: Bearer <token>`, validates signature + expiry via `JwtService`, builds a Spring `Authentication` **directly from the JWT's claims** (username + role) — no DB call. Sets `SecurityContextHolder`. Passes through untouched (no exception thrown) if the header is absent, so `permitAll` routes still work; invalid/expired tokens on protected routes fall through to the `AuthenticationEntryPoint`. **Corrected during implementation:** skips only `/auth/register`, `/auth/login`, `/auth/refresh` — *not* `/auth/logout`, since section 5 requires logout to carry a valid access token; blanket-skipping `/auth/**` would have made that impossible to authenticate. See `logic/jwt.md` section 2.1. |
| `security/CustomUserDetailsService.java` | `UserDetailsService` over `UserRepository`. Used **only** by `AuthenticationManager` during the login call itself — not on every request. |
| `model/RefreshToken.java`, `repository/RefreshTokenRepository.java` | As described in section 2. |
| `dto/RegisterRequest.java` | `username`, `email`, `password` (validated: `@NotBlank`, `@Email`, `@Size(min=8)`). No `role` field — even if a client sends one, there's nothing to bind it to. |
| `dto/LoginRequest.java` | `username`, `password`. |
| `dto/RefreshRequest.java` | `refreshToken`. |
| `dto/AuthResponse.java` | `accessToken`, `refreshToken`, `tokenType` (`"Bearer"`), `expiresIn` (seconds). |
| `service/AuthService.java` | `register()`, `login()`, `refresh()`, `logout()`. Owns password hashing, forced `ROLE_USER` assignment, refresh-token rotation, and the duplicate-registration race handling (Issue #1). |
| `controller/AuthController.java` | `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`. Thin — delegates to `AuthService`. |
| `exception/GlobalExceptionHandler.java` (extend existing pattern if present, else create `@RestControllerAdvice`) | Maps `DataIntegrityViolationException` → `409 Conflict`, bad credentials / invalid or expired token → `401 Unauthorized`, validation failures → `400 Bad Request`. |
| `application.properties` | `jwt.secret`, `jwt.access-token-expiration-ms` (e.g. `1800000` = 30 min), `jwt.refresh-token-expiration-ms` (e.g. `604800000` = 7 days). **Must be overridden via environment variables in any real deployment** — never commit a real secret. |

## 4. Step-by-step flows

### 4.1 Registration

```
Client                     AuthController        AuthService              DB (users)
  |  POST /auth/register        |                     |                        |
  |----------------------------->|                     |                        |
  |                              |--register(dto)----->|                        |
  |                              |                      |--BCrypt.hash(pw)      |
  |                              |                      |--role = ROLE_USER     |
  |                              |                      |    (server-forced,    |
  |                              |                      |     dto has no role   |
  |                              |                      |     field to trust)   |
  |                              |                      |--save(User)---------->|
  |                              |                      |                UNIQUE(username) /
  |                              |                      |                UNIQUE(email) enforced
  |                              |                      |<--- 201 or constraint violation --|
  |                              |<--- 201 Created ----|                        |
  |<---- 201 Created (no token; client must log in) ---|                        |
```

### 4.2 Login

```
Client              AuthController      AuthService        AuthenticationManager      JwtService        DB
  |  POST /auth/login    |                    |                       |                     |            |
  |---------------------->|                    |                       |                     |            |
  |                       |--login(dto)------->|                       |                     |            |
  |                       |                    |--authenticate(user,pw)>|                     |            |
  |                       |                    |                       |--loadUserByUsername->|-----------> users
  |                       |                    |                       |<--UserDetails--------|<-----------|
  |                       |                    |                       |--BCrypt.matches()    |            |
  |                       |                    |<--Authentication OK---|                     |            |
  |                       |                    |--generateAccessToken(user)------------------>|            |
  |                       |                    |<--JWT (sub, role, exp)-----------------------|            |
  |                       |                    |--generateRefreshToken()---------------------->|            |
  |                       |                    |--hash + save RefreshToken row---------------------------->|
  |                       |                    |<--AuthResponse{accessToken, refreshToken}---|            |
  |                       |<--AuthResponse-----|                       |                     |            |
  |<---- 200 OK ----------|                    |                       |                     |            |
```

### 4.3 Authenticated (protected) request

```
Client                JwtAuthenticationFilter          JwtService        SecurityContextHolder   Controller
  |  GET /movies/1 (Authorization: Bearer <jwt>)  |                          |                         |
  |----------------------------------------------->|                          |                         |
  |                                                |--parse + verify sig/exp->|                         |
  |                                                |<-- claims (sub, role) ---|                         |
  |                                                |--build Authentication(username, authorities=[role])|
  |                                                |--------------------------->set context------------->|
  |                                                |                          |          |--@PreAuthorize check
  |                                                |                          |          |   passes/denies
  |                                                |------------------------------------------------------>|
  |<----------------------------------------- 200 OK / 401 / 403 -------------------------------------------|
```
- Missing/invalid/expired token on a protected route → filter leaves the
  `SecurityContext` empty → Spring Security's `AuthenticationEntryPoint`
  returns `401` before the controller runs.
- Valid token, insufficient role (e.g. `USER` hitting an admin-only route)
  → `AccessDeniedHandler` returns `403`.

### 4.4 Refresh

```
Client                AuthController      AuthService              DB (refresh_tokens)
  |  POST /auth/refresh {refreshToken} |                    |                        |
  |------------------------------------>|                    |                        |
  |                                     |--refresh(token)--->|                        |
  |                                     |                     |--hash(token)          |
  |                                     |                     |--findByTokenHash----->|
  |                                     |                     |<--row (check expiry, revoked)--|
  |                                     |                     |--revoke old row------->|
  |                                     |                     |--issue new access token (JwtService)
  |                                     |                     |--issue + save new refresh token row-->|
  |                                     |<--new AuthResponse--|                        |
  |<---- 200 OK -------------------------|                    |                        |
```
Rotation (revoke-old / issue-new on every refresh) means a stolen refresh
token that gets used by an attacker *and* later by the real user produces two
different tokens claiming to descend from the same revoked row — a strong
signal for detecting theft, worth logging as a WARN even if not acted on
immediately in v1.

### 4.5 Logout
`POST /auth/logout {refreshToken}` (requires a valid access token) → hash the
provided refresh token → mark its row `revoked = true`. The **access token
remains valid** until it naturally expires — this is a deliberate, documented
tradeoff of statelessness, addressed in Issue #2 below.

## 5. Endpoint table

| Method | Path | Auth required | Role | Request | Response |
|---|---|---|---|---|---|
| POST | `/auth/register` | No | — | `RegisterRequest` | `201 Created` |
| POST | `/auth/login` | No | — | `LoginRequest` | `200 OK` → `AuthResponse` |
| POST | `/auth/refresh` | No (refresh token is the credential) | — | `RefreshRequest` | `200 OK` → `AuthResponse` |
| POST | `/auth/logout` | Yes | any | `RefreshRequest` | `204 No Content` |

## 6. JWT claims

| Claim | Meaning |
|---|---|
| `sub` | username |
| `role` | e.g. `ROLE_USER` / `ROLE_ADMIN` — embedded so the filter never needs a DB round-trip to authorize a request |
| `iat` | issued-at |
| `exp` | expiry — `iat + jwt.access-token-expiration-ms` |

## 7. Integration with the rest of the system

| Module | Dependency on Module 2 |
|---|---|
| 3 — Movie/Show CRUD | Admin endpoints annotated `@PreAuthorize("hasRole('ADMIN')")`, relying on the `role` claim set here. |
| 4 — Redis seat locking | The seat-lock value written to `seat:{showId}:{seatId}` is the **authenticated principal's user id**, taken from `SecurityContextHolder`, never a client-supplied id in the request body. |
| 5 — Payment/Booking | `Booking.user` is resolved from the authenticated principal, not the request payload — prevents booking on someone else's behalf. |
| 6 — WebSockets | STOMP `CONNECT` frames aren't covered by the servlet filter chain; needs a `ChannelInterceptor` that reuses `JwtService.isAccessTokenValid`/`extractUsername` to authenticate the handshake. Flagged now so it's not a surprise in Module 6. |
| 7 — QR / validate | `/validate/{bookingId}` (entrance scan) is likely role-gated the same way as admin routes. |
| 8 — Frontend | Needs CORS configured in `SecurityConfig` for the frontend's origin — not needed yet, but the config bean is the right place for it later. |
| 9 — Polish/README | README documents the auth flow; springdoc's Swagger config gets a bearer-JWT security scheme so "Authorize" works in the UI. |

## 8. Production-grade issues & mitigations

**1. Race condition on registration (TOCTOU).**
Two concurrent `POST /auth/register` calls with the same username can both
pass an `existsByUsername` pre-check before either transaction commits,
either creating a duplicate account or surfacing a raw, unhandled
`DataIntegrityViolationException` as a `500`.
*Mitigation:* Don't trust the pre-check as authoritative — it's just a
cheap early exit for the common case. The real guard is the DB's `UNIQUE`
constraint on `username`/`email`. `GlobalExceptionHandler` catches
`DataIntegrityViolationException` and returns a clean `409 Conflict`.

**2. Stateless JWTs can't be revoked before expiry.**
If an access token is stolen, or a user's role changes, there is no way to
kill that token immediately — short of checking the DB on every single
request, which defeats the point of statelessness and doesn't scale (adds
one query per API call under load).
*Mitigation:* Keep access tokens short-lived (30 min) to bound the exposure
window. Put actual revocation power on the refresh token instead, which is
already DB-backed and only checked at the low-frequency `/auth/refresh`
call, not on every request. Document (not build yet) a future upgrade: once
Module 4 brings Redis online, add an opt-in denylist keyed by the JWT's
`jti` with a TTL equal to the token's remaining lifetime — O(1), self-
expiring, and reuses infrastructure the project already needs for Module 4
instead of adding new infra just for this.

**3. Per-request DB lookup in the filter (scalability bottleneck).**
A naive `JwtAuthenticationFilter` that calls `loadUserByUsername` on every
incoming request to rebuild the `Authentication` object adds a DB round-trip
to every single API call — a bottleneck that gets worse exactly as traffic
grows.
*Mitigation:* Embed username and role as signed claims in the JWT at
issuance, and have the filter trust the cryptographically verified token
instead of re-querying per request. Reserve an actual DB lookup for the rare
paths that need fresh user state (e.g. an explicit "current user profile"
endpoint, or the login/refresh calls themselves).
