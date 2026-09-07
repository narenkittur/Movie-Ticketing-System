package com.example.movieticket.exception;

/**
 * Thrown when a requested seat id doesn't belong to the given show (or doesn't
 * exist at all) - SeatLockService.lock(). Skipping this check would let a caller
 * "lock" a key nobody ever reads (a seat belonging to a different show), silently
 * doing nothing while the user believes they hold a seat - see plan/redis.md
 * section 6.1 step 3. Mapped to 404 Not Found by GlobalExceptionHandler.
 */
public class SeatNotFoundException extends RuntimeException {
    public SeatNotFoundException(String message) {
        super(message);
    }
}
