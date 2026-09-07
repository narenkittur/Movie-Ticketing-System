package com.example.movieticket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /bookings/{bookingId}/confirm - the browser-callback confirmation
 * path (plan/payment.md section 2, endpoint 2; section 8.4). Deliberately carries
 * NO providerOrderId field: that value is resolved server-side from the booking's
 * own persisted payment row, keyed off the path variable + the authenticated
 * caller - never trusted from a value this request body could echo back. Otherwise
 * a caller could present a signature that's internally consistent for an order
 * that isn't theirs, which section 8.4 calls out as the single most commonly
 * botched part of a Razorpay integration.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmPaymentRequest {

    @NotBlank(message = "providerPaymentId is required")
    private String providerPaymentId;

    @NotBlank(message = "signature is required")
    private String signature;
}
