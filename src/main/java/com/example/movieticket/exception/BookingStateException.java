package com.example.movieticket.exception;

/**
 * An operation that's illegal for the booking's current status - e.g. cancelling
 * an already-CONFIRMED booking, or confirming one with no payment order yet.
 * Mapped to 409 Conflict (plan/payment.md section 2, endpoint 6).
 */
public class BookingStateException extends RuntimeException {
    public BookingStateException(String message) {
        super(message);
    }
}
