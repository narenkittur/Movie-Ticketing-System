package com.example.movieticket.service;

import com.example.movieticket.exception.SeatLockUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Module 4's real implementation of the {@link SeatLockView} seam (plan/redis.md
 * section 8) - the only {@code SeatLockView} bean on the context now that
 * {@code NoOpSeatLockView} has been deleted.
 *
 * <p>One exact {@code MGET} over the candidate seat ids, O(seats in this show) -
 * see {@link SeatLockView}'s javadoc for why this needed the seam's signature to
 * carry the candidate ids rather than falling back to a Redis {@code SCAN}.
 */
@Component
public class RedisSeatLockView implements SeatLockView {

    private static final Logger log = LoggerFactory.getLogger(RedisSeatLockView.class);

    private final StringRedisTemplate redisTemplate;

    public RedisSeatLockView(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Set<Long> lockedSeatIds(Long showId, Collection<Long> candidateSeatIds) {
        if (candidateSeatIds.isEmpty()) {
            return Set.of();
        }

        List<Long> ids = List.copyOf(candidateSeatIds);
        List<String> keys = ids.stream().map(id -> SeatLockKeys.key(showId, id)).toList();

        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys); // one MGET
        } catch (DataAccessException ex) {
            // Fail closed (claude.md): a Redis failure here must NEVER be read as
            // "nothing is locked". GET /shows/{showId}/seats propagates this all
            // the way out as a 503 rather than silently reporting locked seats as
            // AVAILABLE - see plan/redis.md section 8's "this path fails closed too".
            log.error("Redis MGET failed while computing locked seats for show id={}: {}", showId, ex.getMessage());
            throw new SeatLockUnavailableException("Seat lock state is temporarily unavailable");
        }

        Set<Long> locked = new HashSet<>();
        if (values != null) {
            for (int i = 0; i < ids.size(); i++) {
                // index-aligned: values.get(i) is the holder of ids.get(i), or null
                // if free. We do NOT compare to any particular caller here - ANY
                // holder means LOCKED to everyone. "is it mine?" is
                // SeatLockService.myLocks()'s question, not this one.
                if (values.get(i) != null) {
                    locked.add(ids.get(i));
                }
            }
        }
        return locked;
    }
}
