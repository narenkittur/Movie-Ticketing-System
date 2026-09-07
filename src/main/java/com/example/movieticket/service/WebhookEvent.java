package com.example.movieticket.service;

/**
 * Parsed result of {@link PaymentGateway#verifyAndParseWebhook}, AFTER signature
 * verification. {@code type}/{@code status} are provider-specific strings (e.g.
 * Razorpay's {@code "payment.captured"}); {@code PaymentWebhookController} only
 * acts on a captured/successful event and logs-and-ignores the rest
 * (plan/payment.md section 6).
 */
public record WebhookEvent(String type, String providerOrderId, String providerPaymentId, String status) {
}
