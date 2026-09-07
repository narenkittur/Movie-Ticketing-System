package com.example.movieticket.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Payload for POST /admin/shows/{showId}/seats (plan/crud.md section 4.3) - a
 * compact spec that SeatService expands into individual Seat rows, rather than the
 * client having to POST every seat one at a time.
 *
 * Bounds on rows/seatsPerRow are enforced here via Bean Validation instead of a
 * bespoke exception class in the service (plan/crud.md originally sketched an
 * InvalidSeatLayoutException; @Min/@Max + the existing
 * MethodArgumentNotValidException handler in GlobalExceptionHandler already cover
 * this exact case, so a second mechanism for the same job would be redundant).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatLayoutRequest {

    // Capped at 26 so the default row-label scheme (A, B, C, ... Z) never has to
    // fall back to double letters (AA, AB, ...) - keeps generated seat numbers
    // ("A1".."Z12") predictable and human-readable.
    @NotNull(message = "Row count is required")
    @Min(value = 1, message = "Must have at least 1 row")
    @Max(value = 26, message = "Cannot exceed 26 rows (A-Z)")
    private Integer rows;

    @NotNull(message = "Seats-per-row count is required")
    @Min(value = 1, message = "Must have at least 1 seat per row")
    @Max(value = 50, message = "Cannot exceed 50 seats per row")
    private Integer seatsPerRow;

    // Optional explicit row labels (e.g. ["A","B","C"]); when omitted, SeatService
    // generates A, B, C... by row index. If provided, its size must equal `rows` -
    // checked in SeatService.generateLayout() rather than here, since cross-field
    // validation (comparing this list's size against another field) needs a custom
    // Bean Validation constraint for no real benefit at this scale.
    private List<String> rowLabels;
}
