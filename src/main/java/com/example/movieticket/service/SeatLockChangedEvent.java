package com.example.movieticket.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Published whenever a lock is acquired, released, or superseded by a sale
 * (plan/redis.md section 11.2 planted this seam; Module 6 - plan/websockets.md
 * section 4 - is the listener). {@code SeatBroadcaster} fans every event of this
 * type out to {@code /topic/shows/{showId}/seats}.
 *
 * <p>Three publishers today: {@link SeatLockService#lock} (LOCKED),
 * {@link SeatLockService#release} (RELEASED), and
 * {@link SeatLockService#releaseAfterCommit} (BOOKED or RELEASED, depending on
 * why the caller released - see that method's javadoc for why a single RELEASED
 * type would be wrong for a just-sold seat). {@link SeatLockExpiryListener} is a
 * fourth, publishing RELEASED from Redis keyspace notifications rather than
 * application code (plan/websockets.md section 5.1).
 *
 * <p>{@code expiresAt} is non-null only for {@code LOCKED} - it's the client
 * countdown target plan/websockets.md section 3.2/5.2 rely on, read straight from
 * {@code lock_seats.lua}'s own return value at the one call site that publishes
 * LOCKED ({@link SeatLockService#lock}), rather than re-derived with a second
 * Redis round trip in the broadcaster. Every other publisher leaves it null.
 *
 * <p><b>Honest caveat carried over from Module 4:</b> a lock that ends by TTL
 * expiry and is never actively observed by anything produces NO event through
 * this path - {@link SeatLockExpiryListener} is best-effort (fire-and-forget
 * Redis pub/sub, plan/websockets.md section 5.1's caveat), not a guarantee. The
 * client-side countdown (plan/websockets.md section 5.2) is the correctness
 * floor this event can never fully replace.
 */
public record SeatLockChangedEvent(Long showId, List<Long> seatIds, Type type, LocalDateTime expiresAt) {

    public enum Type { LOCKED, RELEASED, BOOKED }
}
