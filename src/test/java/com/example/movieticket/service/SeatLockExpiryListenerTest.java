package com.example.movieticket.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SeatLockExpiryListener (plan/websockets.md section 10, test 7):
 * turns a Redis {@code __keyevent@0__:expired} notification into a
 * {@code SeatLockChangedEvent(RELEASED)}, and ignores any expired key that isn't
 * one of ours.
 */
@ExtendWith(MockitoExtension.class)
class SeatLockExpiryListenerTest {

    @Mock private ApplicationEventPublisher eventPublisher;

    private SeatLockExpiryListener listener;

    @BeforeEach
    void setUp() {
        listener = new SeatLockExpiryListener(eventPublisher);
    }

    private DefaultMessage expiredKeyMessage(String key) {
        return new DefaultMessage("__keyevent@0__:expired".getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void seatLockKeyExpiring_publishesReleasedForThatShowAndSeat() {
        listener.onMessage(expiredKeyMessage("seat_lock:12:101"), null);

        verify(eventPublisher).publishEvent(argThat((SeatLockChangedEvent e) ->
                e.showId().equals(12L)
                        && e.seatIds().equals(List.of(101L))
                        && e.type() == SeatLockChangedEvent.Type.RELEASED));
    }

    @Test
    void nonSeatLockKeyExpiring_isIgnored() {
        listener.onMessage(expiredKeyMessage("refresh_token:some-hash"), null);

        verifyNoInteractions(eventPublisher);
    }
}
