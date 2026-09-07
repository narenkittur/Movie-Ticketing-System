package com.example.movieticket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response body for DELETE /shows/{showId}/seats/lock. {@code releasedSeatIds} may
 * be a strict subset of the ids requested - a seat whose lock already expired, or
 * was never held by this caller, is simply not returned. That is a 200, not an
 * error: release is idempotent by design (plan/redis.md section 4).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatLockReleaseResponse {

    private Long showId;
    private List<Long> releasedSeatIds;
    private int releasedCount;
}
