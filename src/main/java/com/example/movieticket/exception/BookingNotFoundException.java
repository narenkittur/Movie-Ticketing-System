package com.example.movieticket.exception;

/**
 * Unknown booking id, or one that belongs to someone other than the caller.
 * Mapped to 404 Not Found - deliberately not 403, so booking ids aren't
 * enumerable (plan/payment.md section 8.5).
 */
public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(String message) {
        super(message);
    }
}
