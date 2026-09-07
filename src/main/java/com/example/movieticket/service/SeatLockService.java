package com.example.movieticket.service;

import com.example.movieticket.dto.SeatLockReleaseResponse;
import com.example.movieticket.dto.SeatLockRequest;
import com.example.movieticket.dto.SeatLockResponse;
import com.example.movieticket.exception.SeatLockExpiredException;
import com.example.movieticket.exception.SeatLockUnavailableException;
import com.example.movieticket.exception.SeatNotFoundException;
import com.example.movieticket.exception.SeatUnavailableException;
import com.example.movieticket.exception.ShowNotFoundException;
import com.example.movieticket.model.Seat;
import com.example.movieticket.model.User;
import com.example.movieticket.repository.SeatRepository;
import com.example.movieticket.repository.ShowRepository;
import com.example.movieticket.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Module 4's business logic (plan/redis.md sections 5-6): acquiring/releasing
 * short-lived, exclusive, self-expiring seat holds in Redis, and reporting which
 * seats of a show the caller currently holds. This service never writes to the
 * {@code seats} table at all - see plan/redis.md section 8 for why the durable
 * DB status and the ephemeral Redis lock state are deliberately two different
 * sources of truth.
 *
 * <p>Also home to the two hooks Module 5 (booking) will call, so the commit-then-
 * release ordering claude.md fixes is inherited rather than re-derived: see
 * {@link #assertHoldsAll} and {@link #releaseAfterCommit}.
 */
@Service
public class SeatLockService {

    private static final Logger log = LoggerFactory.getLogger(SeatLockService.class);

    // Matches SeatService's own literal - see that class for why these aren't
    // enums yet.
    private static final String STATUS_BOOKED = "BOOKED";

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> lockSeatsScript;
    private final DefaultRedisScript<List> unlockSeatsScript;
    private final ApplicationEventPublisher eventPublisher;

    // claude.md fixes this at 300s; the property exists so a deployment can tune
    // it without a recompile, not as an invitation to change it casually
    // (application.properties' own comment repeats this).
    @Value("${seatlock.ttl-seconds}")
    private long ttlSeconds;

    public SeatLockService(ShowRepository showRepository,
                            SeatRepository seatRepository,
                            UserRepository userRepository,
                            StringRedisTemplate redisTemplate,
                            DefaultRedisScript<List> lockSeatsScript,
                            DefaultRedisScript<List> unlockSeatsScript,
                            ApplicationEventPublisher eventPublisher) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.lockSeatsScript = lockSeatsScript;
        this.unlockSeatsScript = unlockSeatsScript;
        this.eventPublisher = eventPublisher;
    }

    /**
     * POST /shows/{showId}/seats/lock. See plan/redis.md section 6.1 for the full
     * step-by-step flowchart this method follows.
     */
    @Transactional(readOnly = true)
    public SeatLockResponse lock(Long showId, SeatLockRequest request, String username) {
        Long userId = resolveUserId(username);

        if (!showRepository.existsById(showId)) {
            throw new ShowNotFoundException("No show found with id " + showId);
        }

        // De-dup while preserving request order, so a caller who accidentally
        // repeats an id doesn't get double-counted against the 10-seat cap logic
        // or produce a key list with duplicate entries.
        List<Long> requestedIds = List.copyOf(new LinkedHashSet<>(request.getSeatIds()));

        List<Seat> seats = seatRepository.findByIdInAndShowId(requestedIds, showId);
        if (seats.size() != requestedIds.size()) {
            throw new SeatNotFoundException("One or more seat ids do not belong to show " + showId);
        }

        // DB-truth fast path, deliberately BEFORE touching Redis: there's no point
        // taking an ephemeral hold on a seat that is permanently sold. The
        // TOCTOU window between this check and the script below is acknowledged,
        // not hidden - see plan/redis.md section 6.1's note on it.
        List<String> alreadyBooked = seats.stream()
                .filter(seat -> STATUS_BOOKED.equals(seat.getStatus()))
                .map(seat -> "seat " + seat.getId() + " is already booked")
                .toList();
        if (!alreadyBooked.isEmpty()) {
            throw new SeatUnavailableException("One or more seats are no longer available", alreadyBooked);
        }

        List<String> keys = requestedIds.stream().map(id -> SeatLockKeys.key(showId, id)).toList();

        List<?> result;
        try {
            result = redisTemplate.execute(lockSeatsScript, keys, String.valueOf(userId), String.valueOf(ttlSeconds));
        } catch (DataAccessException ex) {
            log.error("Redis lock_seats.lua failed for show id={} seats={}: {}", showId, requestedIds, ex.getMessage());
            throw new SeatLockUnavailableException("Seat locking is temporarily unavailable");
        }

        long first = ((Number) result.get(0)).longValue();
        if (first == -1) {
            List<String> conflicts = result.stream()
                    .skip(1)
                    .map(seatId -> "seat " + seatId + " is held by another user")
                    .toList();
            log.warn("User {} was denied seats {} on show {} (held by others)", userId, requestedIds, showId);
            throw new SeatUnavailableException("One or more seats are no longer available", conflicts);
        }

        long secondsRemaining = first / 1000;
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(secondsRemaining);
        log.info("User {} locked seats {} on show {} for {}s", userId, requestedIds, showId, secondsRemaining);
        eventPublisher.publishEvent(new SeatLockChangedEvent(showId, requestedIds, SeatLockChangedEvent.Type.LOCKED, expiresAt));

        return SeatLockResponse.builder()
                .showId(showId)
                .seatIds(requestedIds)
                .expiresAt(expiresAt)
                .secondsRemaining(secondsRemaining)
                .build();
    }

    /**
     * DELETE /shows/{showId}/seats/lock. Idempotent by design: releasing a seat
     * you don't (or no longer) hold is a no-op, not an error - see plan/redis.md
     * section 6.2. No ownership pre-check in Java; {@code unlock_seats.lua} IS the
     * ownership check, and doing it as two round trips would reintroduce the
     * expiry race described in plan/redis.md section 3.
     */
    public SeatLockReleaseResponse release(Long showId, SeatLockRequest request, String username) {
        Long userId = resolveUserId(username);

        if (!showRepository.existsById(showId)) {
            throw new ShowNotFoundException("No show found with id " + showId);
        }

        List<Long> requestedIds = List.copyOf(new LinkedHashSet<>(request.getSeatIds()));
        List<String> keys = requestedIds.stream().map(id -> SeatLockKeys.key(showId, id)).toList();

        List<?> result;
        try {
            result = redisTemplate.execute(unlockSeatsScript, keys, String.valueOf(userId));
        } catch (DataAccessException ex) {
            log.error("Redis unlock_seats.lua failed for show id={} seats={}: {}", showId, requestedIds, ex.getMessage());
            throw new SeatLockUnavailableException("Seat locking is temporarily unavailable");
        }

        List<Long> released = result.stream().map(seatId -> ((Number) seatId).longValue()).toList();
        log.info("User {} released {} of {} requested seats on show {}",
                userId, released.size(), requestedIds.size(), showId);

        if (!released.isEmpty()) {
            eventPublisher.publishEvent(new SeatLockChangedEvent(showId, released, SeatLockChangedEvent.Type.RELEASED, null));
        }

        return SeatLockReleaseResponse.builder()
                .showId(showId)
                .releasedSeatIds(released)
                .releasedCount(released.size())
                .build();
    }

    /**
     * GET /shows/{showId}/seats/locks/mine (plan/redis.md section 6.3). No Lua
     * needed here - this reads, it never mutates, so atomicity buys nothing. A key
     * that expires between the {@code MGET} and the {@code PTTL} below is simply
     * dropped from the response rather than reported with a bogus negative TTL -
     * the honest answer.
     */
    @Transactional(readOnly = true)
    public SeatLockResponse myLocks(Long showId, String username) {
        Long userId = resolveUserId(username);

        if (!showRepository.existsById(showId)) {
            throw new ShowNotFoundException("No show found with id " + showId);
        }

        List<Long> candidateIds = seatRepository.findByShowId(showId).stream().map(Seat::getId).toList();
        if (candidateIds.isEmpty()) {
            return emptyLockResponse(showId);
        }

        List<String> keys = candidateIds.stream().map(id -> SeatLockKeys.key(showId, id)).toList();

        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
        } catch (DataAccessException ex) {
            log.error("Redis MGET failed while resolving locks/mine for show id={}: {}", showId, ex.getMessage());
            throw new SeatLockUnavailableException("Seat lock state is temporarily unavailable");
        }

        String userIdStr = String.valueOf(userId);
        List<Long> myIds = new ArrayList<>();
        List<String> myKeys = new ArrayList<>();
        if (values != null) {
            for (int i = 0; i < candidateIds.size(); i++) {
                if (userIdStr.equals(values.get(i))) {
                    myIds.add(candidateIds.get(i));
                    myKeys.add(keys.get(i));
                }
            }
        }

        if (myIds.isEmpty()) {
            return emptyLockResponse(showId);
        }

        // One pipelined round trip for every PTTL, not one call per seat.
        List<Object> pttlResults;
        try {
            pttlResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String key : myKeys) {
                    connection.keyCommands().pTtl(key.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
        } catch (DataAccessException ex) {
            log.error("Redis PTTL pipeline failed while resolving locks/mine for show id={}: {}", showId, ex.getMessage());
            throw new SeatLockUnavailableException("Seat lock state is temporarily unavailable");
        }

        List<Long> stillLocked = new ArrayList<>();
        long minPttl = Long.MAX_VALUE;
        for (int i = 0; i < myIds.size(); i++) {
            long pttl = ((Number) pttlResults.get(i)).longValue();
            // -2 means the key no longer exists (expired between the MGET and this
            // PTTL) - drop it rather than let a negative value win the minimum.
            if (pttl >= 0) {
                stillLocked.add(myIds.get(i));
                minPttl = Math.min(minPttl, pttl);
            }
        }

        if (stillLocked.isEmpty()) {
            return emptyLockResponse(showId);
        }

        long secondsRemaining = minPttl / 1000;
        return SeatLockResponse.builder()
                .showId(showId)
                .seatIds(stillLocked)
                .expiresAt(LocalDateTime.now().plusSeconds(secondsRemaining))
                .secondsRemaining(secondsRemaining)
                .build();
    }

    /**
     * Module 5 hook #1 (plan/redis.md section 11.1). Call INSIDE the
     * {@code @Transactional} booking method, after re-reading the seat rows, to
     * prove the caller still holds every seat before the booking commits. The DB
     * re-check is the backstop; this lock check is the primary gate - neither
     * replaces the other.
     *
     * @throws SeatLockExpiredException (409) unless EVERY seat is currently held by {@code userId}
     */
    public void assertHoldsAll(Long showId, Collection<Long> seatIds, Long userId) {
        List<String> keys = seatIds.stream().map(id -> SeatLockKeys.key(showId, id)).toList();

        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
        } catch (DataAccessException ex) {
            log.error("Redis MGET failed in assertHoldsAll for show id={} seats={}: {}", showId, seatIds, ex.getMessage());
            throw new SeatLockUnavailableException("Seat lock state is temporarily unavailable");
        }

        String userIdStr = String.valueOf(userId);
        boolean holdsAll = values != null && !values.isEmpty() && values.stream().allMatch(userIdStr::equals);
        if (!holdsAll) {
            throw new SeatLockExpiredException(
                    "You no longer hold every seat you're trying to book on show " + showId);
        }
    }

    /**
     * Module 5 hook #2 (plan/redis.md section 11.1). Safe to call INSIDE the
     * booking transaction: registers an {@code afterCommit} callback, so locks are
     * released only once the booking has actually committed - never before (see
     * claude.md's "Booking confirmation order is fixed"). Releasing before commit
     * would leave a window where the seat is unlocked in Redis but not yet
     * {@code BOOKED} in MySQL, letting another user lock it mid-payment. If the
     * transaction rolls back, this callback never fires and the locks stand until
     * their TTL - correct, since the user may retry payment.
     *
     * <p><b>Module 6 addition (plan/websockets.md section 4.2):</b> this method is
     * called from two opposite outcomes - {@code BookingService.confirmPaid} (the
     * seats were just SOLD) and {@code BookingService.cancelBooking} (the seats
     * were genuinely freed) - and both execute the exact same unlock script. Before
     * this parameter existed, neither call published anything, so a sold seat
     * broadcast nothing at all. {@code resultingStatus} is required (no default)
     * so a caller cannot forget which of the two meanings applies; this is the ONE
     * publisher for this transition, deliberately not a second, parallel publish
     * from {@code BookingService} (see the plan section's rejected-alternative
     * note on why two publishers for one transition is a race).
     *
     * <p>The two statuses are published under different conditions
     * (plan/websockets.md section 4.3) - read the {@code afterCommit} body below
     * before changing this method:
     * <ul>
     *   <li>{@code BOOKED}: published unconditionally, even if the unlock script
     *       throws. The booking already committed in MySQL regardless of whether
     *       Redis heard about it, so the seats ARE sold; the stale lock (if the
     *       unlock failed) just outlives the sale until its own TTL.</li>
     *   <li>{@code RELEASED}: published only if the unlock script succeeds.
     *       Broadcasting AVAILABLE when the lock might still stand would be a lie -
     *       {@code SeatLockExpiryListener} will announce the truth when the key
     *       actually dies.</li>
     * </ul>
     * A publish failure must never escape this callback - same reason the Redis
     * failure itself is swallowed here: the transaction has already committed and
     * throwing post-commit cannot undo anything.
     */
    public void releaseAfterCommit(Long showId, Collection<Long> seatIds, Long userId,
                                    SeatLockChangedEvent.Type resultingStatus) {
        List<String> keys = seatIds.stream().map(id -> SeatLockKeys.key(showId, id)).toList();
        List<Long> seatIdList = List.copyOf(seatIds);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                boolean unlockSucceeded = false;
                try {
                    redisTemplate.execute(unlockSeatsScript, keys, String.valueOf(userId));
                    unlockSucceeded = true;
                    log.info("Released locks for seats {} on show {} after booking commit", seatIds, showId);
                } catch (DataAccessException ex) {
                    // The booking already committed - a failure here just means the
                    // lock outlives the sale by up to the TTL, which self-corrects
                    // (plan/redis.md section 2). Never let this escape: throwing
                    // post-commit can't undo anything and would only confuse the
                    // caller of the (already-succeeded) booking.
                    log.error("Failed to release locks for seats {} on show {} after commit: {}",
                            seatIds, showId, ex.getMessage());
                }

                try {
                    if (resultingStatus == SeatLockChangedEvent.Type.BOOKED) {
                        // Unconditional: the seats are sold in MySQL either way.
                        eventPublisher.publishEvent(new SeatLockChangedEvent(showId, seatIdList, SeatLockChangedEvent.Type.BOOKED, null));
                    } else if (unlockSucceeded) {
                        // Conditional: only claim AVAILABLE once Redis has actually
                        // forgotten the lock.
                        eventPublisher.publishEvent(new SeatLockChangedEvent(showId, seatIdList, SeatLockChangedEvent.Type.RELEASED, null));
                    }
                } catch (RuntimeException ex) {
                    // A broadcast failure is a stale screen, not a data-integrity
                    // problem - never let it propagate out of an afterCommit hook
                    // (plan/websockets.md section 9-D).
                    log.error("Failed to publish SeatLockChangedEvent for seats {} on show {}: {}",
                            seatIds, showId, ex.getMessage());
                }
            }
        });
    }

    /**
     * Resolves the caller's id from their JWT-derived username - never from the
     * request body (plan/authentication.md section 7; see plan/redis.md section
     * 6.1 step 1). A client-supplied user id would let anyone release anyone
     * else's locks.
     */
    private Long resolveUserId(String username) {
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user '" + username + "' has no matching row in the users table"));
    }

    private SeatLockResponse emptyLockResponse(Long showId) {
        return SeatLockResponse.builder()
                .showId(showId)
                .seatIds(List.of())
                .expiresAt(null)
                .secondsRemaining(0)
                .build();
    }
}
