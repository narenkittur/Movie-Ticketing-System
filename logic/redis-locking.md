# Module 4 — Redis Seat Locking: Implementation Notes

Status: **implemented.** This document explains where the shipped code follows
`plan/redis.md` exactly, and the handful of places an implementation-time judgment
call had to be made that the design doc left open or didn't specify. Line-by-line
"what this code does" is covered by comments in the source files; this is the "why."

## 1. Files added/changed

| File | Purpose |
|---|---|
| `resources/redis/lock_seats.lua`, `unlock_seats.lua` | The two atomic scripts - unchanged, byte-for-byte, from `plan/redis.md` sections 5.1/5.2 |
| `config/RedisConfig.java` | New - the project's first Redis `@Bean`s, loads both scripts from the classpath |
| `service/SeatLockKeys.java` | New - single source of truth for the `seat_lock:<showId>:<seatId>` key format |
| `service/SeatLockChangedEvent.java` | New - the Module 6 seam (`ApplicationEventPublisher`), a plain `record` |
| `service/SeatLockView.java` | Edited - signature gains `Collection<Long> candidateSeatIds` |
| `service/RedisSeatLockView.java` | New - the real seam implementation, one `MGET` |
| `service/NoOpSeatLockView.java` | **Deleted** - replaced, not kept alongside |
| `service/SeatService.java` | Edited - one line in `getSeatLayout` passes the loaded seat ids to the seam |
| `service/SeatLockService.java` | New - `lock`/`release`/`myLocks` + the Module 5 hooks `assertHoldsAll`/`releaseAfterCommit` |
| `controller/SeatLockController.java` | New - the three endpoints, thin |
| `dto/SeatLockRequest.java`, `SeatLockResponse.java`, `SeatLockReleaseResponse.java` | New |
| `exception/SeatNotFoundException.java`, `SeatUnavailableException.java`, `SeatLockExpiredException.java`, `SeatLockUnavailableException.java` | New - 404/409/409/503 respectively |
| `exception/GlobalExceptionHandler.java` | Edited - four new handlers, plus a `DataAccessException` catch-all → 503 |
| `repository/SeatRepository.java` | Edited - added `findByIdInAndShowId` |
| `security/SecurityConfig.java` | Edited - the ordered matcher for `GET /shows/*/seats/locks/mine` |
| `application.properties` | Edited - Redis timeout/connect-timeout/password, `seatlock.ttl-seconds` |
| `docker-compose.yml` | New, project root - Redis + MySQL, matching `plan/redis.md` section 13 |
| `service/SeatLockServiceTest.java`, `service/SeatServiceTest.java` | New - unit tests, see section 3 |

The design (endpoint shapes, Lua scripts, key schema, failure modes, security
wiring) is unchanged from `plan/redis.md` - that document is still the design of
record and isn't repeated here. This file only covers what needed a decision beyond
what the plan specified.

## 2. Decisions made during implementation

### 2.1 `assertHoldsAll`/`releaseAfterCommit` read the Redis exceptions the same way the endpoints do

`plan/redis.md` section 11.1 specifies the *ordering* (verify inside the
transaction → commit → release after commit) but doesn't spell out what
`assertHoldsAll` should do if the `MGET` it needs throws. Made it consistent with
every other Redis read in this module: catch `DataAccessException`, throw
`SeatLockUnavailableException` (503) rather than let the booking transaction
silently treat "Redis didn't answer" as "the caller doesn't hold the seat." A `503`
from inside a booking attempt is honest; a `409` would tell the user their seats
were lost when the truth is "we couldn't check."

`releaseAfterCommit`'s callback runs the opposite way on purpose: it swallows a
Redis failure rather than propagating it. By the time the `afterCommit` hook fires,
the booking has already committed - there is nothing left to roll back, and letting
the exception escape would only confuse the caller of an operation that already
succeeded. The lock outliving the sale by up to the 300s TTL self-corrects, per the
module's own "TTL as reconciliation" thesis (`plan/redis.md` section 2).

### 2.2 Resolving an authenticated user with no matching DB row

Every service method resolves the caller's id via
`userRepository.findByUsername(authentication.getName())`. `plan/redis.md` doesn't
say what happens if that lookup comes back empty - a JWT can outlive the user row
it was issued for only in edge cases (e.g. deleted between token issuance and use),
since nothing in Module 2 checks DB existence per-request by design (that's the
whole point of stateless JWT claims). This is implemented as an `IllegalStateException`
(surfaces as a 500) rather than a bespoke 401/404 - deliberately not given its own
exception type, since it's an anomalous state Module 2's design doesn't otherwise
anticipate, not a normal failure path a client should be coded against. Worth
revisiting only if user deletion becomes a real feature.

