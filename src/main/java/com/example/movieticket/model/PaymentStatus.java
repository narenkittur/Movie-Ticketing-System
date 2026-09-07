package com.example.movieticket.model;

/**
 * {@link Payment}'s own lifecycle, parallel to but distinct from
 * {@link BookingStatus} (plan/payment.md section 7.4). {@code CREATED} is written
 * before the gateway is even called - the {@code Payment} row exists with a null
 * {@code providerOrderId} briefly during {@code POST /bookings} step A (section 5).
 * {@code CAPTURED} is the only status {@code BookingService.confirmPaid} ever
 * writes on the happy path.
 */
public enum PaymentStatus {
    CREATED,
    CAPTURED,
    FAILED,
    REFUND_PENDING,
    REFUNDED
}
