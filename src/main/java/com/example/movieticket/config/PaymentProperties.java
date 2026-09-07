package com.example.movieticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code payment.*} block in application.properties (plan/payment.md
 * section 9). Bean selection between MockPaymentGateway/RazorpayPaymentGateway is
 * via {@code @ConditionalOnProperty("payment.gateway")} on those classes
 * themselves, not read from here - this class only carries the values each
 * gateway implementation needs once it's already the one selected.
 */
@Data
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {

    private String gateway = "mock";
    private String currency = "INR";
    private String callbackBaseUrl = "http://localhost:8080";
    private Razorpay razorpay = new Razorpay();

    @Data
    public static class Razorpay {
        private String apiBaseUrl = "https://api.razorpay.com/v1";
        // Deliberately no safe local default for a credential (claude.md's
        // standing warning about the JWT_SECRET default, plan/payment.md
        // section 8.6) - RazorpayPaymentGateway refuses to construct if any of
        // these three is blank.
        private String keyId = "";
        private String keySecret = "";
        private String webhookSecret = "";
    }
}
