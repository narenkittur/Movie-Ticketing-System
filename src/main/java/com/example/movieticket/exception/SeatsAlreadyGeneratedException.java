package com.example.movieticket.exception;

/**
 * Thrown by SeatService when POST /admin/shows/{showId}/seats is called for a show
 * that already has a seat layout (plan/crud.md section 4.3). Makes the "generate
 * layout" operation safe to retry without side effects: a second call reports the
 * conflict instead of silently doubling the seat inventory. Mapped to 409 Conflict
 * by GlobalExceptionHandler.
 */
public class SeatsAlreadyGeneratedException extends RuntimeException {
    public SeatsAlreadyGeneratedException(String message) {
        super(message);
    }
}
