package com.example.movieticket.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response body for both POST /shows/{showId}/seats/lock (the seats just acquired)
 * and GET /shows/{showId}/seats/locks/mine (the seats currently held). In the
 * "mine" case {@code secondsRemaining} is the MINIMUM across every held seat - the
 * moment the caller's selection starts breaking up, which is the only deadline a
 * checkout timer can honestly display (plan/redis.md section 4). Empty holds report
 * {@code seatIds: []}, {@code expiresAt: null}, {@code secondsRemaining: 0}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatLockResponse {

    private Long showId;
    private List<Long> seatIds;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;

    // Drives the client-side countdown.
    private long secondsRemaining;
}
