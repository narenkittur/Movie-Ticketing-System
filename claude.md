# Movie Ticket Booking System — Project Guide

A backend-heavy ticketing platform: browse shows, lock a seat, pay, get a
confirmed booking with a QR ticket. The hard requirement across every module
is concurrency safety — two users must never end up with the same seat.

## Tech Stack (as actually configured in `pom.xml`)

- **Language:** Java 21
- **Framework:** Spring Boot 4.1.0 (parent POM)
- **Web:** `spring-boot-starter-webmvc` (this Boot version splits the web
  starter into `-webmvc`; do not add the older `spring-boot-starter-web`
  artifact — it will conflict)
- **Security:** `spring-boot-starter-security` + JWT via `io.jsonwebtoken`
  (jjwt-api/impl/jackson `0.12.6`)
- **JSON:** Spring Boot 4.1's web stack pulls in **Jackson 3.x**, whose
  classes live under the `tools.jackson.*` package (e.g.
  `tools.jackson.databind.ObjectMapper`) — **not** the classic Jackson 2
  `com.fasterxml.jackson.*`. A same-named/shaped Jackson 2 `ObjectMapper`
  is *also* on the classpath, but only transitively (runtime scope) via
  `jjwt-jackson`'s own JSON handling for building/parsing JWTs — importing
  it instead compiles against the wrong ObjectMapper (won't match the bean
  Spring Boot auto-configures) or fails outright if only runtime-scoped.
  Jackson annotations (`@JsonFormat`, `@JsonIgnore`, etc.) are the
  exception: Jackson 3's own POM confirms `jackson-annotations`
  deliberately stayed on the Jackson 2.x `com.fasterxml.jackson.core`
  group ID/package, so those imports are unaffected. See
  `logic/jwt.md` section 2.9 for how this was discovered (a compile
  failure while implementing Module 2's JSON error handlers).
- **Persistence:** `spring-boot-starter-data-jpa` + **MySQL**
  (`mysql-connector-j`, runtime scope) — the project spec allowed
  Postgres/MySQL; **MySQL is the one actually wired up**. Don't switch to
  Postgres without updating `pom.xml` and `application.properties`.
- **Cache/Locking:** `spring-boot-starter-data-redis` (Lettuce, Spring Boot's
  default client — no separate Jedis dependency is declared)
- **Validation:** `spring-boot-starter-validation`
- **Object mapping:** Lombok is present; MapStruct is **not yet** in
  `pom.xml` — add it when DTO mapping is actually needed instead of doing it
  by hand
- **QR codes:** ZXing is **not yet** in `pom.xml` — add `com.google.zxing:core`
  and `:javase` when Module 7 starts
- **Real-time:** `spring-boot-starter-websocket` is **not yet** in `pom.xml`
  — add it when Module 6 starts
- **Test deps:** `-jpa-test`, `-redis-test`, `-security-test`,
  `-validation-test`, `-webmvc-test` starters are all already present

Corresponding test-scope starters (`-data-jpa-test`, `-data-redis-test`,
etc.) are already declared, so integration tests against a real DB/Redis
are expected, not mocked-only unit tests.

## Architectural Principles

1. **Concurrency first.** Redis is the source of truth for *ephemeral*
   seat holds. Never infer real-time lock state from the DB — the DB only
   holds durable, committed state (`Seat.status = BOOKED`).
2. **Transactional integrity.** Booking confirmation (seat re-check →
   create `Booking`/`BookingSeat` rows → mark seat `BOOKED`) happens in one
   `@Transactional` service method. Roll back the transaction and release
   the Redis lock together on any failure.
3. **Fail-fast.** A locked/booked seat returns `409 Conflict` immediately —
   no queuing, no waiting.
4. **Decoupling.** Controllers stay thin; Redis lock logic, seat-status
   rules, and payment orchestration live in the service layer, not in
   `@RestController` classes.
5. **Stateless auth.** JWT only. No `HttpSession`, no server-side session
   store.

## Data Model (as implemented — package `com.example.movieticket.model`)

- `User` — `username` (unique), `email`, `password` (hash), `role`
  (plain string, e.g. `"ROLE_USER"` / `"ROLE_ADMIN"` — not an enum),
  `1→N Booking`
- `Movie` — `title`, `description`, `durationMinutes`, `1→N Show`
- `Show` — `startTime`, `screenName`, `N→1 Movie`, `1→N Seat`
- `Seat` — `seatNumber`, `status` (plain string: `AVAILABLE` / `LOCKED` /
  `BOOKED` per the spec — not an enum), `N→1 Show`
- `Booking` — `bookingTime`, `totalPrice`, `N→1 User`, `N→1 Show`,
  `1→N BookingSeat`
- `BookingSeat` — bridge entity for `Booking N↔N Seat` (join table
  `booking_seats`). **This is the actual multi-seat design** — a single
  booking can cover several seats via `BookingSeat` rows, so don't
  reintroduce a direct `Booking→Seat` FK.
- `RefreshToken` (Module 2) — `tokenHash` (unique, SHA-256 of the opaque
  refresh token — the raw value is never stored), `N→1 User`,
  `expiryDate`, `revoked` (flipped true on logout or rotation),
  `createdAt`. See `logic/jwt.md` for the full auth design.

All six Module 1 model files were originally saved with lowercase
filenames (`booking.java`, `seat.java`, etc.) that didn't match their
`public class` names — `javac` rejects that outright. Fixed by renaming
each to match its class name exactly (`Booking.java`, `Seat.java`, ...).
`Seat.java` was also missing its `package` declaration and imports
entirely (silently broken until the first real compile was attempted).
Keep entity filenames matching their class names going forward.

`status` fields on `Seat` are plain `String`s today. If they get converted
to enums later, update this file and check every repository method/service
that compares against string literals (`"AVAILABLE"`, `"LOCKED"`,
`"BOOKED"`).

Repositories (`com.example.movieticket.repository`) are done for all six
entities with the lookups each module will need (`findByShowIdAndStatus`,
`findByMovieIdAndStartTimeBetween`, `findByBookingShowId`, etc.) — check
there before writing a new derived-query method; it may already exist.

## Module Status

- **Module 1 — Setup + Data Model: COMPLETE.** Entities, relationships,
  repositories exist. No seed data script/migration exists yet — if a
  module needs seeded movies/shows/seats, that still needs to be written
  (e.g. `data.sql` or a `CommandLineRunner`).
- **Module 2 — Auth: IMPLEMENTED** (register/login/refresh/logout,
  `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`,
  `CustomUserDetailsService`, JSON 401/403 handlers, `GlobalExceptionHandler`,
  16 passing unit tests). See `logic/jwt.md` for the design rationale and a
  couple of corrections made to `plan/authentication.md` along the way
  (section 2.1: `/auth/logout` requires auth, so the filter does not
  blanket-skip `/auth/**`). **Not yet done:** a real end-to-end run against
  a live MySQL instance (this sandbox has none — see Known Gaps), and
  OpenAPI/Swagger annotations on `AuthController` (blocked on the
  springdoc dependency below).
- **Modules 3–9: PENDING**, per the original spec.

## Known Gaps / Things to Set Up Before They're Needed

- No `docker-compose.yml` for local MySQL + Redis yet — Module 4 needs
  Redis running locally, and `MovieticketApplicationTests.contextLoads`
  currently fails without a real MySQL instance to connect to (expected —
  not a code defect; see `logic/jwt.md` section 5).
- `application.properties` now has datasource URL/credentials, Redis
  host/port, and `jwt.secret`/`jwt.access-token-expiration-ms`/
  `jwt.refresh-token-expiration-ms`, all overridable via environment
  variables (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`,
  `REDIS_PORT`, `JWT_SECRET`, `JWT_ACCESS_EXPIRATION_MS`,
  `JWT_REFRESH_EXPIRATION_MS`) with local-dev-only inline defaults. The
  `JWT_SECRET` default in particular **must** be overridden with a real
  secret in any non-local deployment.
- No OpenAPI/Swagger dependency yet, despite the project constraint that
  every public endpoint must be documented — add
  `springdoc-openapi-starter-webmvc-ui` when the first controller lands.
- This directory is **not currently a git repository**. Worth `git init`
  early if version control matters here — ask before doing it.

## Project Constraints (carry through every module)

- Every public endpoint documented via OpenAPI/Swagger.
- Every service method gets basic SLF4J logging.
- Redis lock TTL is fixed at **300 seconds** — don't hardcode a different
  value in a new module without updating this file.
- Mocked payment logic must be clearly labeled as mocked, both in code
  comments and the README, so it's never mistaken for a real integration.
