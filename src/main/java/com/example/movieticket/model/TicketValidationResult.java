package com.example.movieticket.model;

/**
 * Outcome of a {@code POST /admin/tickets/validate} scan (plan/qrtickets.md
 * section 5.4). Sits with {@link BookingStatus}/{@link PaymentStatus} as this
 * project's third entity/domain enum. Every value here maps to HTTP 200 - this
 * is deliberately NOT thrown as an exception through GlobalExceptionHandler,
 * because the caller is a turnstile that must act differently on each of these
 * five outcomes (let them in / call security / send to box office / come back
 * later / garbage input), not a browser that only needs "succeeded or not."
 */
public enum TicketValidationResult {
    /** First successful scan. {@code checkedInAt} was just written - let them in. */
    VALID,
    /** Redeemed earlier; response carries the original {@code checkedInAt}. */
    ALREADY_USED,
    /** Booking exists but is PENDING/EXPIRED/FAILED/REFUND_PENDING/REFUNDED. */
    NOT_CONFIRMED,
    /** Right ticket, wrong time - outside [startTime - opens-minutes-before, startTime + duration]. */
    OUTSIDE_WINDOW,
    /** No booking with that reference. */
    NOT_FOUND
}
