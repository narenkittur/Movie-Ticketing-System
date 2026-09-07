package com.example.movieticket.service;

import com.example.movieticket.dto.SeatStatusUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SeatBroadcaster's mapping table (plan/websockets.md section 10,
 * tests 1-2) - the whole logic of this class is "which SeatLockChangedEvent.Type
 * becomes which status string", so all three rows are tested.
 */
@ExtendWith(MockitoExtension.class)
class SeatBroadcasterTest {

    @Mock private SimpMessagingTemplate messagingTemplate;

    private SeatBroadcaster seatBroadcaster;

    @BeforeEach
    void setUp() {
        seatBroadcaster = new SeatBroadcaster(messagingTemplate);
    }

    @Test
    void locked_broadcastsToShowTopic_withLockedStatus_andExpiresAt() {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);
        seatBroadcaster.onSeatLockChanged(new SeatLockChangedEvent(12L, List.of(101L, 102L), SeatLockChangedEvent.Type.LOCKED, expiresAt));

        ArgumentCaptor<SeatStatusUpdate> payload = ArgumentCaptor.forClass(SeatStatusUpdate.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/shows/12/seats"), payload.capture());
        assertEquals("LOCKED", payload.getValue().getStatus());
        assertEquals(List.of(101L, 102L), payload.getValue().getSeatIds());
        assertEquals(expiresAt, payload.getValue().getExpiresAt());
    }

    @Test
    void released_broadcastsAvailableStatus() {
        seatBroadcaster.onSeatLockChanged(new SeatLockChangedEvent(12L, List.of(101L), SeatLockChangedEvent.Type.RELEASED, null));

        ArgumentCaptor<SeatStatusUpdate> payload = ArgumentCaptor.forClass(SeatStatusUpdate.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/shows/12/seats"), payload.capture());
        assertEquals("AVAILABLE", payload.getValue().getStatus());
        assertNull(payload.getValue().getExpiresAt());
    }

    @Test
    void booked_broadcastsBookedStatus() {
        seatBroadcaster.onSeatLockChanged(new SeatLockChangedEvent(12L, List.of(101L), SeatLockChangedEvent.Type.BOOKED, null));

        ArgumentCaptor<SeatStatusUpdate> payload = ArgumentCaptor.forClass(SeatStatusUpdate.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/shows/12/seats"), payload.capture());
        assertEquals("BOOKED", payload.getValue().getStatus());
    }

    @Test
    void broadcastFailure_isSwallowed_neverPropagatesToCaller() {
        doThrow(new MessagingException("broker unavailable"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        assertDoesNotThrow(() -> seatBroadcaster.onSeatLockChanged(
                new SeatLockChangedEvent(12L, List.of(101L), SeatLockChangedEvent.Type.LOCKED, LocalDateTime.now())));
    }
}
