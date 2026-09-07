package com.example.movieticket.controller;

import com.example.movieticket.service.MockPaymentGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * *** MOCKED - stands in for the customer completing payment at the real
 * gateway's hosted page. *** (plan/payment.md section 2, endpoint 7; section
 * 6.1). Only present on the context when {@code payment.gateway=mock} (matches
 * {@link MockPaymentGateway}'s own condition) - not reachable, or even wired,
 * in Razorpay mode.
 *
 * <p>Calls the SAME {@code PaymentWebhookController.handleWebhook} the real
 * gateway's servers would call, with a body it signs itself using
 * {@code MockPaymentGateway}'s secret - an in-process call, not a second HTTP
 * round trip, but exercising the identical signature-verification +
 * {@code confirmPaid} code path either way. This is the whole point of the mock's
 * design (section 6.1): the async path is walked, not bypassed.
 */
@RestController
@ConditionalOnProperty(prefix = "payment", name = "gateway", havingValue = "mock", matchIfMissing = true)
@Tag(name = "Mock Gateway", description = "MOCKED payment - stands in for the customer paying; only active when payment.gateway=mock")
public class MockGatewayController {

    private static final Logger log = LoggerFactory.getLogger(MockGatewayController.class);

    private final MockPaymentGateway mockPaymentGateway;
    private final PaymentWebhookController paymentWebhookController;

    public MockGatewayController(MockPaymentGateway mockPaymentGateway, PaymentWebhookController paymentWebhookController) {
        this.mockPaymentGateway = mockPaymentGateway;
        this.paymentWebhookController = paymentWebhookController;
    }

    /** POST /mock-gateway/pay/{providerOrderId} - public, mock mode only. Simulates a successful payment. */
    @PostMapping("/mock-gateway/pay/{providerOrderId}")
    @Operation(summary = "MOCKED: simulate the customer successfully paying")
    public ResponseEntity<Void> pay(@PathVariable String providerOrderId) {
        String providerPaymentId = "mock_pay_" + UUID.randomUUID();
        String rawBody = providerOrderId + "|" + providerPaymentId;
        String signature = mockPaymentGateway.signWebhookBody(rawBody);

        log.warn("MOCK payment for order {} -- simulating webhook delivery, no real money involved", providerOrderId);
        return paymentWebhookController.handleWebhook(rawBody, signature);
    }
}
