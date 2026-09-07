package com.example.movieticket.service;

import com.example.movieticket.config.PaymentProperties;
import com.example.movieticket.exception.PaymentVerificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * plan/payment.md section 10.1, test 18: the mock gateway's full async round trip
 * (create -> mock-pay -> webhook -> CONFIRMED-equivalent event), with no network
 * and no Redis - exercising the SAME signature-verification path a real gateway's
 * webhook would use (MockPaymentGateway's own javadoc), not a bypass of it.
 */
class MockPaymentGatewayTest {

    private MockPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        PaymentProperties properties = new PaymentProperties();
        properties.setCallbackBaseUrl("http://localhost:8080");
        gateway = new MockPaymentGateway(properties);
    }

    @Test
    void fullRoundTrip_createIntent_thenSimulatedWebhook_parsesToCapturedEvent() {
        PaymentIntent intent = gateway.createIntent(new PaymentIntentCommand("ref-1", null, "INR", "a@b.com", "http://cb"));
        assertTrue(intent.providerOrderId().startsWith("mock_order_"));
        assertTrue(intent.checkoutUrl().endsWith("/mock-gateway/pay/" + intent.providerOrderId()));

        // What MockGatewayController does: build "orderId|paymentId" and sign it
        // exactly like a real webhook body would be signed (section 8.1).
        String rawBody = intent.providerOrderId() + "|mock_pay_123";
        String signature = gateway.signWebhookBody(rawBody);

        WebhookEvent event = gateway.verifyAndParseWebhook(rawBody, signature);

        assertEquals("captured", event.status());
        assertEquals(intent.providerOrderId(), event.providerOrderId());
        assertEquals("mock_pay_123", event.providerPaymentId());
    }

    @Test
    void verifyAndParseWebhook_tamperedSignature_throwsVerificationException() {
        String rawBody = "mock_order_x|mock_pay_y";
        assertThrows(PaymentVerificationException.class,
                () -> gateway.verifyAndParseWebhook(rawBody, "0000not-a-real-signature"));
    }

    @Test
    void verifyCallbackSignature_matchesWebhookSecret_sameConstructionAsRazorpay() {
        // The mock signs "orderId|paymentId" for the browser-callback path too,
        // same construction Razorpay uses (section 6.2) - just with its own
        // locally generated secret instead of a configured one.
        String payload = "order-9|pay-9";
        String signature = gateway.signWebhookBody(payload);
        assertDoesNotThrow(() -> gateway.verifyCallbackSignature("order-9", "pay-9", signature));
    }
}
