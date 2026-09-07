package com.example.movieticket.controller;

import com.example.movieticket.dto.SeatLayoutRequest;
import com.example.movieticket.dto.SeatLayoutResponse;
import com.example.movieticket.service.SeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seat half of Module 3 (plan/crud.md endpoints #4, #7) - the most
 * architecturally sensitive pair in this module. GET /shows/{showId}/seats is
 * where claude.md's "never infer real-time lock state from the DB" principle is
 * actually enforced; see SeatService's javadoc and plan/crud.md section 6.
 */
@RestController
@Tag(name = "Seats", description = "Admin seat-layout generation and the public live seat map")
public class SeatController {

    private final SeatService seatService;

    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    /**
     * POST /admin/shows/{showId}/seats - admin only, one-time per show. Calling
     * this twice for the same show is rejected (409) rather than silently
     * doubling the seat inventory - see SeatService.generateLayout().
     */
    @PostMapping("/admin/shows/{showId}/seats")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Generate the seat layout for a show", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SeatLayoutResponse> generateSeatLayout(
            @PathVariable Long showId, @Valid @RequestBody SeatLayoutRequest request) {
        SeatLayoutResponse response = seatService.generateLayout(showId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /shows/{showId}/seats - public. Every seat's status here is the
     * EFFECTIVE status (DB state overlaid with the Redis lock view), never a raw
     * read of the seats table - see SeatService.getSeatLayout().
     */
    @GetMapping("/shows/{showId}/seats")
    @Operation(summary = "Get the live seat layout for a show")
    public ResponseEntity<SeatLayoutResponse> getSeatLayout(@PathVariable Long showId) {
        SeatLayoutResponse response = seatService.getSeatLayout(showId);
        return ResponseEntity.ok(response);
    }
}
