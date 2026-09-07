package com.example.movieticket.service;

import com.example.movieticket.dto.BookingResponse;
import com.example.movieticket.model.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BookingBroadcaster (plan/websockets.md section 10, test 8): the
 * one behaviour that matters is sending to the booking OWNER's username, never to
 * a show topic or anyone else.
 */
@ExtendWith(MockitoExtension.class)
class BookingBroadcasterTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private BookingService bookingService;

    private BookingBroadcaster bookingBroadcaster;

    @BeforeEach
    void setUp() {
        bookingBroadcaster = new BookingBroadcaster(messagingTemplate, bookingService);
    }

    @Test
    void confirmed_sendsToOwnersUserQueue_notATopic() {
        BookingResponse response = BookingResponse.builder().id(1L).status("CONFIRMED").build();
        when(bookingService.getBooking(1L, "alice")).thenReturn(response);

        bookingBroadcaster.onBookingStatusChanged(new BookingStatusChangedEvent(1L, "alice", BookingStatus.CONFIRMED));

        verify(messagingTemplate).convertAndSendToUser("alice", "/queue/bookings", response);
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void bookingServiceLookupFailure_isSwallowed_neverPropagatesToCaller() {
        when(bookingService.getBooking(anyLong(), anyString())).thenThrow(new RuntimeException("booking vanished"));

        assertDoesNotThrow(() -> bookingBroadcaster.onBookingStatusChanged(
                new BookingStatusChangedEvent(1L, "alice", BookingStatus.CONFIRMED)));
    }

    @Test
    void pushFailure_isSwallowed_neverPropagatesToCaller() {
        BookingResponse response = BookingResponse.builder().id(1L).status("REFUND_PENDING").build();
        when(bookingService.getBooking(1L, "alice")).thenReturn(response);
        doThrow(new MessagingException("broker unavailable"))
                .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any(Object.class));

        assertDoesNotThrow(() -> bookingBroadcaster.onBookingStatusChanged(
                new BookingStatusChangedEvent(1L, "alice", BookingStatus.REFUND_PENDING)));
    }
}
