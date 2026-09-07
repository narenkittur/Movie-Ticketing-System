package com.example.movieticket.exception;

import java.util.List;

/**
 * Thrown when one or more requested seats can't be locked - already {@code BOOKED}
 * in the DB (a cheap pre-check before Redis is ever touched), or held by another
 * user according to {@code lock_seats.lua}'s pass 1 (plan/redis.md section 6.1).
 * Mapped to 409 Conflict; {@code details} names the specific seats that blocked the
 * request, reusing {@code ErrorResponse.details}, so a client can deselect exactly
 * the lost seat and retry instead of clearing its whole selection and guessing.
 */
public class SeatUnavailableException extends RuntimeException {

    private final List<String> details;

    public SeatUnavailableException(String message, List<String> details) {
        super(message);
        this.details = details;
    }

    public List<String> getDetails() {
        return details;
    }
}
