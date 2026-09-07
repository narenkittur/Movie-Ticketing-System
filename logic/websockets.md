# Module 6 — WebSockets: Implementation Notes

Status: **implemented.** This document explains where the shipped code follows
`plan/websockets.md` exactly, and the handful of places an implementation-time
judgment call had to be made (or the plan's own estimate turned out to need more
machinery than it described) that the design doc left open or underspecified.
Line-by-line "what this code does" is covered by comments/javadoc in the source
files; this is the "why."

## 1. Files added/changed

| File | Purpose |
|---|---|
| `config/WebSocketConfig.java` | New - `@EnableWebSocketMessageBroker`, `/ws` endpoint, simple broker, the auth interceptor, the session-tracking decorator (section 3) |
| `config/WebSocketSchedulerConfig.java` | New, **not in the plan's class inventory** - see decision 2.3 below for why the `TaskScheduler` bean needed its own tiny config class |
| `dto/SeatStatusUpdate.java` | New - the broadcast delta (section 3.2) |
| `security/StompAuthChannelInterceptor.java` | New - JWT on CONNECT, `/user/**` guard, schedules the session cap (sections 8.2/8.3, Open Decision A) |
| `security/WebSocketSessionRegistry.java` | New, **not in the plan's class inventory** - see decision 2.3 |
| `service/SeatBroadcaster.java` | New - `@EventListener(SeatLockChangedEvent)` → `/topic` (section 3.2) |
| `service/BookingBroadcaster.java` | New - `@TransactionalEventListener(AFTER_COMMIT)` → `/user/queue` (section 6) |
| `service/SeatLockExpiryListener.java` | New - Redis `__keyevent@0__:expired` subscriber (section 5.1) |
| `service/BookingStatusChangedEvent.java` | New - record (section 6) |
| `service/SeatLockChangedEvent.java` | Edited - `Type.BOOKED` added (section 4.2), **plus an `expiresAt` field the plan didn't call for** - see decision 2.1 |
| `service/SeatLockService.java` | Edited - `releaseAfterCommit` gains a required `resultingStatus` param and publishes (section 4.2/4.3); `lock`/`release` updated for the new event field |
| `service/SeatLockKeys.java` | Edited - `showIdFromKey` + `isSeatLockKey` added (section 5.1) |
| `service/BookingService.java` | Edited - both `releaseAfterCommit` call sites pass a status; `confirmPaid` publishes `BookingStatusChangedEvent` on all three terminal branches (section 4.2, section 6) |
| `security/JwtService.java` | Edited - `extractExpiration` added (Open Decision A) |
| `config/RedisConfig.java` | Edited - `RedisMessageListenerContainer` bean, gated by the same property as the listener (section 5.1) |
| `security/SecurityConfig.java` | Edited - `/ws/**` `permitAll`, ordered ahead of `anyRequest().authenticated()` (section 8.1) |
| `docker-compose.yml` | Edited - Redis gains `--notify-keyspace-events Kx` |
| `pom.xml` | Edited - `spring-boot-starter-websocket` |
| `application.properties` | Edited - `websocket.allowed-origins`, `websocket.expiry-notifications.enabled` |
| `service/SeatLockServiceTest.java` | Edited - `releaseAfterCommit` call sites updated; three new tests for the BOOKED/RELEASED publish rule (section 10 tests 3-4) |
| `service/BookingServiceTest.java` | Edited - constructor + `releaseAfterCommit` verifications updated; `BookingStatusChangedEvent` publish assertions added to the three `confirmPaid` branches |
| `service/SeatLockKeysTest.java`, `SeatBroadcasterTest.java`, `BookingBroadcasterTest.java`, `SeatLockExpiryListenerTest.java`, `security/StompAuthChannelInterceptorTest.java` | New - unit tests, see section 3 |

The design (transport choice, destinations, payload shape, security model, failure
modes) is unchanged from `plan/websockets.md` - that document is still the design
of record and isn't repeated here.

## 2. Decisions made during implementation

### 2.1 `SeatLockChangedEvent` grew a fourth field the plan didn't ask for: `expiresAt`

Section 3.2's payload table requires `SeatStatusUpdate.expiresAt` to be non-null
for a `LOCKED` delta - it's the client's countdown target (section 5.2). But
section 4.2 only asked for `Type.BOOKED` to be added to the event record; nothing
in the plan says where a `LOCKED` broadcast gets its expiry from.

The only place that value exists at all is `SeatLockService.lock()`, which already
computes `secondsRemaining` from `lock_seats.lua`'s own return value. Two options:
have `SeatBroadcaster` re-derive it with a second Redis `PTTL` call per LOCKED
event, or have the one call site that already knows the answer carry it on the
event. Chose the latter - `SeatLockChangedEvent` gained a fourth component,
`LocalDateTime expiresAt`, non-null only when `type() == LOCKED`. Every other
publisher (four of them now) passes `null`. This is the same shape of trade the
plan itself made in section 4.2 (amending a record `plan/redis.md` called fixed) -
recorded here rather than silently changed.

