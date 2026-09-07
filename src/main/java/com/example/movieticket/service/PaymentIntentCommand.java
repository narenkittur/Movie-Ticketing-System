package com.example.movieticket.service;

import java.math.BigDecimal;

/**
 * Input to {@link PaymentGateway#createIntent}. {@code bookingReference} (not the
 * numeric booking id) is what's handed to the gateway - see {@code Booking}'s own
 * javadoc for why the id itself is never exposed externally (plan/payment.md
 * section 6).
 */
public record PaymentIntentCommand(
        String bookingReference,
        BigDecimal amount,
        String currency,
        String customerEmail,
        String callbackUrl) {
}
