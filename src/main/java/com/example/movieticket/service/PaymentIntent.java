package com.example.movieticket.service;

/**
 * Result of {@link PaymentGateway#createIntent}: the gateway's own order id, and
 * the URL the customer pays at (plan/payment.md section 6).
 */
public record PaymentIntent(String providerOrderId, String checkoutUrl) {
}
