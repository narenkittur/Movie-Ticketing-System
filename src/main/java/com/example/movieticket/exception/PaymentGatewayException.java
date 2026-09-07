package com.example.movieticket.exception;

/**
 * The payment gateway itself is unreachable, times out, or returns a non-2xx
 * response (plan/payment.md section 9). Mapped to 502 Bad Gateway - deliberately
 * NOT 503: 503 in this codebase already means "we are degraded, fail closed"
 * (Redis down, see SeatLockUnavailableException). A third party being down is a
 * distinct condition and should read differently in logs/dashboards.
 */
public class PaymentGatewayException extends RuntimeException {
    public PaymentGatewayException(String message) {
        super(message);
    }
}
