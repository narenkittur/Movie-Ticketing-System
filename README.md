# Movie Ticket Booking System

A backend-heavy ticketing platform: browse shows, lock a seat, pay, get a
confirmed booking. Concurrency safety is the point of the project — two users
must never end up with the same seat. See [`claude.md`](claude.md) for the
full architectural guide and module-by-module design/status; this file is the
practical "how do I run it" companion.

## ⚠️ Payment mode: mocked by default

**By default (`payment.gateway=mock`), no real payment processing happens.**
No money moves, no external account is contacted, no network call is made.
The mock gateway is a genuine test double of a real gateway's *contract*
(same async flow, same HMAC signature discipline, same webhook code path) —
it is not a shortcut that hides how payment actually works. It is labelled as
mocked in three places: `MockPaymentGateway`'s class javadoc, a `log.warn` at
startup, and here.

A real, free integration is also available — see [Real Razorpay](#6-real-razorpay-optional)
below. It costs nothing and needs no KYC in Razorpay's TEST mode; the only
difference between this and a live integration that moves real money is the
API key prefix (`rzp_test_…` vs `rzp_live_…`). See `plan/payment.md` section
1.1 for the full reasoning.

## Prerequisites

- JDK 21
- Docker (for local MySQL + Redis via `docker-compose.yml`)

## Running locally

1. `docker compose up -d` — starts MySQL and Redis (no persistence on Redis,
   intentional — see `plan/redis.md` section 9-F).
2. **On a database where `bookings` already exists from before Module 5**,
   run this migration first — `ddl-auto=update` will not do it for you:
   ```sql
   ALTER TABLE bookings MODIFY total_price DECIMAL(10,2) NOT NULL;
   ```
   (On a fresh database, skip this — the column is created correctly from
   the start.)
3. `./mvnw spring-boot:run` — defaults to `payment.gateway=mock`, no
   credentials needed.

## Manual end-to-end walkthrough (mock mode)

1. `POST /auth/register`, then `POST /auth/login` to get a JWT. As an admin
   user, create a movie, a show, and a seat layout (`POST /admin/movies`,
   `POST /admin/movies/{id}/shows`, `POST /admin/shows/{id}/seats`).
2. `POST /shows/{id}/seats/lock` with 1-10 seat ids — acquires a 300s Redis
   hold.
3. `POST /bookings` with the same `showId`/`seatIds` — `201`, status
   `PENDING`, a `checkoutUrl`.
4. `POST` that `checkoutUrl` (it's a `POST /mock-gateway/pay/{providerOrderId}`
   route) — the booking flips to `CONFIRMED`, the seats go `BOOKED`,
   `GET /shows/{id}/seats` reflects it, and the locks disappear from Redis.
5. Repeat, but wait out the full 300 seconds before "paying" — the booking
   goes to `REFUND_PENDING`, **not** `CONFIRMED`. This is the branch worth
   watching fail correctly: the money would be real, but the seats are no
   longer yours to give once the hold expires, so it is never confirmed.
   `REFUND_PENDING` is a terminal state today — no refund is actually
   auto-issued (see [Refunds](#refunds) below).
6. On the `CONFIRMED` booking from step 4: `GET /bookings/{id}/qr` (owner's
   JWT) returns a PNG — scan it with any phone QR app and it decodes to the
   booking's `bookingReference`. `GET /bookings/{id}/ticket` returns the
   printable detail (movie, time, screen, seats).
7. **Gate scan** — as an **admin**, `POST /admin/tickets/validate` with
   `{"code": "<bookingReference>"}` → `200`, `result: "VALID"`, `checkedInAt`
   set. Repeat the exact same call → `200`, `result: "ALREADY_USED"`, the
   **same** `checkedInAt` as the first response. As a non-admin, the same
   call → `403`.

## QR tickets

`GET /bookings/{id}/qr` and `GET /bookings/{id}/ticket` are owner-only and
require a `CONFIRMED` booking (`409` otherwise). The QR encodes nothing but
the bare `bookingReference` — no signature, no URL — because it's looked up in
our own database rather than trusted on its own; see `plan/qrtickets.md`
section 4.1 for the full reasoning.

**What this does *not* defend against:** a user screenshotting their own
ticket and forwarding it to someone else. Single-use redemption
(`checkedInAt`) stops the *second* scan of a given ticket, but not necessarily
the *right* person — whoever scans first wins. Binding a ticket to an identity
document is a product decision outside this codebase's scope.

## Real Razorpay (optional)

1. Create a free Razorpay TEST-mode dashboard account (no KYC required for
   test mode).
2. Set `PAYMENT_GATEWAY=razorpay` plus `RAZORPAY_KEY_ID`,
   `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`.
3. Expose your local app with a tunnel — `cloudflared tunnel --url
   http://localhost:8080` is recommended over ngrok (free, unlimited
   bandwidth, no account; ngrok's free tier was cut to 2-hour sessions and
   1 GB/month in early 2026).
4. Register `https://<random>.trycloudflare.com/payments/webhook` in the
   Razorpay dashboard against the `payment.captured` event.
5. Repeat the walkthrough above, but pay the real Payment Link with a test
   card or test UPI id in any browser instead of hitting `/mock-gateway/pay`.

**Known limitation:** Razorpay Payment Links redirect the browser back via
`GET` on `callback_url`. `POST /bookings/{id}/confirm` (this API's
browser-callback endpoint) only accepts `POST` — bridging that GET redirect
into a POST call is a frontend concern that belongs to Module 8, which
doesn't exist yet. Until then, the **webhook** path
(`POST /payments/webhook`) is what actually and reliably confirms a real
Razorpay payment; the browser-callback endpoint exists and is fully
implemented/tested, but nothing in this backend-only codebase currently
drives it end-to-end against live Razorpay.

## Refunds

`PaymentGateway.refund()` is implemented for both gateways but is **never
called automatically**. A booking that lands in `REFUND_PENDING` (paid too
late, or paid after the seat was taken by someone else) stays there, loudly
logged (`log.error`), until an admin-triggered refund endpoint is added in
Module 9. This is a deliberate choice, not an oversight — see `plan/payment.md`
Open Decision A.

## Known gaps

See `claude.md`'s "Known Gaps" section for the full, current list (Redis/
MySQL/Testcontainers/Razorpay environment limitations, the `total_price`
migration note, etc.) — kept there as the single source of truth so this file
doesn't drift out of sync with it.
