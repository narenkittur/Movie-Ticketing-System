package com.example.movieticket.controller;

import com.example.movieticket.dto.BookingResponse;
import com.example.movieticket.dto.ConfirmPaymentRequest;
import com.example.movieticket.dto.CreateBookingRequest;
import com.example.movieticket.dto.TicketResponse;
import com.example.movieticket.service.BookingService;
import com.example.movieticket.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Module 5's core booking lifecycle endpoints (plan/payment.md section 2,
 * endpoints #1, #2, #4-#6), plus Module 7's two owner-scoped ticket views
 * (plan/qrtickets.md section 4.3/4.4 - see that doc's section 6 for why these
 * two live here rather than on TicketController: they're owner-scoped views of
 * a booking, splitting by AUDIENCE keeps the admin gate surface in its own
 * small file). Thin per claude.md principle #4 - every decision is made in
 * {@link BookingService}/{@link TicketService}. {@code @Validated} at class
 * level is what makes {@code @Min}/{@code @Max} on {@link #qr}'s bare
 * {@code size} query param actually get enforced (throws
 * {@code ConstraintViolationException}, mapped to 400 in GlobalExceptionHandler).
 */
@RestController
@Validated
@Tag(name = "Bookings", description = "Create, confirm, list, and cancel bookings")
public class BookingController {

    private final BookingService bookingService;
    private final TicketService ticketService;

    public BookingController(BookingService bookingService, TicketService ticketService) {
        this.bookingService = bookingService;
        this.ticketService = ticketService;
    }

    /** POST /bookings - any logged-in user. Returns 201 with a checkoutUrl to pay at. */
    @PostMapping("/bookings")
    @Operation(summary = "Create a PENDING booking + payment intent for 1-10 seats", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request, Authentication authentication) {
        BookingResponse response = bookingService.createBooking(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /bookings/{bookingId}/confirm - browser-callback confirmation path
     * (plan/payment.md section 8.4). Two BookingService calls, deliberately: the
     * first verifies the caller owns this booking and checks the gateway's
     * signature against OUR stored providerOrderId (never one this request body
     * echoes back); the second is the same idempotent confirmPaid the webhook
     * (PaymentWebhookController) also calls - see BookingService.confirmPaid's
     * javadoc for why they share one method.
     */
    @PostMapping("/bookings/{bookingId}/confirm")
    @Operation(summary = "Confirm payment via the browser redirect callback", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<BookingResponse> confirm(
            @PathVariable Long bookingId, @Valid @RequestBody ConfirmPaymentRequest request, Authentication authentication) {
        String providerOrderId = bookingService.verifyCallbackAndResolveOrderId(bookingId, request, authentication.getName());
        BookingResponse response = bookingService.confirmPaid(providerOrderId, request.getProviderPaymentId());
        return ResponseEntity.ok(response);
    }

    /** GET /bookings/{bookingId} - owner only; 404 (not 403) for someone else's booking (section 8.5). */
    @GetMapping("/bookings/{bookingId}")
    @Operation(summary = "Get one booking with its seats and payment status", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<BookingResponse> get(@PathVariable Long bookingId, Authentication authentication) {
        BookingResponse response = bookingService.getBooking(bookingId, authentication.getName());
        return ResponseEntity.ok(response);
    }

    /** GET /bookings - the caller's own bookings, most recent first. */
    @GetMapping("/bookings")
    @Operation(summary = "List the caller's own bookings", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<BookingResponse>> list(Authentication authentication) {
        List<BookingResponse> response = bookingService.listBookings(authentication.getName());
        return ResponseEntity.ok(response);
    }

    /** POST /bookings/{bookingId}/cancel - owner only. Only a PENDING booking can be cancelled. */
    @PostMapping("/bookings/{bookingId}/cancel")
    @Operation(summary = "Abandon a PENDING booking and release its seat locks now", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<BookingResponse> cancel(@PathVariable Long bookingId, Authentication authentication) {
        BookingResponse response = bookingService.cancelBooking(bookingId, authentication.getName());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /bookings/{bookingId}/qr - owner only; CONFIRMED only, else 409 via the
     * existing BookingStateException (plan/qrtickets.md section 4.3).
     * {@code Cache-Control: no-store} - a QR code is a bearer credential, it has
     * no business in a shared proxy cache. {@code size} is bounded
     * ({@code @Min(128)}/{@code @Max(1024)}, default 320) so it can't be turned
     * into a free CPU/memory amplifier.
     */
    @GetMapping(value = "/bookings/{bookingId}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render this booking's QR ticket as a PNG (owner only, CONFIRMED only)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<byte[]> qr(
            @PathVariable Long bookingId,
            @RequestParam(defaultValue = "320") @Min(128) @Max(1024) int size,
            Authentication authentication) {
        byte[] png = ticketService.qrPng(bookingId, authentication.getName(), size);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }

    /**
     * GET /bookings/{bookingId}/ticket - owner only; CONFIRMED only, else 409
     * (plan/qrtickets.md section 4.4). The printable face of a booking -
     * BookingResponse can't serve this since it only carries {@code showId}.
     */
    @GetMapping("/bookings/{bookingId}/ticket")
    @Operation(summary = "Get the printable ticket detail for a confirmed booking (owner only)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketResponse> ticket(@PathVariable Long bookingId, Authentication authentication) {
        TicketResponse response = ticketService.getTicket(bookingId, authentication.getName());
        return ResponseEntity.ok(response);
    }
}
