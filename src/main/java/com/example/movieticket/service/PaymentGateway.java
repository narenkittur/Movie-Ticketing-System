package com.example.movieticket.service;

import java.math.BigDecimal;

/**
 * The seam between this module and whichever payment provider actually handles a
 * booking (plan/payment.md section 6) - same pattern this project already used
 * once for {@code SeatLockView} -&gt; {@code NoOpSeatLockView} (Module 3) -&gt;
 * {@code RedisSeatLockView} (Module 4). Bean selection is
 * {@code @ConditionalOnProperty("payment.gateway", havingValue = ...)}, so exactly
 * one implementation is ever on the context - no {@code @Primary}, no list
 * injection, no runtime branch.
 *
 * <p>Two implementations: {@link MockPaymentGateway} (default, offline, no
 * credentials) and {@link RazorpayPaymentGateway} (real Razorpay TEST mode - see
 * that class's javadoc for why "real" and "mocked" here differ by nothing but a
 * key prefix).
 */
public interface PaymentGateway {

    /** "mock" | "razorpay" - persisted on the payment row so a booking always records which implementation actually handled it. */
    String name();

    /** Creates the gateway-side order/link. Returns its id + a URL the customer pays at. */
    PaymentIntent createIntent(PaymentIntentCommand command);

    /** Browser-callback path. Throws {@link com.example.movieticket.exception.PaymentVerificationException} (400) on mismatch. */
    void verifyCallbackSignature(String providerOrderId, String providerPaymentId, String signature);

    /** Webhook path. Verifies over the RAW request body, THEN parses - never the other way round (plan/payment.md section 8.1). Throws on mismatch. */
    WebhookEvent verifyAndParseWebhook(String rawBody, String signatureHeader);

    /** Drives REFUND_PENDING -&gt; REFUNDED. Implemented but never auto-invoked by BookingService (Open Decision A) - see plan/payment.md section 13-A. */
    void refund(String providerPaymentId, BigDecimal amount);
}