### 2.3 `myLocks` drops a seat whose lock expires between the `MGET` and the `PTTL`, not just from the minimum

`plan/redis.md` section 6.3 says an expired-in-the-gap key "yields a negative TTL,
which is floored to 0 and simply drops out of the response." Read literally that's
ambiguous between two behaviors: (a) exclude that seat's id from `seatIds` entirely,
or (b) keep it in `seatIds` but let its negative TTL fall out of the *minimum*
calculation. Implemented (a): a seat whose key is gone by the time `PTTL` runs is no
longer actually locked, so reporting it in `seatIds` (even with an honest
`secondsRemaining` computed from the *other* seats) would tell the client they still
hold a seat they don't - the exact kind of lie `plan/redis.md`'s fail-closed
philosophy argues against elsewhere. If every held seat expires in that gap, the
response falls back to the same empty-hold shape as never having locked anything
(`seatIds: []`, `secondsRemaining: 0`).

### 2.4 A `DataAccessException` catch-all in `GlobalExceptionHandler`, beyond the exceptions this module throws deliberately

`plan/redis.md` section 9-A only specifies wrapping Redis failures in
`SeatLockUnavailableException` at the point each service method touches Redis - and
that's exactly what `SeatLockService`/`RedisSeatLockView` do. Added one more handler
on top: `@ExceptionHandler(DataAccessException.class) → 503`. This is defense in
depth, not a replacement for the deliberate wrapping - if some future code path
lets a raw Spring Data exception escape uncaught (a missed `try/catch` in a new
method, say), it still fails closed as a `503` instead of falling through to an
unhelpful raw `500`. Consistent with, not a substitute for, the explicit wrapping
everywhere else.

### 2.5 De-duplicating requested seat ids before building Redis keys

Neither `SeatLockRequest` nor `plan/redis.md` forbids a client sending
`{"seatIds": [101, 101, 102]}`. `SeatLockService.lock`/`release` de-duplicate via
`List.copyOf(new LinkedHashSet<>(request.getSeatIds()))` (preserving request order)
before building the key list - otherwise a duplicate id produces a duplicate Redis
key in `KEYS`, which `lock_seats.lua`/`unlock_seats.lua` handle harmlessly (Lua's
`GET`/`SET`/`DEL` are idempotent per key within one script run) but which would
inflate `@Size(max = 10)`'s effective limit if left unfiltered before the DB lookups.

## 3. Test coverage as shipped, vs. the full plan

`plan/redis.md` section 14's test table has ten rows. What's actually running:

- **`SeatLockServiceTest`** (8 tests, mocked `StringRedisTemplate`): the happy path
  with exact key/arg verification, the script-returns-conflict path, the
  wrong-show/already-booked pre-checks (and that Redis is never touched for either),
  a Redis-unreachable path, releasing a lock held by someone else, and both branches
  of `assertHoldsAll`.
- **`SeatServiceTest`** (4 tests, stubbed `SeatLockView`): the `toSeatDto` effective-
  status precedence (`BOOKED` beats a lock, a lock beats `AVAILABLE`, neither means
  `AVAILABLE`), plus that `getSeatLayout` passes the seam the seat ids it already
  loaded rather than just the show id - directly protecting the Principle #1
  amendment `claude.md` and this module both describe.

**Not shipped, and why:** the concurrency test (N threads racing one seat), the
multi-seat all-or-nothing integration test, the idempotent-re-lock-doesn't-refresh-
TTL test, and the TTL-expiry test all need a *real* Redis - `plan/redis.md`'s own
section 14 says as much, and Open Decision C recommends Testcontainers for exactly
this reason. This sandbox has neither Docker nor a working JDK 21 install (see
claude.md's "Local build environment note" and Known Gaps), so Testcontainers was
not added and these tests were not written speculatively against an API that can't
be exercised here. This is the same limitation Modules 2 and 3 flagged for their own
live-DB integration tests - not a new gap Module 4 introduces, but one it inherits
and doesn't get to close either. Add `spring-boot-testcontainers` +
`org.testcontainers:redis` and write these four scenarios before treating Module 4
as fully verified.

## 4. What Module 5 inherits

`SeatLockService.assertHoldsAll(showId, seatIds, userId)` and
`releaseAfterCommit(showId, seatIds, userId)` exist now, unused until Module 5 calls
them. The ordering they encode - re-check the seat rows and call `assertHoldsAll`
*inside* the booking's `@Transactional` method, then call `releaseAfterCommit`
*inside that same method* (it registers an `afterCommit` synchronization, so it's
safe to call before the transaction actually commits) - is not optional; see
`plan/redis.md` section 11.1 and claude.md's "Booking confirmation order is fixed"
for why releasing before commit reopens the exact race this whole module exists to
close.
