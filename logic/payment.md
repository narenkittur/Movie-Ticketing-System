# Module 5 — Payment + Transactional Booking: Implementation Notes

Status: **implemented.** This document explains where the shipped code follows
`plan/payment.md` exactly, and the handful of places an implementation-time
judgment call had to be made (or a genuine inconsistency in the design doc had to
be resolved) that the plan left open or got slightly wrong. Line-by-line "what
this code does" is covered by comments in the source files; this is the "why."

## 1. Files added/changed

| File | Purpose |
|---|---|
| `model/BookingStatus.java`, `PaymentStatus.java` | New - the project's first entity enums |
| `model/Payment.java` | New - one row per booking's payment attempt |
| `model/Booking.java` | Edited - `totalPrice` `double`→`BigDecimal`, added `status`/`expiresAt`/`bookingReference`/`payment` |
| `model/BookingSeat.java` | Edited - `UNIQUE(booking_id, seat_id)` |
| `repository/PaymentRepository.java` | New - `findByProviderOrderId`, `findWithLockByProviderOrderId` (`PESSIMISTIC_WRITE`) |
| `repository/SeatRepository.java` | Edited - added `findByIdInAndShowIdForUpdate` (`PESSIMISTIC_WRITE`) |
| `repository/BookingRepository.java` | Edited - added `findByIdAndUserId`, `findByBookingReference`, `findAllForUserWithSeats`, `findByUserIdAndShowIdAndStatusWithSeats` |
| `service/PaymentGateway.java` | New - the seam interface |
| `service/PaymentIntent.java`, `PaymentIntentCommand.java`, `WebhookEvent.java` | New - records |
| `service/PaymentSignatures.java` | New - shared HMAC sign/verify helper |
| `service/MockPaymentGateway.java` | New - default bean, offline |
| `service/RazorpayPaymentGateway.java` | New - real gateway, no SDK |
| `service/BookingService.java` | New - the whole module's orchestration |
| `controller/BookingController.java`, `PaymentWebhookController.java`, `MockGatewayController.java` | New |
| `dto/CreateBookingRequest.java`, `BookingResponse.java`, `BookingSeatDto.java`, `ConfirmPaymentRequest.java` | New |
| `exception/BookingNotFoundException.java`, `BookingStateException.java`, `PaymentVerificationException.java`, `PaymentGatewayException.java` | New - 404/409/400/502 respectively |
| `exception/GlobalExceptionHandler.java` | Edited - four new handlers |
| `security/SecurityConfig.java` | Edited - `permitAll` for `/payments/webhook` and `/mock-gateway/**`, ahead of `anyRequest().authenticated()` |
| `config/PaymentProperties.java`, `PaymentConfig.java` | New - `@ConfigurationProperties` + a timeout-bounded `RestClient` bean |
| `application.properties` | Edited - `payment.*` block |
| `claude.md` | Edited - principle #2 amended, `booking_seats` backstop claim corrected, enum inconsistency recorded, `totalPrice` migration added to Known Gaps |
| `README.md` | New - runbook + mock disclaimer (didn't exist before this module) |
| `service/BookingServiceTest.java`, `PaymentSignaturesTest.java`, `MockPaymentGatewayTest.java` | New - unit tests, see section 4 |

The design (endpoints, state machine, gateway seam shape, schema, traps) is
unchanged from `plan/payment.md` except where noted below - that document is
still the design of record.

## 2. Deviations from `plan/payment.md`

### 2.1 `Payment.checkoutUrl` added; `providerOrderId` made nullable

Section 7.2's schema table lists `provider_order_id` as "unique, not null" and
doesn't mention a `checkoutUrl` column at all. Both are inconsistent with section
5's own step-by-step algorithm: step A explicitly persists the `Payment` row with
`providerOrderId = null` (the gateway hasn't been called yet), and step C says to
"persist `providerOrderId` + `checkoutUrl` onto the payment row" - which requires
somewhere to put the latter.

Resolved by making `provider_order_id` nullable at the DB level (MySQL's unique
index already treats multiple `NULL`s as distinct, so this doesn't weaken the
idempotency guarantee once a real order id is set) and adding a `checkout_url`
column. Without persisting it, neither `GET /bookings/{id}` on a still-`PENDING`
booking nor the Open-Decision-B "reuse an existing `PENDING` booking" path could
hand back a working checkout link on a second read.

### 2.2 `EXPIRED` computed on every read, not opportunistically persisted

Section 3.3 describes `EXPIRED` as written "only when something actually touches
the booking - a read, a confirm attempt, a cancel - and even then only as a side
effect." The shipped code computes the *display* status (`effectiveStatus`) fresh
on every read instead of writing `EXPIRED` back to the row the first time a stale
`PENDING` booking is touched.

This is functionally identical to every caller - they always see the correct
derived status either way - and avoids adding a write path (with its own
transactional/locking questions) to what would otherwise be a plain read-only
query. `confirmPaid`'s own paid-too-late branch (section 4.3) still writes
`REFUND_PENDING` explicitly, since that *is* a real state transition with money
involved, not a display convenience.

### 2.3 The `createBooking` self-invocation fix

Section 5 asks for three steps in one method, with different transactional
requirements per step (DB transaction / no transaction, deliberately / DB
transaction again) - all living in `BookingService`, per `claude.md` principle #4
("payment orchestration lives in the service layer, not controllers").

The plan doesn't address a real Spring pitfall this collides with: `@Transactional`
is proxy-based AOP, and a same-class ("self-invocation") method call bypasses the
proxy entirely, silently running with **no transaction at all** rather than the one
the annotation promises. Naively writing `createBooking` to call
`this.createPendingBooking(...)` and `this.attachIntent(...)` would compile and
even *appear* to work in the mock-mode manual walkthrough, while quietly running
step A's `SELECT ... FOR UPDATE` seat re-check with no actual row lock held -
exactly the kind of bug that only shows up under real concurrent load, which this
sandbox can't produce anyway (see Known Gaps).

Fixed with the standard Spring workaround: `BookingService` takes a `@Lazy`
self-reference (`self`) via constructor injection - a proxy of itself - and routes
`createPendingBooking`/`attachIntent` through `self.` instead of `this.`, so each
call re-enters through the real AOP proxy and gets its own genuine transaction
boundary. See `BookingService.createBooking`'s javadoc for the in-code explanation.

The browser-callback confirm path avoids needing this trick at all:
`BookingController` calls `verifyCallbackAndResolveOrderId` and `confirmPaid` as
two separate, controller-issued calls rather than nesting one inside the other in
`BookingService` - both already go through the real proxy since the controller is
an external caller, and the two operations are independently meaningful anyway
(the webhook path calls `confirmPaid` directly, with no ownership check at all,
since it authenticates via HMAC signature, not a caller's JWT).

## 3. Open Decisions (`plan/payment.md` section 13) - resolutions

Both decisions with real code-shape consequences were confirmed with the user
before implementation (2026-09-04); the other three matched the plan's own
recommendation and weren't re-litigated.

- **A (auto-refund on `REFUND_PENDING`?): implement `refund()`, never auto-invoke
  it.** `REFUND_PENDING` + `log.error` is the terminal state; an admin-triggered
  refund endpoint is Module 9's problem. See `README.md`'s Refunds section.
- **B (dedupe `POST /bookings`?): yes.** `BookingService.createPendingBooking`
  checks `findByUserIdAndShowIdAndStatusWithSeats` for a non-expired `PENDING`
  booking with the exact same seat set before creating a new one; a match short-
  circuits the whole method (no gateway call, existing `checkoutUrl` returned).
- **C (retry the gateway on 502?): no** - implemented as designed, one attempt,
  fail fast.
- **D (Testcontainers?): still deferred** - flagged again in `claude.md` Known
  Gaps, now alongside Module 4's identical gap.
- **E (`SeatService`/seat DTOs change?): no** - confirmed; neither was touched.

## 4. Trap checklist (`plan/payment.md` section 8)

Each one verified in the shipped code, not just assumed from having written it:

| # | Trap | Where verified |
|---|---|---|
| 8.1 | Webhook body is `@RequestBody String`, never a parsed DTO | `PaymentWebhookController.handleWebhook` |
| 8.2 | `POST /payments/webhook` `permitAll`, declared before `anyRequest().authenticated()` | `SecurityConfig` - matcher order read directly, not inferred |
| 8.3 | Constant-time signature comparison | `PaymentSignatures.verify` uses `MessageDigest.isEqual`, shared by both gateways |
| 8.4 | Callback path trusts the caller's *own stored* `providerOrderId`, never one the request echoes | `BookingService.verifyCallbackAndResolveOrderId` resolves via `findByIdAndUserId` + the booking's own `Payment` row |
| 8.5 | `GET /bookings/{id}` is 404, not 403, for someone else's booking | `BookingRepository.findByIdAndUserId` - ownership is a query predicate |
| 8.6 | No inline default for the three Razorpay secrets | `application.properties` - `${RAZORPAY_KEY_ID:}` etc., all empty-default |
| 8.7 | `releaseAfterCommit` called inside an active transaction | `confirmPaid`/`cancelBooking` both `@Transactional`, call registered before the method returns |

## 5. Testing

Unit tests (`BookingServiceTest`, `PaymentSignaturesTest`, `MockPaymentGatewayTest`)
cover the section 10.1 scenario list that doesn't require a real MySQL - including
the two branches `plan/payment.md` section 14 point 3 calls out as easiest to skip:
paid-too-late and paid-but-seat-gone, both driving a booking to `REFUND_PENDING`.
21 new tests, all 49 project tests green in this sandbox (`-Dmaven.compiler.
release=17` offline compile+test, same JDK workaround as every prior module).

`MovieticketApplicationTests.contextLoads` still fails here - confirmed via the
stack trace to be purely "no live MySQL to connect to" (Hibernate can't determine
a dialect without a JDBC connection), the same pre-existing environment gap every
module has had, not a regression from this one.

**Not done, same as Module 4:** Testcontainers-backed integration tests for the
double-confirmation idempotency race (Open Decision D) and a real end-to-end run
against live MySQL/Redis/Razorpay - this sandbox has none of the three.
