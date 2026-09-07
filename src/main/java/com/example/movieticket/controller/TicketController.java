package com.example.movieticket.controller;

import com.example.movieticket.dto.TicketValidationRequest;
import com.example.movieticket.dto.TicketValidationResponse;
import com.example.movieticket.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module 7's staff-facing gate surface (plan/qrtickets.md section 6). Split by
 * AUDIENCE from BookingController's owner-scoped {@code /bookings/{id}/qr} and
 * {@code /bookings/{id}/ticket}: this stays the one small, auditable admin file.
 * Under {@code /admin/**}, so no new SecurityConfig matcher is needed at all
 * (section 5.5/7.1) - the coarse {@code /admin/**} URL rule already there is
 * layer one; {@code @PreAuthorize} below is layer two, the same two-layer gate
 * Module 3 established for every admin controller.
 */
@RestController
@Tag(name = "Ticket Gate", description = "Admin-only QR ticket redemption at the door")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * POST /admin/tickets/validate - ROLE_ADMIN only. ALWAYS 200 for a scan the
     * server actually processed, with an explicit {@code result} field
     * distinguishing VALID / ALREADY_USED / NOT_CONFIRMED / OUTSIDE_WINDOW /
     * NOT_FOUND (plan/qrtickets.md section 5.4) - a deliberate deviation from
     * this project's usual exception-to-status-code convention via
     * GlobalExceptionHandler. The caller is a turnstile that must do three
     * different things depending on which of those it got back; genuine failures
     * (bad/missing JWT, non-admin caller, blank code, dead database) still throw
     * and still map through GlobalExceptionHandler normally.
     */
    @PostMapping("/admin/tickets/validate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Scan a QR ticket at the gate - always 200, see the result field", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketValidationResponse> validate(
            @Valid @RequestBody TicketValidationRequest request, Authentication authentication) {
        TicketValidationResponse response = ticketService.validate(request.getCode(), authentication.getName());
        return ResponseEntity.ok(response);
    }
}
