package com.example.movieticket.exception;

/**
 * Thrown deliberately whenever Redis is unreachable or a lock/read operation times
 * out - covers the lock/release/mine endpoints as well as the public seat map
 * (RedisSeatLockView). Mapped to 503 Service Unavailable by GlobalExceptionHandler.
 * This is claude.md's fail-closed rule made concrete: a seat is never assumed free
 * just because Redis didn't answer.
 */
public class SeatLockUnavailableException extends RuntimeException {
    public SeatLockUnavailableException(String message) {
        super(message);
    }
}
