package com.example.movieticket.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * plan/payment.md section 10.1, tests 13-15. A fixed secret + fixed input must
 * always produce the same hex digest (a regression here would silently break
 * every future signature check), a tampered body must be rejected, and comparison
 * must be constant-time (MessageDigest.isEqual, never String.equals - section 8.3).
 */
class PaymentSignaturesTest {

    // HMAC-SHA256("hello", "secret") - independently verifiable known vector.
    private static final String KNOWN_PAYLOAD = "hello";
    private static final String KNOWN_SECRET = "secret";
    private static final String KNOWN_DIGEST_HEX = "88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b";

    @Test
    void sign_knownVector_producesKnownHexDigest() {
        assertEquals(KNOWN_DIGEST_HEX, PaymentSignatures.sign(KNOWN_PAYLOAD, KNOWN_SECRET));
    }

    @Test
    void verify_correctSignature_returnsTrue() {
        String signature = PaymentSignatures.sign(KNOWN_PAYLOAD, KNOWN_SECRET);
        assertTrue(PaymentSignatures.verify(KNOWN_PAYLOAD, KNOWN_SECRET, signature));
    }

    @Test
    void verify_isCaseInsensitiveOnHex_sinceGatewaysArentConsistentAboutCasing() {
        String signature = PaymentSignatures.sign(KNOWN_PAYLOAD, KNOWN_SECRET);
        assertTrue(PaymentSignatures.verify(KNOWN_PAYLOAD, KNOWN_SECRET, signature.toUpperCase()));
    }

    @Test
    void verify_tamperedPayload_isRejected() {
        String signature = PaymentSignatures.sign(KNOWN_PAYLOAD, KNOWN_SECRET);
        assertFalse(PaymentSignatures.verify("hello!", KNOWN_SECRET, signature));
    }

    @Test
    void verify_wrongSecret_isRejected() {
        String signature = PaymentSignatures.sign(KNOWN_PAYLOAD, KNOWN_SECRET);
        assertFalse(PaymentSignatures.verify(KNOWN_PAYLOAD, "wrong-secret", signature));
    }

    @Test
    void verify_nullOrBlankSignature_isRejected_notAnException() {
        assertFalse(PaymentSignatures.verify(KNOWN_PAYLOAD, KNOWN_SECRET, null));
        assertFalse(PaymentSignatures.verify(KNOWN_PAYLOAD, KNOWN_SECRET, ""));
    }
}
