package com.example.movieticket.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body for both POST /shows/{showId}/seats/lock and DELETE /shows/{showId}/seats/lock
 * (plan/redis.md section 4). The 10-seat cap is deliberately a compile-time
 * annotation constant, not a {@code @Value}-injected property - Bean Validation
 * needs a literal, and one source of truth (this annotation, recorded in claude.md)
 * beats an annotation and a service-layer check that could silently drift apart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatLockRequest {

    @NotEmpty(message = "seatIds must contain at least one seat id")
    @Size(max = 10, message = "cannot lock more than 10 seats in a single request")
    private List<Long> seatIds;
}
