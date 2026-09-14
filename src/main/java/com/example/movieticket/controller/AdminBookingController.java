package com.example.movieticket.controller;

import com.example.movieticket.dto.BookingResponse;
import com.example.movieticket.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only booking/payment operations (Module 9, resolving plan/payment.md
 * Open Decision A). Split by AUDIENCE from BookingController's owner-scoped
 * endpoints - the same "one small, auditable admin file" pattern TicketController
 * established for the ticket gate, just for the payments/bookings domain
 * instead. Under {@code /admin/**}, so no new SecurityConfig matcher is needed -
 * the coarse {@code /admin/**} URL rule already there is layer one;
 * {@code @PreAuthorize} below is layer two, the same two-layer gate Module 3
 * established for every admin controller.
 */
@RestController
@Tag(name = "Admin Bookings", description = "Admin-only refund operations")
public class AdminBookingController {

    private final BookingService bookingService;

    public AdminBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * POST /admin/bookings/{bookingId}/refund - ROLE_ADMIN only. Only a booking
     * whose payment is REFUND_PENDING can be refunded (409 via
     * BookingStateException otherwise - see BookingService.refund's javadoc for
     * the full state machine). Takes no request body: the refund amount and the
     * gateway payment id to refund are resolved from our own stored Payment row,
     * never from anything the caller could submit - there is nothing for an
     * admin to specify but which booking.
     */
    @PostMapping("/admin/bookings/{bookingId}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Refund a REFUND_PENDING booking via the payment gateway", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<BookingResponse> refund(@PathVariable Long bookingId, Authentication authentication) {
        BookingResponse response = bookingService.refund(bookingId, authentication.getName());
        return ResponseEntity.ok(response);
    }
}