### 2.2 `releaseAfterCommit`'s publish-then-swallow ordering, exactly as specified

Section 4.3's table (publish BOOKED unconditionally, RELEASED only if the unlock
succeeded) is implemented literally: the `afterCommit` callback tracks
`unlockSucceeded` as a local `boolean`, and the publish decision reads it after
the Redis call's try/catch has already run and been swallowed. A second,
independent try/catch wraps the publish call itself, so a broadcast failure can
never re-surface as a Redis-unlock-looking failure in the logs, and neither can
escape the callback (`plan/websockets.md` section 9-D). Tests 3 and 4 from section
10 - the ones the plan calls out as the pair that actually catches a regression
here - are in `SeatLockServiceTest` as
`releaseAfterCommit_booked_publishesBooked_evenWhenUnlockScriptThrows` and
`releaseAfterCommit_released_publishesNothing_whenUnlockScriptThrows`, using
`TransactionSynchronizationManager.initSynchronization()` +
`TransactionSynchronizationUtils.triggerAfterCommit()` to simulate the commit
point without a real transaction (same reason `BookingServiceTest` self-references
its own `self` field - no Spring AOP proxy exists in a mock-only unit test).

### 2.3 Open Decision A needed a session registry the plan's "roughly fifteen lines" didn't anticipate

The plan recommended capping a session at its token's expiry and estimated "one
new public accessor on `JwtService`... and roughly fifteen lines." The accessor
(`JwtService.extractExpiration`) was indeed one method. Scheduling *when* to close
a session was the easy half; actually closing one specific STOMP session from
application code turned out to have no built-in Spring API at all -
`SimpMessagingTemplate` only ever sends messages, and the live `WebSocketSession`
object is otherwise buried inside the internal `SubProtocolWebSocketHandler`.

The fix that exists in practice (and is what this kind of "kick a session" feature
uses in real Spring WebSocket deployments): decorate the handler registered for
`/ws` via `WebSocketMessageBrokerConfigurer.configureWebSocketTransport`, purely to
observe `afterConnectionEstablished`/`afterConnectionClosed` and record the session
by id. `WebSocketSessionRegistry` is that lookup table (`register`/`unregister`/
`closeSession`); `WebSocketConfig`'s `configureWebSocketTransport` override is the
decorator that feeds it. `StompAuthChannelInterceptor.scheduleSessionCap` then
does exactly what the plan described: reads `exp`, schedules a `TaskScheduler` task
at that instant that calls `sessionRegistry.closeSession(sessionId)`.

This pulled in one more file than the plan's inventory (section 7) listed
(`WebSocketSessionRegistry`), plus a second config class (`WebSocketSchedulerConfig`,
below) - closer to sixty lines across three files than fifteen in one, but the
same design the plan asked for, not a smaller substitute for it.

### 2.4 The `TaskScheduler` bean needed its own config class to avoid a cycle

The natural place for the `TaskScheduler` bean `StompAuthChannelInterceptor` needs
is `WebSocketConfig`, right next to where it's also handed to the simple broker.
That creates a real circular dependency: `WebSocketConfig`'s constructor needs
`StompAuthChannelInterceptor` (to register it as an inbound-channel interceptor),
`StompAuthChannelInterceptor`'s constructor needs a `TaskScheduler`, and a `@Bean`
method producing that `TaskScheduler` on `WebSocketConfig` itself can't run until
`WebSocketConfig` is already constructed. Spring can't resolve that with
constructor injection on both sides.

Fix: `WebSocketSchedulerConfig`, a second `@Configuration` class with zero
dependencies of its own, is the sole source of the `TaskScheduler` bean.
`WebSocketConfig` and `StompAuthChannelInterceptor` both just consume it by type.
No `@Lazy` proxy games - the dependency graph is simply acyclic once the bean's
origin moves out of the class that also consumes its consumer.

### 2.5 `BookingBroadcaster` rebuilds the response via `BookingService.getBooking`, not a payload on the event

