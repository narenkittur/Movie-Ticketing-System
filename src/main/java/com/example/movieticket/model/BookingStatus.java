package com.example.movieticket.model;

/**
 * Module 5's booking lifecycle (plan/payment.md section 3). {@code PENDING} is
 * written the moment a payment intent is created and holds nothing durable - the
 * Redis lock already taken in Module 4 is what protects the seats until
 * {@code CONFIRMED} writes them as {@code BOOKED} (see Booking's own javadoc for
 * why seats never become {@code BOOKED} at {@code PENDING} time).
 * {@code EXPIRED} is never written by a background job - it is derived at read
 * time from {@code status == PENDING && now > expiresAt}, the same lazy-derivation
 * pattern Module 4 already used for Redis TTL and {@code SeatService.toSeatDto}'s
 * effective status (plan/payment.md section 3.3).
 *
 * <p>This is the project's first entity enum - {@code Seat.status} and
 * {@code User.role} stay plain {@code String}s (see claude.md's tracked
 * inconsistency note, plan/payment.md section 7.4). Diverging is deliberate: these
 * are brand-new fields with no legacy rows and no string literals scattered across
 * Modules 3/4 to hunt down.
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    EXPIRED,
    REFUND_PENDING,
    REFUNDED
}
