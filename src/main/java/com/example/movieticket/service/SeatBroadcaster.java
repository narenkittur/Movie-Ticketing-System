package com.example.movieticket.service;

import com.example.movieticket.dto.SeatStatusUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Module 6's public fan-out (plan/websockets.md section 3.2): every
 * {@link SeatLockChangedEvent}, from whichever of its four publishers, becomes one
 * {@link SeatStatusUpdate} sent to {@code /topic/shows/{showId}/seats}. This class
 * is the entire mapping between "why the seat changed" (the event's {@code Type})
 * and "what the client is told" (the DTO's {@code status} string) - see
 * {@link #toStatus} for the one place that table lives.
 *
 * <p>A plain {@code @EventListener}, not transactional: every publisher already
 * publishes from a point that's correct to broadcast from (either genuinely
 * synchronous, like {@code lock()}/{@code release()}, or already inside an
 * {@code afterCommit} callback, like {@code releaseAfterCommit}) - see
 * {@code SeatLockChangedEvent}'s javadoc for the publisher list. A second
 * transactional wrapper here would be redundant.
 */
@Component
public class SeatBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SeatBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public SeatBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onSeatLockChanged(SeatLockChangedEvent event) {
        SeatStatusUpdate update = SeatStatusUpdate.builder()
                .showId(event.showId())
                .seatIds(event.seatIds())
                .status(toStatus(event.type()))
                .expiresAt(event.expiresAt())
                .at(LocalDateTime.now())
                .build();

        String destination = "/topic/shows/" + event.showId() + "/seats";
        try {
            messagingTemplate.convertAndSend(destination, update);
            log.debug("Broadcast {} for seats {} on show {} to {}", update.getStatus(), event.seatIds(), event.showId(), destination);
        } catch (RuntimeException ex) {
            // A failed push must never propagate into the publisher (plan/websockets.md
            // section 9-D): several of this event's publishers call publishEvent(...)
            // synchronously in the middle of a request (lock()/release()), and a plain
            // @EventListener runs inline - letting this escape would turn a stale
            // broadcast into a failed seat-lock request, which is strictly worse.
            log.error("Failed to broadcast seat status update for show {}: {}", event.showId(), ex.getMessage());
        }
    }

    /**
     * The same three literals {@code SeatDto.status}/{@code SeatService.toSeatDto}
     * use, by the same BOOKED &gt; LOCKED &gt; AVAILABLE precedence rule
     * (plan/websockets.md section 3.2) - RELEASED always means AVAILABLE here
     * because a seat that's still LOCKED by someone else never produces a RELEASED
     * event in the first place (unlock_seats.lua's compare-and-delete only reports
     * keys it actually deleted).
     */
    private String toStatus(SeatLockChangedEvent.Type type) {
        return switch (type) {
            case LOCKED -> "LOCKED";
            case RELEASED -> "AVAILABLE";
            case BOOKED -> "BOOKED";
        };
    }
}
