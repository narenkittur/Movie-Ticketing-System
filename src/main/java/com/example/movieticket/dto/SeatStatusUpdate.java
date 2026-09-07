package com.example.movieticket.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Module 6's broadcast delta (plan/websockets.md section 3.2), published to
 * {@code /topic/shows/{showId}/seats} by {@code SeatBroadcaster} every time
 * {@link com.example.movieticket.service.SeatLockChangedEvent} fires. Deliberately
 * a delta, not a full {@link SeatLayoutResponse} snapshot - see that section for
 * why (a Redis MGET + a DB read on every lock event, on the busiest write path in
 * the app, is too expensive to pay per broadcast).
 *
 * <p>{@code status} uses the SAME three string literals {@link SeatDto#getStatus()}
 * already carries, computed by the same BOOKED &gt; LOCKED &gt; DB-status precedence
 * {@code SeatService.toSeatDto} enforces - a client applies this by overwriting
 * {@code status} on the matching seat in its existing {@link SeatLayoutResponse},
 * with no separate translation table between "what REST says" and "what the
 * socket says".
 *
 * <p><b>The client's contract</b> (plan/websockets.md section 3.2, restated here
 * since it's load-bearing): on connect and on every reconnect, fetch
 * {@code GET /shows/{showId}/seats} for a baseline BEFORE trusting any delta - this
 * stream is an optimization over that endpoint, never a replacement for it. On a
 * LOCKED delta, start a countdown to {@code expiresAt}; at zero, re-fetch the
 * baseline rather than trusting that a RELEASED delta will arrive (plan/websockets.md
 * section 5.2 - Redis keyspace notifications are best-effort, not guaranteed).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatStatusUpdate {

    private Long showId;
    private List<Long> seatIds;

    // One of "AVAILABLE" / "LOCKED" / "BOOKED" - see class javadoc.
    private String status;

    // Non-null only when status == "LOCKED"; the instant the client should re-fetch
    // the baseline if no further delta has arrived by then (plan/websockets.md
    // section 5.2).
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime at;
}
