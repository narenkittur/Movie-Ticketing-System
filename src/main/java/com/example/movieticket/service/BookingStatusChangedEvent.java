package com.example.movieticket.service;

import com.example.movieticket.model.BookingStatus;

/**
 * Module 6 (plan/websockets.md section 6): published by
 * {@link BookingService#confirmPaid} on all three terminal branches - the
 * CONFIRMED path and both REFUND_PENDING paths (paid-too-late, paid-but-seat-
 * gone) - so the payer's browser learns the outcome the instant it commits,
 * instead of polling {@code GET /bookings/{bookingId}}.
 *
 * <p>{@code username}, not {@code userId}: {@link BookingBroadcaster} hands this
 * straight to {@code SimpMessagingTemplate.convertAndSendToUser}, which resolves
 * STOMP's user-destination registry by principal name (the JWT "sub" claim,
 * same identity {@code JwtAuthenticationFilter}/{@code StompAuthChannelInterceptor}
 * both authenticate against) - never a raw numeric id.
 */
public record BookingStatusChangedEvent(Long bookingId, String username, BookingStatus status) {
}
