package com.example.movieticket.service;

import com.example.movieticket.config.PaymentProperties;
import com.example.movieticket.exception.PaymentGatewayException;
import com.example.movieticket.exception.PaymentVerificationException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Map;

/**
 * The real gateway (plan/payment.md section 6.2) - Razorpay TEST mode by default
 * ({@code rzp_test_...} keys). The only difference between this being a real,
 * free integration and one processing real money is the key prefix (section 1.1
 * - "real HTTP, real order objects, real HMAC signatures, real webhooks" costs
 * nothing; only merchant KYC, which a portfolio project should decline, does).
 *
 * <p><b>Payment Links, not Checkout.js</b>: this project has no frontend until
 * Module 8. A Payment Link is created server-side and returns a URL that opens in
 * any browser, making a real end-to-end test possible today from a backend-only
 * codebase.
 *
 * <p><b>No Razorpay SDK</b> (section 6.3): the entire server-side surface needed
 * is create-a-link, verify-a-callback-signature, verify-a-webhook-signature -
 * roughly forty lines with {@code RestClient} (already present via
 * spring-boot-starter-webmvc), {@code javax.crypto.Mac}, and
 * {@code java.util.HexFormat} (both JDK). The SDK pulls okhttp3 + org.json onto a
 * Spring Boot 4.1/Jackson 3 classpath that has already produced one Jackson
 * collision (claude.md's JSON note, logic/jwt.md section 2.9) - not worth it for
 * three HTTP calls, same reasoning that rejected MapStruct in Module 3.
 * {@code pom.xml} is unchanged by this module.
 */
@Service
@ConditionalOnProperty(prefix = "payment", name = "gateway", havingValue = "razorpay")
public class RazorpayPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGateway.class);

    private final RestClient restClient;
    private final PaymentProperties properties;
    // Spring Boot's own auto-configured Jackson 3 ObjectMapper bean - never
    // `new ObjectMapper()`, matching RestAuthenticationEntryPoint/
    // RestAccessDeniedHandler's established convention (claude.md's JSON note).
    private final ObjectMapper objectMapper;

    public RazorpayPaymentGateway(RestClient paymentGatewayRestClient, PaymentProperties properties, ObjectMapper objectMapper) {
        this.restClient = paymentGatewayRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;

        // Fail fast (section 6.2): a half-configured payment gateway should
        // refuse to start, not fail on the first real customer.
        PaymentProperties.Razorpay rp = properties.getRazorpay();
        if (isBlank(rp.getKeyId()) || isBlank(rp.getKeySecret()) || isBlank(rp.getWebhookSecret())) {
            throw new IllegalStateException(
                    "payment.gateway=razorpay requires RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and " +
                    "RAZORPAY_WEBHOOK_SECRET all to be set - refusing to start half-configured.");
        }
    }

    @PostConstruct
    void logActive() {
        log.info("Razorpay payment gateway active against {}", properties.getRazorpay().getApiBaseUrl());
    }

    @Override
    public String name() {
        return "razorpay";
    }

    @Override
    public PaymentIntent createIntent(PaymentIntentCommand command) {
        PaymentProperties.Razorpay rp = properties.getRazorpay();
        // Amounts are paise (integer minor units). Never doubleValue() -
        // movePointRight(2).longValueExact() THROWS rather than silently
        // truncating a price that somehow carries sub-paise precision.
        long amountPaise = command.amount().movePointRight(2).longValueExact();

        Map<String, Object> body = Map.of(
                "amount", amountPaise,
                "currency", command.currency(),
                "reference_id", command.bookingReference(),
                "callback_url", command.callbackUrl(),
                "callback_method", "get");

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri(rp.getApiBaseUrl() + "/payment_links")
                    .header("Authorization", basicAuth())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception ex) {
            log.error("Razorpay createIntent failed for booking {}: {}", command.bookingReference(), ex.getMessage());
            throw new PaymentGatewayException("Payment gateway is currently unreachable");
        }

        if (response == null || response.get("id") == null || response.get("short_url") == null) {
            log.error("Razorpay createIntent returned an unexpected body for booking {}", command.bookingReference());
            throw new PaymentGatewayException("Payment gateway returned an unexpected response");
        }

        return new PaymentIntent(String.valueOf(response.get("id")), String.valueOf(response.get("short_url")));
    }

    @Override
    public void verifyCallbackSignature(String providerOrderId, String providerPaymentId, String signature) {
        String payload = providerOrderId + "|" + providerPaymentId;
        if (!PaymentSignatures.verify(payload, properties.getRazorpay().getKeySecret(), signature)) {
            throw new PaymentVerificationException("Razorpay callback signature mismatch");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public WebhookEvent verifyAndParseWebhook(String rawBody, String signatureHeader) {
        // Section 8.1: verified over the EXACT raw bytes Razorpay sent, BEFORE
        // any parsing - deserializing first and re-serializing to verify would
        // change whitespace/key order and the signature would never match again.
        if (!PaymentSignatures.verify(rawBody, properties.getRazorpay().getWebhookSecret(), signatureHeader)) {
            throw new PaymentVerificationException("Razorpay webhook signature mismatch");
        }

        Map<String, Object> root = objectMapper.readValue(rawBody, Map.class);
        String event = String.valueOf(root.get("event"));
        Map<String, Object> payload = (Map<String, Object>) root.get("payload");
        Map<String, Object> paymentWrapper = (Map<String, Object>) payload.get("payment");
        Map<String, Object> entity = (Map<String, Object>) paymentWrapper.get("entity");

        String providerOrderId = String.valueOf(entity.get("order_id"));
        String providerPaymentId = String.valueOf(entity.get("id"));
        String status = String.valueOf(entity.get("status"));

        return new WebhookEvent(event, providerOrderId, providerPaymentId, status);
    }

    @Override
    public void refund(String providerPaymentId, BigDecimal amount) {
        long amountPaise = amount.movePointRight(2).longValueExact();
        try {
            restClient.post()
                    .uri(properties.getRazorpay().getApiBaseUrl() + "/payments/" + providerPaymentId + "/refund")
                    .header("Authorization", basicAuth())
                    .body(Map.of("amount", amountPaise))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.error("Razorpay refund failed for payment {}: {}", providerPaymentId, ex.getMessage());
            throw new PaymentGatewayException("Refund could not be submitted to the payment gateway");
        }
    }

    private String basicAuth() {
        PaymentProperties.Razorpay rp = properties.getRazorpay();
        String credentials = rp.getKeyId() + ":" + rp.getKeySecret();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
