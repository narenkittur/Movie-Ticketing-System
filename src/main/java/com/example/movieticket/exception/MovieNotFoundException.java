package com.example.movieticket.exception;

/**
 * Thrown when a {@code movieId} path variable doesn't resolve to any row in the
 * movies table - e.g. PUT /admin/movies/999 or GET /movies/999/shows. Mapped to
 * 404 Not Found by GlobalExceptionHandler.
 */
public class MovieNotFoundException extends RuntimeException {
    public MovieNotFoundException(String message) {
        super(message);
    }
}
