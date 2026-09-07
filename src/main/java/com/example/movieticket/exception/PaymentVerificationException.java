package com.example.movieticket.exception;

/**
 * Signature mismatch on either the browser-callback or webhook confirmation path
 * (plan/payment.md section 8.3) - the gateway's HMAC didn't match what we
 * computed. Mapped to 400 Bad Request.
 */
public class PaymentVerificationException extends RuntimeException {
    public PaymentVerificationException(String message) {
        super(message);
    }
}
