package com.example.movieticket.exception;

/**
 * Thrown by {@code SeatLockService.assertHoldsAll()} - the Module 5 hook
 * (plan/redis.md section 11.1) - when a booking attempt discovers the caller no
 * longer holds every seat they're trying to book: the 300s TTL ran out mid-checkout,
 * or the lock was never theirs to begin with. This is the accepted cost of the
 * project's no-heartbeat rule (claude.md) - the transaction never commits, so
 * nothing is left corrupted. Mapped to 409 Conflict.
 */
public class SeatLockExpiredException extends RuntimeException {
    public SeatLockExpiredException(String message) {
        super(message);
    }
}
