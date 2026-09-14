package com.example.movieticket.model;

/**
 * {@link Payment}'s own lifecycle, parallel to but distinct from
 * {@link BookingStatus} (plan/payment.md section 7.4). {@code CREATED} is written
 * before the gateway is even called - the {@code Payment} row exists with a null
 * {@code providerOrderId} briefly during {@code POST /bookings} step A (section 5).
 * {@code CAPTURED} is the only status {@code BookingService.confirmPaid} ever
 * writes on the happy path.
 *
 * <p>{@code REFUNDING} (Module 9, {@code BookingService.refund}) is a short-lived
 * in-flight marker, not a durable state a caller ever sees for long: it exists
 * purely so two concurrent admin refund attempts on the same booking can't both
 * pass the row-locked guard check. The first flips REFUND_PENDING to REFUNDING
 * and commits (releasing the lock) before calling the gateway over the network;
 * a second attempt's guard then sees REFUNDING, not REFUND_PENDING, and is
 * rejected. If the gateway call itself fails, a compensating step reverts this
 * back to REFUND_PENDING so a retry is possible - a row must never be left
 * stuck at REFUNDING with no way back.
 */
public enum PaymentStatus {
    CREATED,
    CAPTURED,
    FAILED,
    REFUND_PENDING,
    REFUNDING,
    REFUNDED
}
