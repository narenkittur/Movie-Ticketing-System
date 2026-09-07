# Module 7 — QR Tickets: Implementation Notes

Status: **implemented.** This document explains where the shipped code follows
`plan/qrtickets.md` exactly, and the handful of places an implementation-time
judgment call had to be made that the design doc left open. Line-by-line "what
this code does" is covered by comments/javadoc in the source files; this is the
"why."

## 1. Files added/changed

| File | Purpose |
|---|---|
| `model/TicketValidationResult.java` | New - `VALID`/`ALREADY_USED`/`NOT_CONFIRMED`/`OUTSIDE_WINDOW`/`NOT_FOUND` |
| `model/Booking.java` | Edited - `+ checkedInAt` (nullable `LocalDateTime`), `+ checkedInBy` (nullable `Long`, Open Decision A resolved yes) |
| `repository/BookingRepository.java` | Edited - `+ findWithLockByBookingReference` (`PESSIMISTIC_WRITE`, mirrors `PaymentRepository.findWithLockByProviderOrderId`), `+ findByIdAndUserIdWithDetail` (fetch-joined) |
| `service/QrCodeGenerator.java` | New - the only class importing `com.google.zxing`; `toPng(text, sizePx)` |
| `service/TicketService.java` | New - `qrPng`, `getTicket`, `validate` |
| `controller/TicketController.java` | New - `POST /admin/tickets/validate` |
| `controller/BookingController.java` | Edited - `+ GET /bookings/{id}/qr`, `+ GET /bookings/{id}/ticket`; class gains `@Validated` (see section 2.1) |
| `dto/TicketResponse.java`, `TicketValidationRequest.java`, `TicketValidationResponse.java` | New |
| `exception/GlobalExceptionHandler.java` | Edited - one new handler, `ConstraintViolationException` → 400 (section 2.1) |
| `pom.xml` | Edited - `zxing.version=3.5.3` (verified against Maven Central at implementation time, matching the plan's memory of the current release), `+` `com.google.zxing:core`, `+` `com.google.zxing:javase` |
| `application.properties` | Edited - `+ ticket.checkin.opens-minutes-before` |
| `src/test/.../QrCodeGeneratorTest.java`, `TicketServiceTest.java` | New - 13 tests total, see section 3 |
| `claude.md`, `README.md` | Edited - see section 4 |

The design (payload, endpoints, redemption locking, check-in window, result
semantics, security posture) is unchanged from `plan/qrtickets.md`. No class in
the plan's inventory (section 6) was dropped, and nothing needed adding beyond
one exception handler - see section 2.1.

## 2. Deviations / additions beyond `plan/qrtickets.md`

### 2.1 A `ConstraintViolationException` handler, not called out in section 6's table

`GET /bookings/{id}/qr`'s `?size=` is a bare `@RequestParam`, not a field on a
`@RequestBody` DTO - `@Min(128)`/`@Max(1024)` on a loose method parameter only
gets evaluated at all if the controller class carries `@Validated`, and when it
fails, Spring throws `ConstraintViolationException`, not
`MethodArgumentNotValidException` (the type `GlobalExceptionHandler` already
handled, but only for `@Valid @RequestBody` failures). Without a handler for it,
an out-of-range `size` would fall through to Spring's default 500/whitelabel
response instead of a clean 400 - not what section 4.3's Bean Validation intent
implies. Added `@Validated` to `BookingController` and one more
`@ExceptionHandler` in `GlobalExceptionHandler`, same shape as the existing
`MethodArgumentNotValidException` handler. Not a design change, just a
consequence of section 4.3 that the plan's prose didn't spell out down to the
exception type.

### 2.2 `TicketService.effectiveStatus` is a deliberate duplicate of `BookingService`'s

Section 4.3/4.4 requires "status must come from `BookingService`'s
read-time-derived `effectiveStatus` semantics, not the raw column." Rather than
exposing that private method or injecting `BookingService` into `TicketService`
(neither of which the class inventory's Modified-files table asked for -
`BookingService.java` isn't listed there at all), `TicketService` carries its
own 2-line copy of the same PENDING-past-`expiresAt`-reads-as-EXPIRED check,
cross-referenced in both classes' javadoc. Kept intentionally small and
duplicated rather than shared, per the same reasoning the codebase already
applies to `SeatService`/`BookingService` not sharing a status-derivation
utility: it's a two-line rule, not an algorithm worth a cross-service
dependency, and the design doc's own class inventory didn't call for touching
`BookingService.java`.

Note this duplication is deliberately **not** used inside `validate()` - that
method checks `booking.getStatus() != CONFIRMED` against the raw column, which
is correct there: PENDING (stale or not) and every other non-CONFIRMED value
all collapse to the same `NOT_CONFIRMED` result at the gate, so the
EXPIRED-vs-PENDING distinction that matters to `qrPng`/`getTicket` is irrelevant
to a turnstile.

### 2.3 `logic/websockets.md` already existed

`plan/qrtickets.md` section 9 (written before this module started) asserts
`logic/websockets.md` "does not exist" and treats writing it as this module's
documentation debt. That was already stale by the time implementation started -
the file exists, is a complete as-built writeup, and `claude.md`'s Module
Status section already carries a full "Module 6 — WebSockets: IMPLEMENTED"
entry with a `logic/websockets.md` reference. No action was needed there; the
one genuinely stale line was `claude.md`'s trailing "Modules 7–9: PENDING",
fixed in this module's `claude.md` update (section 4 below).

## 3. Test results

13 new tests, all green: `QrCodeGeneratorTest` (2 - the round-trip decode plus a
requested-size assertion) and `TicketServiceTest` (11 - covers plan section 8's
scenarios 2-8 exactly: first scan VALID + timestamp stamped, second scan
ALREADY_USED with the original timestamp preserved, PENDING → NOT_CONFIRMED,
before-window and after-window → OUTSIDE_WINDOW, unknown reference → NOT_FOUND
with nothing echoed, `qrPng` on a non-CONFIRMED booking → `BookingStateException`,
`qrPng` on someone else's booking → `BookingNotFoundException` via the
owner-scoped query, plus two extra `getTicket` cases the plan's list didn't
enumerate but the same pattern called for).

Full project suite: **86 tests, 85 passing** - the one failure is the
pre-existing `MovieticketApplicationTests.contextLoads`, which needs a live
MySQL this sandbox doesn't have (documented in `claude.md`'s Known Gaps since
Module 2; unrelated to this module).

**Deferred, per Open Decision C:** plan section 8 test 9 (the two-turnstile
concurrent-scan race) needs a real MySQL that actually honours
`SELECT … FOR UPDATE` - worthless against a mocked repository, which will
happily hand `checkedInAt == null` to every concurrent caller. Fourth
consecutive module to want Testcontainers (Modules 4, 5, 6, now 7); still not
added, to be fixed once on real hardware for all four at once.

## 4. Documentation debt cleared

- `claude.md`'s Module Status: "Modules 7–9: PENDING" replaced with a Module 7
  entry; "Modules 8–9: PENDING" is what remains true.
- `claude.md` Tech Stack: the "ZXing is not yet in `pom.xml`" line (61-62)
  updated to reflect it now is.
- `claude.md` Data Model: `Booking.checkedInAt`/`checkedInBy` documented, with
  the explicit note that no manual migration is needed (nullable add, unlike
  `total_price`'s type change).
- `claude.md` Project Constraints: `ticket.checkin.opens-minutes-before`
  recorded alongside the fixed 300s lock TTL.
- `claude.md` Known Gaps: `ROLE_STAFF` deferral (section 5.5) and test 9
  (section 8/10-C) added.
- `README.md`: gate-scan walkthrough step (scan → `VALID`, scan again →
  `ALREADY_USED`) and section 7.3's honest note about forwarded screenshots.
