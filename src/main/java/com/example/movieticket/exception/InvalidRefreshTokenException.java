package com.example.movieticket.exception;

/**
 * Thrown by AuthService when a client presents a refresh token that is unknown,
 * already revoked, or past its expiry date. Mapped to 401 Unauthorized by
 * GlobalExceptionHandler - deliberately the same status as bad login credentials,
 * so the response doesn't reveal *why* the token was rejected (unknown vs revoked
 * vs expired are all equally "not usable" from the caller's point of view).
 */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
