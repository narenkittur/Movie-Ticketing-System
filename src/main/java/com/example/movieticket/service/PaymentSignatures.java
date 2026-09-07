package com.example.movieticket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The one place HMAC-SHA256 signing/verification happens (plan/payment.md section
 * 8.3) - both {@link MockPaymentGateway} and {@link RazorpayPaymentGateway} share
 * this instead of each rolling their own, so there is a single place a
 * constant-time-comparison bug could ever be introduced, not two.
 */
public final class PaymentSignatures {

    private static final Logger log = LoggerFactory.getLogger(PaymentSignatures.class);
    private static final String ALGORITHM = "HmacSHA256";

    private PaymentSignatures() {
    }

    /** {@code HMAC_SHA256(payload, secret)}, lower-case hex. */
    public static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (InvalidKeyException | NoSuchAlgorithmException ex) {
            // Only thrown for a malformed key/unknown algorithm - both are
            // programmer errors (a blank secret is rejected earlier, at
            // RazorpayPaymentGateway's constructor - section 6.2), never a
            // runtime user-input problem, so there is nothing more useful to do
            // than fail loudly.
            throw new IllegalStateException("Failed to compute HMAC signature", ex);
        }
    }

    /**
     * Constant-time comparison (section 8.3): {@code MessageDigest.isEqual},
     * NEVER {@code String.equals}, which short-circuits on the first mismatched
     * character and can leak timing information about how much of a guessed
     * signature was already correct.
     */
    public static boolean verify(String payload, String secret, String expectedSignatureHex) {
        if (expectedSignatureHex == null || expectedSignatureHex.isBlank()) {
            return false;
        }
        String actual = sign(payload, secret);
        boolean matches = MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expectedSignatureHex.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            log.warn("Payment signature verification failed");
        }
        return matches;
    }
}
