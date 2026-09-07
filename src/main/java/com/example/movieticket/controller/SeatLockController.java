package com.example.movieticket.controller;

import com.example.movieticket.dto.SeatLockReleaseResponse;
import com.example.movieticket.dto.SeatLockRequest;
import com.example.movieticket.dto.SeatLockResponse;
import com.example.movieticket.service.SeatLockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module 4's three endpoints (plan/redis.md section 4) - thin, per claude.md
 * principle #4, delegating everything to {@link SeatLockService}. This is the
 * first controller in the app that reads the caller's identity
 * ({@code authentication.getName()}), which SeatLockService then resolves to a
 * user id - never trusting an id from the request body (see that class's javadoc).
 */
@RestController
@Tag(name = "Seat Locking", description = "Redis-backed ephemeral seat holds - lock, release, and 'my locks'")
public class SeatLockController {

    private final SeatLockService seatLockService;

    public SeatLockController(SeatLockService seatLockService) {
        this.seatLockService = seatLockService;
    }

    /**
     * POST /shows/{showId}/seats/lock - any logged-in user. All-or-nothing: either
     * every requested seat is held by the caller when this returns, or none of
     * them are and nothing changed.
     */
    @PostMapping("/shows/{showId}/seats/lock")
    @Operation(summary = "Acquire an all-or-nothing hold on 1-10 seats", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SeatLockResponse> lock(
            @PathVariable Long showId, @Valid @RequestBody SeatLockRequest request, Authentication authentication) {
        SeatLockResponse response = seatLockService.lock(showId, request, authentication.getName());
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /shows/{showId}/seats/lock - any logged-in user. Releases seats the
     * caller holds; idempotent, always 200 (see SeatLockService.release()).
     */
    @DeleteMapping("/shows/{showId}/seats/lock")
    @Operation(summary = "Release seats you currently hold", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SeatLockReleaseResponse> release(
            @PathVariable Long showId, @Valid @RequestBody SeatLockRequest request, Authentication authentication) {
        SeatLockReleaseResponse response = seatLockService.release(showId, request, authentication.getName());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /shows/{showId}/seats/locks/mine - any logged-in user. Which seats of
     * this show the caller currently holds, and for how much longer - lets a page
     * refresh or a second tab rebuild the checkout countdown. Requires its own
     * SecurityConfig matcher declared BEFORE the existing permitAll GET rule for
     * /shows/** - see SecurityConfig's comment at that matcher.
     */
    @GetMapping("/shows/{showId}/seats/locks/mine")
    @Operation(summary = "Which seats of this show the caller currently holds", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SeatLockResponse> myLocks(@PathVariable Long showId, Authentication authentication) {
        SeatLockResponse response = seatLockService.myLocks(showId, authentication.getName());
        return ResponseEntity.ok(response);
    }
}
