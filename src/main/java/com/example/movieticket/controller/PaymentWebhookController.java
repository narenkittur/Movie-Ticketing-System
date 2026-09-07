package com.example.movieticket.controller;

import com.example.movieticket.service.BookingService;
import com.example.movieticket.service.PaymentGateway;
import com.example.movieticket.service.WebhookEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /payments/webhook (plan/payment.md section 2, endpoint 3) - public ON
 * PURPOSE, and the single most dangerous line in this module (section 8.2,
 * section 14 point 1). Razorpay's servers carry no JWT; the HMAC signature IS the
 * authentication. SecurityConfig MUST permitAll this path AHEAD of
 * {@code .anyRequest().authenticated()} - verify by reading the matcher list, not
 * by the endpoint appearing to work (a too-late matcher makes every webhook
 * silently 401 and nothing ever confirms, and nobody notices until a customer
 * complains).
 */
@RestController
@Tag(name = "Payment Webhook", description = "Public gateway-callback confirmation path - signature-verified, not JWT-verified")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final PaymentGateway paymentGateway;
    private final BookingService bookingService;

    public PaymentWebhookController(PaymentGateway paymentGateway, BookingService bookingService) {
        this.paymentGateway = paymentGateway;
        this.bookingService = bookingService;
    }

    /**
     * {@code @RequestBody String rawBody}, NEVER a parsed DTO (section 8.1): the
     * HMAC is computed over the exact bytes the gateway sent. Deserializing to an
     * object and re-serializing to verify would change whitespace/key order and
     * the signature would never match again - parse only AFTER verification, and
     * that parsing itself lives inside {@code PaymentGateway.verifyAndParseWebhook},
     * not here.
     */
    @PostMapping("/payments/webhook")
    @Operation(summary = "Gateway webhook - signature verified, not JWT authenticated")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String rawBody, @RequestHeader("X-Razorpay-Signature") String signature) {
        WebhookEvent event = paymentGateway.verifyAndParseWebhook(rawBody, signature);

        if ("captured".equalsIgnoreCase(event.status()) || "payment.captured".equalsIgnoreCase(event.type())) {
            bookingService.confirmPaid(event.providerOrderId(), event.providerPaymentId());
        } else {
            log.info("Ignoring webhook event type={} status={} for order {}", event.type(), event.status(), event.providerOrderId());
        }

        return ResponseEntity.ok().build();
    }
}
