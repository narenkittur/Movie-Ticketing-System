package com.example.movieticket.exception;

/**
 * Thrown when a {@code showId} path variable doesn't resolve to any row in the
 * shows table - e.g. POST /admin/shows/999/seats or GET /shows/999/seats. Mapped
 * to 404 Not Found by GlobalExceptionHandler.
 */
public class ShowNotFoundException extends RuntimeException {
    public ShowNotFoundException(String message) {
        super(message);
    }
}
