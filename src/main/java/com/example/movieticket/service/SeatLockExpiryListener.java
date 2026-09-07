package com.example.movieticket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Hole #2's server-side answer (plan/websockets.md section 5.1): a
 * {@code RedisMessageListenerContainer} (see {@code RedisConfig}) delivers every
 * {@code __keyevent@0__:expired} notification here, and this class turns the ones
 * that are seat locks into {@link SeatLockChangedEvent}{@code (RELEASED)} - the
 * one lock-ending path that, until this module, produced no event at all (TTL
 * expiry, per {@code SeatLockChangedEvent}'s original javadoc).
 *
 * <p>Requires {@code docker-compose.yml}'s Redis service to run with
 * {@code --notify-keyspace-events Kx} - see that file's comment. Gated by
 * {@code websocket.expiry-notifications.enabled} (default {@code true}) since
 * this depends on Redis SERVER configuration the application cannot assert or
 * enable at startup; a deployment against a managed Redis with notifications off
 * can turn this listener off rather than run one that silently never fires.
 *
 * <p><b>Honest caveat (plan/websockets.md section 5.1):</b> keyspace
 * notifications are fire-and-forget pub/sub - no delivery guarantee, nothing
 * buffered for a subscriber that's reconnecting, and Redis emits {@code expired}
 * only when a key is actively expired (touched by a client or swept by the
 * background cycle), which can lag the nominal TTL by a beat. This is a
 * best-effort optimization, never a source of truth - the client-side countdown
 * (section 5.2) is the correctness floor this listener exists to make merely
 * prompt, not correct.
 */
@Component
@ConditionalOnProperty(name = "websocket.expiry-notifications.enabled", havingValue = "true", matchIfMissing = true)
public class SeatLockExpiryListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(SeatLockExpiryListener.class);

    private final ApplicationEventPublisher eventPublisher;

    public SeatLockExpiryListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * For a {@code __keyevent@<db>__:expired} channel, Redis puts the expired
     * key's NAME (not a value - the key is already gone) in the message body.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody(), StandardCharsets.UTF_8);

        if (!SeatLockKeys.isSeatLockKey(expiredKey)) {
            return; // Some other key in the same Redis DB expired - not ours.
        }

        try {
            Long showId = SeatLockKeys.showIdFromKey(expiredKey);
            Long seatId = SeatLockKeys.seatIdFromKey(expiredKey);
            log.info("Seat lock {} expired by TTL - publishing RELEASED for seat {} on show {}", expiredKey, seatId, showId);
            eventPublisher.publishEvent(new SeatLockChangedEvent(showId, List.of(seatId), SeatLockChangedEvent.Type.RELEASED, null));
        } catch (RuntimeException ex) {
            // A malformed key or a broadcast failure here must not take down the
            // Redis listener container - same "never propagate" rule as every
            // other listener in this module (plan/websockets.md section 9-D).
            log.error("Failed to process expiry notification for key {}: {}", expiredKey, ex.getMessage());
        }
    }
}
