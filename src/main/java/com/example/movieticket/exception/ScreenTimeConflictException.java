package com.example.movieticket.exception;

/**
 * Thrown by ShowService when a new show's [startTime, startTime + duration) window
 * overlaps another show already scheduled on the same screen (plan/crud.md section
 * 4.2) - a screen can only play one movie at a time. Mapped to 409 Conflict by
 * GlobalExceptionHandler, same status family as other "this collides with existing
 * state" cases (e.g. duplicate seat layout, duplicate username).
 */
public class ScreenTimeConflictException extends RuntimeException {
    public ScreenTimeConflictException(String message) {
        super(message);
    }
}
