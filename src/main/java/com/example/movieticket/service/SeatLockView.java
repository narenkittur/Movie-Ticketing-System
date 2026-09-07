package com.example.movieticket.service;

import java.util.Collection;
import java.util.Set;

/**
 * The seam between Module 3 (this module) and Module 4 (Redis seat locking).
 *
 * claude.md's architectural principle #1 is "never infer real-time lock state from
 * the DB" - a seat's LOCKED status is an ephemeral, TTL-bound hold that only ever
 * lives in Redis (see plan/crud.md section 6). SeatService needs to know which
 * seats are currently locked in order to compute the EFFECTIVE status it returns
 * from GET /shows/{showId}/seats. Module 3 shipped the no-op {@code NoOpSeatLockView}
 * (deleted in Module 4); {@code RedisSeatLockView} is the real, Module 4
 * implementation, and swapping it in required no {@code SeatController} or DTO
 * change - only which bean gets injected.
 *
 * <p><b>Signature amended in Module 4 (plan/redis.md section 8).</b> The original
 * Module 3 signature was {@code lockedSeatIds(Long showId)}. That single-argument
 * form leaves a Redis implementation no option but {@code SCAN MATCH
 * seat_lock:<showId>:*}, which is O(the entire keyspace) on the single most-polled
 * endpoint in the app, against a single-threaded server. Supplying the candidate
 * seat ids (which {@code SeatService} has already loaded one statement earlier)
 * turns that into one exact {@code MGET}, O(seats in this show). This is a
 * knowingly-broken promise - {@code plan/crud.md} section 6 and
 * {@code logic/listing-deep-dive.md} section 2.7 both said {@code SeatService}
 * would need zero changes - traded for one line, because what the seam actually
 * had to protect (no DTO change, no controller change, no change to
 * {@code toSeatDto}'s status precedence) still holds exactly. See claude.md's
 * "Architectural Principles" section for the full writeup before reverting this.
 */
public interface SeatLockView {

    /**
     * @param showId           the show whose seats are being queried
     * @param candidateSeatIds the ids of the seats to check - callers should pass
     *                         exactly the seat ids they already have in hand, never
     *                         a broader or unrelated set
     * @return the subset of {@code candidateSeatIds} currently held by an active
     *         (unexpired) Redis lock, from ANY user - "is it mine?" is a different
     *         question (see {@code SeatLockService.myLocks}), not this one
     */
    Set<Long> lockedSeatIds(Long showId, Collection<Long> candidateSeatIds);
}
