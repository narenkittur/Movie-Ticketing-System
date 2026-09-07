package com.example.movieticket.service;

import com.example.movieticket.dto.BookingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Module 6's per-user push (plan/websockets.md section 6): the payer sees their
 * booking flip {@code PENDING -> CONFIRMED} (or the two REFUND_PENDING outcomes)
 * the instant {@code BookingService.confirmPaid} commits, instead of polling
 * {@code GET /bookings/{bookingId}}.
 *
 * <p><b>{@code AFTER_COMMIT} is not negotiable</b> (section 6): a plain
 * {@code @EventListener} runs synchronously inside the publishing transaction, so
 * if {@code confirmPaid} rolled back after publishing, this would tell a browser
 * "confirmed" for a booking no {@code Booking} row actually reflects. Note the
 * asymmetry with {@link SeatBroadcaster}: the seat events are published from
 * inside an {@code afterCommit} callback already, so they're post-commit by
 * construction; {@link BookingStatusChangedEvent} is published mid-transaction and
 * needs this annotation to do the waiting.
 *
 * <p>Rebuilds the {@link BookingResponse} via {@code BookingService.getBooking}
 * rather than carrying it on the event itself - one source of truth for the
 * response shape (that method's {@code effectiveStatus}/{@code toResponse}
 * mapping), and by the time this listener runs the booking is guaranteed
 * committed and visible to a fresh read.
 */
@Component
public class BookingBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(BookingBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final BookingService bookingService;

    public BookingBroadcaster(SimpMessagingTemplate messagingTemplate, BookingService bookingService) {
        this.messagingTemplate = messagingTemplate;
        this.bookingService = bookingService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingStatusChanged(BookingStatusChangedEvent event) {
        try {
            // A fresh, separate read - bookingService is a different bean, so this
            // is a normal proxied call (not the self-invocation BookingService's own
            // javadoc warns about) and opens its own read-only transaction.
            BookingResponse response = bookingService.getBooking(event.bookingId(), event.username());
            messagingTemplate.convertAndSendToUser(event.username(), "/queue/bookings", response);
            log.info("Pushed booking {} status {} to user '{}'", event.bookingId(), event.status(), event.username());
        } catch (RuntimeException ex) {
            // Same rule as SeatBroadcaster (plan/websockets.md section 9-D): the
            // money has already moved by the time this listener runs. A failed push
            // costs a stale screen until the client's next poll/reconnect, never a
            // reason to unwind anything.
            log.error("Failed to push booking {} status change to user '{}': {}",
                    event.bookingId(), event.username(), ex.getMessage());
        }
    }
}