`BookingStatusChangedEvent` carries only `(bookingId, username, status)`, not a
`BookingResponse`. `BookingBroadcaster` calls `bookingService.getBooking(bookingId,
username)` inside its `@TransactionalEventListener(AFTER_COMMIT)` method to build
the payload. This is a normal proxied call to a *different* bean (not the
same-class self-invocation `BookingService`'s own javadoc warns about), it opens
its own fresh read-only transaction, and by the time this listener runs
(after-commit) the row is guaranteed visible. One source of truth for the response
shape (`toResponse`/`effectiveStatus`) instead of a second mapping living in the
broadcaster, at the cost of one extra `SELECT` per booking-status push - judged
worth it since this is a low-frequency event (one per booking, not one per seat
click) and consistent with plan section 6's "no new DTO" instruction.

### 2.6 `logic/payment.md` already existed

Section 12 of the plan flagged `logic/payment.md` as a live cross-reference (from
`BookingService.effectiveStatus`'s javadoc) to a file that didn't exist yet, and
asked Module 6 to fix it. It was already on disk by the time this module started
(154 lines, `Status: implemented`) - written sometime after the plan was drafted
but not reflected back into it. No action needed here beyond noting the doc debt
item was already closed.

## 3. Test coverage

Unit tests only (Mockito, no network) - same sandbox limitation as every prior
module (no JDK 21, no Docker/MySQL/Redis here). 15 new/changed test methods across
six files, all passing alongside the pre-existing suite (72 total project tests,
71 passing - the one failure, `JwtServiceTest.isAccessTokenValid_returnsFalse_
forTamperedSignature`, is pre-existing flakiness in a Module 2 test untouched by
this module: it passes in isolation and only ever fails when the tampered
signature's flipped last character happens to land on a base64 padding bit that
doesn't change the decoded byte - unrelated to anything Module 6 touched).

Covers section 10's items 1-8 (the mock-only ones):
1-2. `SeatBroadcasterTest` - all three `Type` → status mappings, plus a broadcast
     failure never escaping the listener.
3-4. `SeatLockServiceTest`'s three new `releaseAfterCommit_*` tests - the pair the
     plan calls the ones to insist on (section 2.2 above).
5-6. `StompAuthChannelInterceptorTest` - valid/invalid/absent/malformed CONNECT
     tokens, and the `/user/**` SUBSCRIBE guard both ways.
7. `SeatLockKeysTest` (`showIdFromKey`/`isSeatLockKey`) + `SeatLockExpiryListenerTest`
   (ignores a non-`seat_lock:` expired key, publishes RELEASED for one that is).
8. `BookingBroadcasterTest` - sends to the owner's username via
   `convertAndSendToUser`, never to a topic; lookup and push failures both
   swallowed.

Items 9-10 (the `WebSocketStompClient` integration tests against a real
`@SpringBootTest`) are written down here as still-needed, not written as code -
see Known Gaps below and Open Decision D, same Testcontainers gap as Modules 4-5.

## 4. Confirmed matching the plan, no deviation

- Transport: STOMP + simple broker at `/ws`, no SockJS, no `@MessageMapping`
  anywhere (section 3).
- `SeatStatusUpdate`'s three status literals and BOOKED > LOCKED > AVAILABLE
  precedence match `SeatDto`/`SeatService.toSeatDto` exactly.
- `/ws/**` `permitAll`, declared ahead of `anyRequest().authenticated()`
  (section 8.1) - verified by reading `SecurityConfig`'s matcher list top to
  bottom, per the project's own standing instruction for this trap.
- No `@EnableWebSocketSecurity` (section 8.4).
- Anonymous CONNECT allowed; a *presented* invalid token is refused, never
  silently downgraded (section 8.3).
- `BOOKED` published unconditionally from `releaseAfterCommit`, `RELEASED` only on
  a successful unlock (section 4.3) - see decision 2.2.
- `BookingStatusChangedEvent` published from all three terminal branches of
  `confirmPaid` (CONFIRMED + both REFUND_PENDING outcomes), never from the
  idempotent early-return or from `cancelBooking` (section 6) - covered by
  `BookingServiceTest`'s per-branch `eventPublisher` assertions.
- Open Decisions B/C/E all taken exactly as recommended: `SeatLockChangedEvent`
  stayed in `service/` (B); no locking-user id on `LOCKED` deltas (C);
  `websocket.expiry-notifications.enabled` defaults `true` (E).

## 5. Known Gaps carried into `claude.md`

- **Single-instance broker (section 9-A).** The simple broker is in-process; a
  lock acquired on one instance never reaches a browser connected to another. No
  code here changes that - it's a deployment-topology constraint, not a bug, with
  two named fixes (Redis pub/sub relay, or a real STOMP broker) neither of which
  is built.
- **Integration tests 9-10 (section 10)** need `@SpringBootTest(webEnvironment =
  RANDOM_PORT)` + `WebSocketStompClient` against real MySQL/Redis - not run in this
  sandbox, same gap as every prior module (Open Decision D, deferred again).
- **Fire-and-forget keyspace notifications (section 5.1's caveat).** `
  SeatLockExpiryListener` is best-effort; the client countdown (section 5.2)
  remains the only thing that makes an expiry *correct* rather than merely
  *prompt*. Nothing to fix here - this is the documented design, not a shortfall.
