package com.example.movieticket.service;

import com.example.movieticket.config.PaymentProperties;
import com.example.movieticket.exception.PaymentVerificationException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * *** MOCKED PAYMENT GATEWAY - NO MONEY MOVES, NO NETWORK CALL, NO EXTERNAL
 * ACCOUNT. *** Default bean ({@code matchIfMissing = true}) so
 * {@code ./mvnw spring-boot:run} and every unit test work offline with no
 * credentials (plan/payment.md section 6.1).
 *
 * <p>Unlike a naive mock that just returns {@code CONFIRMED} synchronously, this
 * class walks the SAME asynchronous contract a real gateway forces: it signs with
 * the identical HMAC construction {@link PaymentSignatures}/
 * {@link RazorpayPaymentGateway} use, and its {@code checkoutUrl} points back into
 * this application ({@code MockGatewayController}) rather than confirming
 * anything itself - "paying" is a separate, later HTTP call that goes through the
 * exact same webhook handler production traffic would. The idempotency, the
 * late-payment branch, and the seat-gone branch in {@code BookingService.confirmPaid}
 * are therefore all reachable and testable with no network at all.
 */
@Service
@ConditionalOnProperty(prefix = "payment", name = "gateway", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    // Generated once per process, never persisted or exposed - good enough to
    // exercise the real HMAC code path with zero configuration (this bean's whole
    // point). A restart invalidates any in-flight mock signature, which is fine:
    // nothing about mock mode needs to survive a restart.
    private final String secret = HexFormat.of().formatHex(randomBytes());

    private final PaymentProperties properties;

    public MockPaymentGateway(PaymentProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void warnMocked() {
        log.warn("*** payment.gateway=mock is active -- NO REAL PAYMENT PROCESSING. " +
                "See README.md before treating any booking made in this mode as a real sale. ***");
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public PaymentIntent createIntent(PaymentIntentCommand command) {
        String providerOrderId = "mock_order_" + UUID.randomUUID();
        String checkoutUrl = properties.getCallbackBaseUrl() + "/mock-gateway/pay/" + providerOrderId;
        log.info("Mock gateway created intent {} for booking {}", providerOrderId, command.bookingReference());
        return new PaymentIntent(providerOrderId, checkoutUrl);
    }

    @Override
    public void verifyCallbackSignature(String providerOrderId, String providerPaymentId, String signature) {
        String payload = providerOrderId + "|" + providerPaymentId;
        if (!PaymentSignatures.verify(payload, secret, signature)) {
            throw new PaymentVerificationException("Mock callback signature mismatch");
        }
    }

    @Override
    public WebhookEvent verifyAndParseWebhook(String rawBody, String signatureHeader) {
        // Mock webhook body is deliberately the trivial shape MockGatewayController
        // sends: "providerOrderId|providerPaymentId", signed exactly like a real
        // webhook body would be (HMAC over the raw string, section 8.1). This
        // class never needs to look like Razorpay's actual JSON envelope - only
        // exercise the same signature discipline and the same
        // BookingService.confirmPaid call. Real parsing of Razorpay's JSON lives
        // entirely in RazorpayPaymentGateway.
        if (!PaymentSignatures.verify(rawBody, secret, signatureHeader)) {
            throw new PaymentVerificationException("Mock webhook signature mismatch");
        }
        String[] parts = rawBody.split("\\|", 2);
        if (parts.length != 2) {
            throw new PaymentVerificationException("Malformed mock webhook body");
        }
        return new WebhookEvent("payment.captured", parts[0], parts[1], "captured");
    }

    @Override
    public void refund(String providerPaymentId, BigDecimal amount) {
        log.warn("Mock gateway 'refunding' payment {} amount {} -- no real money involved", providerPaymentId, amount);
    }

    /**
     * Lets {@code MockGatewayController} sign the exact raw body it submits to
     * the shared webhook handler, so the mock exercises the SAME signature
     * verification path a real gateway's webhook would, not a bypass of it.
     */
    public String signWebhookBody(String rawBody) {
        return PaymentSignatures.sign(rawBody, secret);
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
