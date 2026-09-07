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
- **Object mapping:** Lombok is present; MapStruct is a deliberate **non-add**.
  Module 3 (movie/show/seat DTOs) was the moment this file originally
  flagged as "add MapStruct when DTO mapping is actually needed" — decided
  against it instead. Reasoning: this project's DTO shapes are simple
  (flat, few fields, no nested collections to map), and the user explicitly
  wants every line commentable/explainable — generated mapper
  implementations (MapStruct's whole value proposition) are the opposite of
  that, plus the annotation-processor ordering with Lombok
  (`lombok-mapstruct-binding`) is one more thing that can silently break.
  Hand-written `private XResponse toResponse(X entity)` methods in each
  `*Service` class do the same job in ~10 readable lines. Revisit only if a
  future module's DTOs get deep/nested enough that hand mapping becomes
  genuinely error-prone.
- **API documentation:** `springdoc-openapi-starter-webmvc-ui` **3.0.3**
  (Module 3) — pinned explicitly since this artifact isn't in Spring Boot's
  parent BOM. **Version matters here:** the 2.x line targets Spring Boot 3
  (Jakarta EE 9/10, Jackson 2); this project is on Spring Boot 4.1
  (Spring Framework 7, Jackson 3 — see the JSON note above), which needs
  springdoc's **3.x** line. Verified 3.0.3 resolves cleanly and its
  transitive deps don't collide with the existing Jackson 3 wiring before
  adding it. `OpenApiConfig` (package `com.example.movieticket.config`)
  defines the shared `bearerAuth` security scheme every protected
  endpoint's `@Operation(security = ...)` references — annotation-only
  class, no `@Bean` methods, scanned once at startup.
- **QR codes (Module 7):** `com.google.zxing:core` and `:javase` **3.5.3**
  (pinned via `<zxing.version>`, not in the parent BOM — same situation as
  springdoc in Module 3). `javase` pulls in `java.awt`/ImageIO
  (`MatrixToImageWriter` is AWT-backed) — safe since Spring Boot sets
  `java.awt.headless=true` by default. `QrCodeGenerator` is the only class in
  the project that imports `com.google.zxing`.
- **Real-time (Module 6):** `spring-boot-starter-websocket` is in `pom.xml`, no
  `<version>` (managed by the parent BOM). STOMP over WebSocket, one endpoint
  (`/ws`), Spring's simple in-memory broker — no external message broker, no
  SockJS. See `plan/websockets.md`/`logic/websockets.md`.
- **Payments (Module 5):** `pom.xml` is **unchanged** — no Razorpay SDK, no
  new HTTP client library. `RestClient` (already on the classpath via
  `spring-boot-starter-webmvc`) + `javax.crypto.Mac`/`java.util.HexFormat`
  (both JDK) cover the entire gateway surface. See
  `RazorpayPaymentGateway`'s javadoc for why the SDK was deliberately
  rejected (same reasoning as the MapStruct decision above).
- **Test deps:** `-jpa-test`, `-redis-test`, `-security-test`,
  `-validation-test`, `-webmvc-test` starters are all already present

**Local build environment note:** this sandbox's default JDK is Temurin 17,
not 21 — `./mvnw compile` fails outright with "release version 21 not
supported" using the ambient `JAVA_HOME`. A JDK 22 install exists on this
machine under `C:\Program Files\Java\jdk-22` but is missing `bin\java.exe`
in this environment (broken/incomplete install), so it can't be pointed at
via `JAVA_HOME` either. Module 3's code was verified to compile cleanly via
a one-off `-Dmaven.compiler.release=17` CLI override (not persisted to
`pom.xml` — the project's actual target stays Java 21 per the line above);
none of the new code uses any Java-18-through-21-only syntax, so this is a
safe diagnostic, not a real target-version change. A real JDK 21 (or
newer, properly installed) is still needed before this project can be
built/run for real in this environment.

Corresponding test-scope starters (`-data-jpa-test`, `-data-redis-test`,
etc.) are already declared, so integration tests against a real DB/Redis
are expected, not mocked-only unit tests.

## Architectural Principles

1. **Concurrency first.** Redis is the source of truth for *ephemeral*
   seat holds. Never infer real-time lock state from the DB — the DB only
   holds durable, committed state (`Seat.status = BOOKED`).
2. **Transactional integrity.** ~~Booking confirmation (seat re-check →
   create `Booking`/`BookingSeat` rows → mark seat `BOOKED`) happens in one
   `@Transactional` service method.~~ **Amended by Module 5
   (`plan/payment.md` section 1.2, 3):** a real payment gateway makes payment
   asynchronous, so creation and confirmation are now TWO separate requests
   separated by an unbounded amount of real-world time, not one. `POST
   /bookings` creates a `PENDING` booking + `BookingSeat` rows (no seat is
   marked `BOOKED` yet - see the Data Model note on `Booking`/`Payment`
   below); confirmation - `BookingService.confirmPaid`, shared by the
   browser-callback and webhook paths - is still exactly one
   `@Transactional` method that re-checks the seats, marks them `BOOKED`,
   and flips the booking to `CONFIRMED`. Roll back that transaction and
   leave the Redis lock standing (until its own TTL) on any failure - never
   release before commit.
3. **Fail-fast.** A locked/booked seat returns `409 Conflict` immediately —
   no queuing, no waiting.
4. **Decoupling.** Controllers stay thin; Redis lock logic, seat-status
   rules, and payment orchestration live in the service layer, not in
   `@RestController` classes.
5. **Stateless auth.** JWT only. No `HttpSession`, no server-side session
   store. **Module 6 footnote:** a WebSocket session is the one place this
   project holds any server-side session state at all — `WebSocketSessionRegistry`
   tracks live `/ws` sessions so one can be force-closed. This isn't an auth
   session store (no credentials live there, just a session-id → socket
   lookup), and it exists specifically to keep a socket's *authenticated
   lifetime* bounded to its access token's, per `plan/websockets.md` Open
   Decision A — the alternative was a socket that outlives its 30-minute token
   indefinitely, a bigger violation of this principle's spirit than the small
   in-memory registry that prevents it.

**Principle #1 in practice (Module 3):** `GET /shows/{showId}/seats` is where
this rule is actually load-bearing for the first time. Module 3 ships
without Redis, so it can only ever legitimately return `AVAILABLE`/`BOOKED`
(both real DB state) — never `LOCKED`. Rather than hardcode that limitation
into `SeatService`, it's expressed as a seam:
`com.example.movieticket.service.SeatLockView` (interface) +
`NoOpSeatLockView` (Module 3's implementation, always returns an empty
locked-seat set). `SeatService` always computes each seat's *effective*
status as `BOOKED > (LOCKED if SeatLockView says so) > DB status`, so when
Module 4 adds a Redis-backed `SeatLockView` implementation and swaps it in,
**no `SeatController` or DTO changes** are needed — only which bean gets
injected. Do not shortcut this by writing `"LOCKED"` into `Seat.status`
directly in Module 4; that couples ephemeral state to durable state and has
no TTL to expire it (see `plan/crud.md` section 6 for the full writeup).

**Amended by Module 4's design (`plan/redis.md` section 8):** `plan/crud.md`
section 6 and `logic/listing-deep-dive.md` section 2.7 originally promised
that **`SeatService` itself would need zero changes** either. That promise is
knowingly traded for **one line**. The seam's method becomes
`Set<Long> lockedSeatIds(Long showId, Collection<Long> candidateSeatIds)`, and
`SeatService.getSeatLayout` passes the seat ids it has already loaded. Reason:
the single-argument form leaves a Redis implementation no option but
`SCAN MATCH seat_lock:{showId}:*`, which is O(the entire keyspace) on the
single hottest endpoint in the app (the seat map a frontend polls) against a
single-threaded server. With the ids supplied it is one exact `MGET`, O(seats
in the show). What the seam actually had to protect — no DTO change, no
controller change, and above all **no change to `toSeatDto`'s status
precedence**, the one place principle #1 could be broken by accident — still
holds exactly. Don't "restore" the old signature without reading that section.

## Data Model (as implemented — package `com.example.movieticket.model`)

- `User` — `username` (unique), `email`, `password` (hash), `role`
  (plain string, e.g. `"ROLE_USER"` / `"ROLE_ADMIN"` — not an enum),
  `1→N Booking`
- `Movie` — `title`, `description`, `durationMinutes`, `1→N Show`
- `Show` — `startTime`, `screenName`, `price` (`BigDecimal`, added Module 3
  — see below), `N→1 Movie`, `1→N Seat`
- `Seat` — `seatNumber`, `status` (plain string: `AVAILABLE` / `LOCKED` /
  `BOOKED` per the spec — not an enum; `LOCKED` is never actually persisted,
  see the Principle #1 note above), `N→1 Show`. **Module 3:** added a
  DB-level `UNIQUE(show_id, seat_number)` constraint
  (`uk_seat_show_seatnumber`) — belt-and-braces against a seat layout being
  generated twice for the same show under concurrent admin requests (same
  pattern as the `users` username/email constraint from Module 2).
- `Booking` — `bookingTime`, `totalPrice` (`BigDecimal(10,2)`, **Module 5** —
  was `double`, see below), `status` (**Module 5**, `BookingStatus` enum:
  `PENDING`/`CONFIRMED`/`FAILED`/`EXPIRED`/`REFUND_PENDING`/`REFUNDED`),
  `expiresAt` (**Module 5**, `bookingTime + seatlock.ttl-seconds`, stored not
  recomputed), `bookingReference` (**Module 5**, unique UUID - for Module 7's
  QR code, deliberately not the numeric id), `checkedInAt` (**Module 7**,
  nullable — null until the ticket is scanned at the gate, the instant of the
  FIRST successful scan thereafter; deliberately NOT a `BookingStatus` value,
  see that enum's javadoc), `checkedInBy` (**Module 7**, nullable plain `Long`
  staff user id — an audit breadcrumb, not a `@ManyToOne`), `N→1 User`,
  `N→1 Show`, `1→N BookingSeat`, `1→1 Payment` (**Module 5**). Both Module 7
  columns are new and nullable — `ddl-auto=update` adds them cleanly, **no
  manual migration needed** (unlike `totalPrice`'s type change below).
- `BookingSeat` — bridge entity for `Booking N↔N Seat` (join table
  `booking_seats`, `UNIQUE(booking_id, seat_id)` added in Module 5 - prevents
  a seat being duplicated *within* one booking; see the corrected backstop
  note under Project Constraints below for why `UNIQUE(seat_id)` alone can't
  exist). **This is the actual multi-seat design** — a single
  booking can cover several seats via `BookingSeat` rows, so don't
  reintroduce a direct `Booking→Seat` FK.
- `Payment` (**Module 5**, table `payments`) — one row per booking's payment
  attempt (`@OneToOne` with `Booking`, not `@OneToMany` - a retry after
  failure creates a new `Booking` instead, since its Redis lock has to be
  re-acquired anyway). `provider` ("mock"/"razorpay"), `providerOrderId`
  (unique - the module's idempotency key), `providerPaymentId`,
  `checkoutUrl`, `amount`, `currency`, `status` (`PaymentStatus` enum:
  `CREATED`/`CAPTURED`/`FAILED`/`REFUND_PENDING`/`REFUNDED`), `failureReason`,
  `createdAt`/`updatedAt`. See `logic/payment.md` for the full design.

  **First entity enums in the project:** `BookingStatus`/`PaymentStatus` are
  real Java enums, while `Seat.status` and `User.role` stay plain `String`s
  (see the note just below `status` fields on `Seat` for the original
  flag of this inconsistency). Deliberate: these are brand-new Module 5
  fields with no legacy rows and no string literals scattered across Modules
  3/4 to hunt down - converting `Seat.status`/`User.role` in the same breath
  would put an unrelated refactor inside a payments module. Revisit both
  together if `Seat.status` is ever converted.
- `RefreshToken` (Module 2) — `tokenHash` (unique, SHA-256 of the opaque
  refresh token — the raw value is never stored), `N→1 User`,
  `expiryDate`, `revoked` (flipped true on logout or rotation),
  `createdAt`. See `logic/jwt.md` for the full auth design.

**Pricing (Module 3):** the original spec had no price field anywhere —
`Movie`, `Show`, and `Seat` were all priceless, yet `Booking.totalPrice`
existed with nothing to compute it from. Added `Show.price` (flat
per-showing price, `BigDecimal(10,2)` — not `double`/`float`, since money
must never lose cents to binary floating-point rounding). Deliberately on
`Show`, not `Seat`: this project prices a whole showing the same regardless
of which seat you pick, no premium/regular seat tiers. If seat-tier pricing
is ever wanted, that's a `Seat.price` addition layered on top later, not a
replacement of this field. Module 5's `Booking.totalPrice` should be
computed as `show.price * seatCount` at booking time.

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
  OpenAPI/Swagger annotations on `AuthController` (the springdoc dependency
  landed in Module 3 — see below — but `AuthController` itself hasn't been
  backfilled with `@Operation` annotations yet; it still works and is
  publicly reachable, it just won't show up nicely in Swagger UI).
- **Module 3 — Movie/Show/Seat CRUD: IMPLEMENTED.** Design doc at
  `plan/crud.md`. `MovieController`/`ShowController`/`SeatController` +
  matching `*Service` classes for all 7 endpoints (create/update movie,
  create show, generate seat layout, list movies, list shows for a movie,
  get seat layout). Full class-by-class breakdown is in `plan/crud.md`
  section 10 (as-built matches the plan, with two deviations noted where
  they're described above: MapStruct not added, `InvalidSeatLayoutException`
  dropped in favor of Bean Validation covering the same case). Highlights:
  - **Fixed a real Module 2 security gap:** `SecurityConfig` was missing
    `@EnableMethodSecurity`, meaning every `@PreAuthorize("hasRole('ADMIN')")`
    anywhere in the app — including ones the Module 2 plan assumed already
    worked — was silently unenforced. Module 3 is the first module with
    admin-only endpoints, so it added the annotation plus a coarse
    `/admin/**` URL-level rule (`SecurityConfig`) as defense-in-depth
    alongside the per-method `@PreAuthorize` checks.
  - Screen double-booking is prevented without a persisted `Show.endTime`
    column — `ShowService.assertNoScreenConflict()` fetches same-screen
    candidates in a bounded lookback window and computes each one's end
    time on the fly from `movie.durationMinutes` (`plan/crud.md` section
    4.2's "no-schema-change" option).
  - See the Principle #1 note above and `SeatLockView`/`NoOpSeatLockView`
    for how the Module 4 handoff is designed to be a drop-in.
  - **Not yet done:** integration tests against a live MySQL instance (same
    sandbox limitation as Module 2 — see Known Gaps), and a real end-to-end
    run/Swagger UI smoke test (only compiled, not run, in this
    environment — see the local build environment note under Tech Stack).
- **Module 4 — Redis Seat Locking: IMPLEMENTED.** Design doc at
  `plan/redis.md`; as-built writeup with a couple of implementation-time
  decisions at `logic/redis-locking.md`. Key schema
  `seat_lock:{showId}:{seatId}`, all-or-nothing multi-seat acquire via
  `lock_seats.lua`, compare-and-delete release via `unlock_seats.lua`,
  TTL-as-reconciliation (no sweeper/cleanup code anywhere), `RedisSeatLockView`
  replacing — not coexisting with — `NoOpSeatLockView` (deleted). Three
  endpoints (`POST`/`DELETE /shows/{showId}/seats/lock`,
  `GET /shows/{showId}/seats/locks/mine`), `SeatLockService` (lock/release/
  myLocks + the Module 5 hooks `assertHoldsAll`/`releaseAfterCommit`),
  `RedisConfig` (the two `DefaultRedisScript<List>` beans), the four new
  exception types wired into `GlobalExceptionHandler` (plus a
  `DataAccessException` catch-all → `503` for defense in depth), and
  `docker-compose.yml` at the project root (Redis + MySQL, no persistence on
  the Redis service — intentional, see `plan/redis.md` section 9-F). 12
  passing unit tests (`SeatLockServiceTest`, `SeatServiceTest` — the latter
  finally covers the `toSeatDto` precedence rule that Module 3 shipped
  untested). Highlights/decisions, all per the design doc:
  - **Three endpoints, not two.** The extra one is
    `GET /shows/{showId}/seats/locks/mine` (which seats do *I* hold, and for
    how long) — without it a page refresh loses the checkout countdown,
    since a client can't distinguish its own `LOCKED` seats from anyone
    else's. ⚠️ Its `SecurityConfig` matcher is declared **before** the
    existing `permitAll` rule for `GET /shows/**` (`plan/redis.md` section 10)
    — verified by reading the matcher order, not just by the endpoint working.
  - **The public seat map fails closed too.** `GET /shows/{showId}/seats`
    now depends on Redis (`RedisSeatLockView`), so it returns `503` on a
    Redis failure rather than reporting locked seats as `AVAILABLE`.
  - The `SeatLockView` signature change — see the Principle #1 amendment
    above — is implemented; `SeatService.getSeatLayout` passes the seat ids
    it already loaded.
  - `spring.data.redis.timeout`/`connect-timeout` are set to `1000ms` in
    `application.properties`, overriding Lettuce's 60s default — see the
    Project Constraints entry below.
  - **Not yet done:** a real end-to-end run against a live Redis/MySQL (this
    sandbox has neither — see Known Gaps), and the concurrency/TTL
    integration tests from `plan/redis.md` section 14 that need a real Redis
    (Open Decision C recommended Testcontainers; not added yet — see Known
    Gaps).
- **Module 5 — Payment + Transactional Booking: IMPLEMENTED.** Design doc at
  `plan/payment.md`; as-built writeup (deviations, trap checklist) at
  `logic/payment.md`. **Scope change from the original spec:** ships a real
  Razorpay TEST-mode gateway behind a seam, not a synchronous mock — see
  `plan/payment.md` section 1.1 for why "real" costs nothing here. Key
  pieces: `PaymentGateway` interface + `MockPaymentGateway` (default,
  `matchIfMissing=true`, offline, no credentials) + `RazorpayPaymentGateway`
  (real, `@ConditionalOnProperty("payment.gateway", havingValue="razorpay")`,
  no SDK — see that class's javadoc); the async `BookingStatus` state
  machine (`PENDING → CONFIRMED/FAILED/EXPIRED/REFUND_PENDING → REFUNDED`,
  `EXPIRED` derived at read time, never swept); `BookingService.confirmPaid`
  as the single idempotent method both the browser-callback
  (`POST /bookings/{id}/confirm`) and webhook (`POST /payments/webhook`)
  paths funnel into, guarded by a `PESSIMISTIC_WRITE` lock on the `payments`
  row; 7 endpoints (`BookingController` ×5, `PaymentWebhookController`,
  `MockGatewayController` — mock mode only); `PaymentSignatures` (shared
  constant-time HMAC helper); new `Payment`/`BookingStatus`/`PaymentStatus`
  model classes; 4 new exception types wired into `GlobalExceptionHandler`.
  21 new passing unit tests (`BookingServiceTest`, `PaymentSignaturesTest`,
  `MockPaymentGatewayTest`), all 49 project tests green (see Known Gaps for
  the one pre-existing, environment-only failure). Highlights/decisions:
  - **Open Decisions A–E from `plan/payment.md` section 13, all resolved
    with the user before implementation:** (A) `refund()` is implemented
    but never auto-invoked — `REFUND_PENDING` + `log.error` is the terminal
    state until an admin-triggered refund lands in Module 9. (B) `POST
    /bookings` dedupes: a double-click for the same user+show+seat-set
    reuses the existing non-expired `PENDING` booking and its `checkoutUrl`
    instead of creating a second, payable one. (C) No retry on gateway
    failure — fail fast, `502`. (D) Testcontainers still deferred (same gap
    as Module 4). (E) `SeatService`/seat DTOs untouched — confirmed correct.
  - **The `createBooking` self-invocation fix.** `POST /bookings` is three
    steps (`plan/payment.md` section 5): a DB transaction, then an
    un-transactional network call to the gateway, then a second DB
    transaction — all required to live in `BookingService` per principle
    #4, but a same-class call bypasses Spring's `@Transactional` proxy
    entirely. `BookingService` injects a `@Lazy` self-reference (`self`) and
    routes the two transactional steps through it — see that class's
    javadoc for the full explanation of why, since this is the module's
    least obvious piece of code.
  - **`Payment.checkoutUrl` and a nullable `providerOrderId`** were added/
    relaxed beyond `plan/payment.md` section 7.2's schema table, which
    both omitted `checkoutUrl` and marked `providerOrderId` "not null" while
    section 5 itself requires persisting a null one at creation time — see
    `Payment`'s javadoc and `logic/payment.md` for the full deviation note.
  - **`EXPIRED` is computed on every read**, not opportunistically persisted
    the first time a stale row is touched as section 3.3's wording implies —
    functionally identical to every caller, simpler, no extra write path.
  - **Not yet done:** a real end-to-end run against live MySQL/Redis/Razorpay
    (same sandbox limitation as every prior module), and the
    Testcontainers-backed concurrency tests from Open Decision D.
- **Module 6 — WebSockets: IMPLEMENTED.** Design doc at `plan/websockets.md`;
  as-built writeup (deviations, trap checklist) at `logic/websockets.md`. STOMP
  over WebSocket, one endpoint (`/ws`), Spring's simple in-memory broker — no
  `@MessageMapping` anywhere, the socket is push-only. Two destinations:
  `/topic/shows/{showId}/seats` (public seat-status deltas, anyone incl.
  anonymous) and `/user/queue/bookings` (the payer's own booking push,
  authenticated only). Key pieces: `WebSocketConfig`/`WebSocketSchedulerConfig`
  (transport + broker wiring), `StompAuthChannelInterceptor` (JWT rides the
  STOMP `CONNECT` frame's native header, not the handshake — a browser's
  `WebSocket` constructor cannot set request headers), `WebSocketSessionRegistry`
  (tracks live sessions so one can be force-closed), `SeatBroadcaster`/
  `BookingBroadcaster` (the two `@EventListener`/`@TransactionalEventListener`
  fan-out points), `SeatLockExpiryListener` (Redis keyspace-notification
  subscriber), `SeatStatusUpdate`/`BookingStatusChangedEvent` (new DTOs/events).
  20 new passing unit tests (`SeatBroadcasterTest`, `BookingBroadcasterTest`,
  `SeatLockExpiryListenerTest`, `SeatLockKeysTest`,
  `StompAuthChannelInterceptorTest`, plus additions to `SeatLockServiceTest`/
  `BookingServiceTest`), 71 of 72 project tests green (the one failure is
  pre-existing `JwtServiceTest` flakiness unrelated to this module — see Known
  Gaps). Highlights/decisions:
  - **Closed the two holes `plan/websockets.md` existed to close, not just added
    a transport.** `SeatLockService.releaseAfterCommit` — called by both
    `BookingService.confirmPaid` (a sale) and `cancelBooking` (a genuine
    release) — previously published no event at all for either. It now takes a
    required `resultingStatus` (`BOOKED`/`RELEASED`) and publishes accordingly:
    `BOOKED` unconditionally (the DB already committed the sale regardless of
    whether Redis heard about the unlock), `RELEASED` only if the unlock
    actually succeeded (never broadcast `AVAILABLE` for a lock that might still
    stand). And `SeatLockExpiryListener` gives TTL expiry — previously silent by
    design — a Redis keyspace-notifications-driven event, gated by
    `websocket.expiry-notifications.enabled` (default `true`) since it depends
    on Redis *server* config (`docker-compose.yml`'s `--notify-keyspace-events
    Kx`) the app can't assert at startup.
  - **`/ws/**` is `permitAll`, ordered ahead of `anyRequest().authenticated()`**
    (`SecurityConfig`) — the third instance of this project's matcher-ordering
    trap (Module 4's `locks/mine`, Module 5's webhook), and the most forgiving:
    declared too late, the handshake just 401s loudly at the first connection
    attempt. Authentication itself happens one layer up, in
    `StompAuthChannelInterceptor` reading the STOMP `CONNECT` frame — not
    `JwtAuthenticationFilter`, which never sees a WebSocket handshake's (header-
    less) request at all.
  - **A WebSocket session is capped at its authenticating access token's
    lifetime** (resolved Open Decision A) — `StompAuthChannelInterceptor`
    schedules a force-close via a shared `TaskScheduler` at the token's `exp`.
    Needed more than the plan's "roughly fifteen lines" estimate: Spring's STOMP
    stack has no built-in way to close one specific session from application
    code, so `WebSocketSessionRegistry` + a `WebSocketHandlerDecoratorFactory`
    in `WebSocketConfig` exist purely to make that possible. The `TaskScheduler`
    bean lives in its own `WebSocketSchedulerConfig` class, not on
    `WebSocketConfig` itself, to avoid a circular dependency (see
    `logic/websockets.md` section 2.3-2.4 for the full explanation).
  - **`SeatLockChangedEvent` gained a fourth field, `expiresAt`**, beyond what
    the plan's section 4.2 called for — needed so a `LOCKED` broadcast can carry
    the client's countdown target without a second Redis round trip in the
    broadcaster. Non-null only when `type() == LOCKED`.
  - **Not yet done:** a real end-to-end run against live MySQL/Redis (same
    sandbox limitation as every prior module), and the `WebSocketStompClient`
    integration tests from `plan/websockets.md` section 10 (tests 9-10) that
    need a real Spring context + MySQL + Redis (Testcontainers still deferred,
    Open Decision D again — see Known Gaps).
- **Module 7 — QR Tickets: IMPLEMENTED.** Design doc at `plan/qrtickets.md`;
  as-built writeup at `logic/qrtickets.md`. Three endpoints
  (`GET /bookings/{id}/qr`, `GET /bookings/{id}/ticket`,
  `POST /admin/tickets/validate`), one new nullable column pair
  (`Booking.checkedInAt`/`checkedInBy`), one new property
  (`ticket.checkin.opens-minutes-before`), **no new `SecurityConfig`
  matcher** (the validate endpoint lives under the existing `/admin/**` rule).
  13 new passing unit tests (`QrCodeGeneratorTest` — a real ZXing encode→decode
  round trip, `TicketServiceTest`), 85 of 86 project tests green (the one
  failure is the pre-existing `MovieticketApplicationTests.contextLoads`
  environment gap — see Known Gaps). Highlights/decisions:
  - **The QR payload is the bare `bookingReference` UUID** — no prefix, JSON,
    URL, or HMAC signature. A random UUID looked up in our own database is a
    nonce, not a claim: nothing to tamper with, nothing to sign
    (`plan/qrtickets.md` section 4.1 has the full rejected-alternatives
    reasoning for a signed payload and a URL).
  - **Redemption is a single-use state transition guarded by a row lock** —
    `BookingRepository.findWithLockByBookingReference` (`PESSIMISTIC_WRITE`,
    a direct mirror of `PaymentRepository.findWithLockByProviderOrderId`) inside
    one non-`readOnly` `@Transactional` method, `TicketService.validate`. This
    is the module's actual substance (`plan/qrtickets.md` section 5) — the
    QR-rendering endpoint is the easy half.
  - **`POST /admin/tickets/validate` always returns `200`** with an explicit
    `result` enum (`VALID`/`ALREADY_USED`/`NOT_CONFIRMED`/`OUTSIDE_WINDOW`/
    `NOT_FOUND`), a deliberate deviation from this project's usual
    exception-to-status-code convention — the caller is a turnstile that must
    act differently on each outcome, not a browser. Genuine failures (bad JWT,
    non-admin, blank code, dead DB) still throw and still map through
    `GlobalExceptionHandler` normally. `NOT_FOUND` never echoes the submitted
    code back.
  - **The check-in window** (`showTime - opens-minutes-before` through
    `showTime + movie.durationMinutes`) reuses the same derive-the-end-time-
    on-the-fly approach `ShowService.assertNoScreenConflict` established in
    Module 3, rather than persisting a `Show.endTime` column.
  - `checkedInBy` (Open Decision A) was included, as recommended — a plain
    `Long`, not a `@ManyToOne`.
  - `BookingController` gained one small addition beyond the plan's own
    prose: a `ConstraintViolationException` handler in
    `GlobalExceptionHandler`, needed for `@Min`/`@Max` on `GET
    /bookings/{id}/qr`'s bare `?size=` query param to actually 400 instead of
    500 — see `logic/qrtickets.md` section 2.1.
  - **Not yet done:** a real end-to-end run against live MySQL/Redis (same
    sandbox limitation as every prior module), and the two-turnstile
    concurrent-scan race (`plan/qrtickets.md` section 8 test 9) — needs
    Testcontainers, deferred for the fourth module running (Known Gaps).
- **Modules 8–9: PENDING**, per the original spec.

## Known Gaps / Things to Set Up Before They're Needed

- ~~No `docker-compose.yml` for local MySQL + Redis yet~~ — added in Module 4
  (project root, matching `plan/redis.md` section 13 exactly). Local dev is
  `docker compose up -d` then `./mvnw spring-boot:run`, no env vars needed.
  `MovieticketApplicationTests.contextLoads` still fails in **this sandbox**
  without a real MySQL to connect to — that's an environment limitation (no
  Docker here), not a code defect; see `logic/jwt.md` section 5.
- No Testcontainers dependency still. Module 4's headline test — N threads
  racing for one seat, exactly one wins — is worthless against a mocked
  `StringRedisTemplate` and needs a real Redis (`plan/redis.md` section 15-C
  recommended Testcontainers). The unit tests that shipped with Module 4
  (`SeatLockServiceTest`) cover the service's branching logic with a mocked
  template; the concurrency/idempotent-re-lock/TTL-expiry integration tests
  from `plan/redis.md` section 14 still need this dependency added and a
  real dev machine with Docker to run them on. **Module 5 makes the case
  stronger** (`plan/payment.md` Open Decision D): the double-confirmation
  idempotency test is only fully convincing against a real MySQL that
  actually honours `SELECT … FOR UPDATE`, since a mocked repository just
  returns whatever it's told. Deferred alongside Module 4's identical gap,
  to be fixed together on real hardware.
- **`bookings.total_price` needs a manual migration on any pre-existing
  database.** Module 5 changed `Booking.totalPrice` from `double` to
  `BigDecimal(10,2)` (it was a bug the whole time — see the Data Model note
  above) — `spring.jpa.hibernate.ddl-auto=update` will **not** narrow/convert
  an existing column type. On a database where `bookings` already exists
  (nothing ever wrote to this column before Module 5, so this only bites a
  reviewer's or CI's persistent DB, not a fresh one):
  ```sql
  ALTER TABLE bookings MODIFY total_price DECIMAL(10,2) NOT NULL;
  ```
  Dropping the table is equally valid locally, since no booking row has ever
  been written by any code before this module. See `README.md`'s runbook.
- `application.properties` now has datasource URL/credentials, Redis
  host/port, and `jwt.secret`/`jwt.access-token-expiration-ms`/
  `jwt.refresh-token-expiration-ms`, all overridable via environment
  variables (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`,
  `REDIS_PORT`, `JWT_SECRET`, `JWT_ACCESS_EXPIRATION_MS`,
  `JWT_REFRESH_EXPIRATION_MS`) with local-dev-only inline defaults. The
  `JWT_SECRET` default in particular **must** be overridden with a real
  secret in any non-local deployment.
- ~~No OpenAPI/Swagger dependency yet~~ — added in Module 3
  (`springdoc-openapi-starter-webmvc-ui` 3.0.3). `AuthController`
  (Module 2) still isn't annotated with it though — see Module Status
  above.
- No seed data for movies/shows exists yet (same gap Module 1 already
  flagged) — Module 3's endpoints are fully functional but there's nothing
  in the DB to list until an admin calls `POST /admin/movies` etc., or a
  `data.sql`/`CommandLineRunner` seed script is written.
- This directory is **not currently a git repository**. Worth `git init`
  early if version control matters here — ask before doing it.
- **The WebSocket broker is single-instance (Module 6, `plan/websockets.md`
  section 9-A).** Spring's simple broker lives in one JVM's memory — a lock
  acquired on instance A publishes an `ApplicationEvent` that instance B's
  viewers never hear, and they have no way to know they missed it (their seat
  map just goes stale until the next `GET /shows/{showId}/seats`). This is a
  documented constraint on deployment topology, not an oversight: the app is
  correct on one instance and lossy on several. Two named fixes, neither
  built: relay `SeatStatusUpdate`/booking pushes through Redis pub/sub
  (Redis is already a dependency, and `RedisConfig` already has a
  `RedisMessageListenerContainer` after Module 6), or swap the simple broker
  for a real one (`enableStompBrokerRelay` against RabbitMQ/ActiveMQ).
- **`WebSocketStompClient` integration tests (Module 6, `plan/websockets.md`
  section 10 tests 9-10) are written down as needed but not written as code**
  — same Testcontainers/no-Docker sandbox gap as Modules 4-5's concurrency
  tests, now a third module deep. All three should be fixed together on real
  hardware rather than one at a time.
- **The two-turnstile concurrent-scan race (Module 7, `plan/qrtickets.md`
  section 8 test 9) is deferred**, same Testcontainers/no-Docker gap — fourth
  consecutive module to want it (Modules 4, 5, 6, 7). Worthless against a
  mocked `BookingRepository`, which will happily return `checkedInAt == null`
  to every concurrent caller; needs a real MySQL that actually honours
  `SELECT … FOR UPDATE`. Fix all four at once on real hardware.
- **No `ROLE_STAFF` role exists (Module 7, `plan/qrtickets.md` section 5.5).**
  `POST /admin/tickets/validate` is `ROLE_ADMIN`-gated for now — a real gate
  operator isn't an administrator, but adding a third role touches
  registration, the `role` claim's documented value set, `SecurityConfig`, and
  a new admin-only role-assignment endpoint, which is a self-contained
  user-management feature that belongs in Module 9, not folded into the QR
  module.

## Project Constraints (carry through every module)

- Every public endpoint documented via OpenAPI/Swagger.
- Every service method gets basic SLF4J logging.
- Redis lock TTL is fixed at **300 seconds** — don't hardcode a different
  value in a new module without updating this file. No lock-extension /
  heartbeat mechanism, ever — if 300s is wrong, change the constant here.
- **Redis locking standards (Module 4 design, `plan/redis.md`):**
  - Lock keys are `seat_lock:{showId}:{seatId}`, value = owning user's DB id.
    The `{}` here is placeholder notation — keys are written literally as
    `seat_lock:12:101`. (Note for any future move to Redis Cluster: `{}` is
    *literal hash-tag syntax* there, and the multi-key Lua scripts below
    become illegal once a show's seats span slots. See `plan/redis.md` §3.)
  - Multi-seat acquisition is **all-or-nothing via a single Lua script**
    (`lock_seats.lua`) — not a loop of per-seat `SET NX` calls (avoids the
    transient phantom-LOCKED window during rollback).
  - Lock release is **always** compare-and-delete via Lua
    (`unlock_seats.lua`: `DEL` only if value == caller's userId). A bare
    `DEL` on a lock key is forbidden anywhere in the codebase — it can
    delete another user's legitimately acquired lock after a TTL expiry race.
  - **Re-locking a seat you already hold succeeds but must NOT refresh its
    TTL** — `lock_seats.lua`'s second pass uses `SET NX`, leaving keys the
    caller owns untouched. This is what keeps retries idempotent without
    quietly becoming the banned heartbeat.
  - **Max 10 seats per lock request**, enforced by `@Size(max = 10)` on
    `SeatLockRequest.seatIds`. Deliberately not a property — Bean Validation
    needs a compile-time constant, and one source of truth beats an
    annotation and a service check that can drift apart.
  - Booking confirmation order is fixed: verify ownership inside the DB
    transaction → commit → release locks **after** commit. Never release
    before commit. `SeatLockService.assertHoldsAll` /
    `releaseAfterCommit` exist so Module 5 inherits this ordering rather
    than re-deriving it. **Module 6 amendment:** `releaseAfterCommit` now
    takes a required fourth parameter, `SeatLockChangedEvent.Type
    resultingStatus` (`BOOKED` or `RELEASED`) — the caller must say which
    of the two opposite meanings this release has, since
    `BookingService.confirmPaid` (a sale) and `cancelBooking` (a genuine
    release) both call the same method. See `plan/websockets.md` section
    4.2/4.3 and `logic/websockets.md` section 2.2 for why a single
    unconditional `RELEASED` would have been wrong.
  - **Fail closed:** if Redis is unreachable or a lock operation times out,
    the lock endpoints **and the public seat map** return `503` — never
    assume a seat is unlocked. The Module 5 DB re-check is the last-line
    backstop, not the primary gate. ~~The `uk_seat_show_seatnumber`-style
    constraint backstops this too~~ — **corrected by Module 5
    (`plan/payment.md` section 7.3):** no such constraint exists on
    `booking_seats`, and the obvious one, `UNIQUE(seat_id)`, *can't* be
    added — `BookingSeat` rows are created at `PENDING` time (before
    payment), so the same seat legitimately appears in an earlier abandoned
    booking *and* a later successful one; a per-seat unique index would make
    every retry after an abandoned checkout fail with `409` forever. What's
    added instead is `UNIQUE(booking_id, seat_id)`, which only stops a seat
    duplicating *within* one booking. The real backstop is
    `BookingService.confirmPaid`'s `SELECT … FOR UPDATE` re-read of the seat
    rows plus the `BOOKED` status check — stronger than a unique index would
    be, since it also catches the cross-booking case a per-row constraint
    can't see.
  - ⚠️ **`spring.data.redis.timeout` must stay set (1s).** Lettuce's default
    command timeout is **60 seconds**, so a *hung* Redis — not even a dead
    one — blocks every request thread and takes down endpoints that never
    touch Redis. Every "fail fast" claim in this project depends on this one
    property being present.
- Mocked payment logic must be clearly labeled as mocked, both in code
  comments and the README, so it's never mistaken for a real integration.
- **QR ticket check-in window (Module 7, `plan/qrtickets.md` section 5.3):**
  `ticket.checkin.opens-minutes-before` (default 60) sets how early before
  `Show.startTime` the gate accepts a scan; the window closes at
  `startTime + movie.durationMinutes`, derived on every call rather than
  persisted — same pattern as `ShowService.assertNoScreenConflict`'s end-time
  derivation, not a new property. Late arrivals are allowed for the film's
  full runtime, deliberately — the seat is already `BOOKED` regardless.
