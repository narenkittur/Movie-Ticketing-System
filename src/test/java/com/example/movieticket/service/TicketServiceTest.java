package com.example.movieticket.service;

import com.example.movieticket.dto.TicketResponse;
import com.example.movieticket.dto.TicketValidationResponse;
import com.example.movieticket.exception.BookingNotFoundException;
import com.example.movieticket.exception.BookingStateException;
import com.example.movieticket.model.Booking;
import com.example.movieticket.model.BookingSeat;
import com.example.movieticket.model.BookingStatus;
import com.example.movieticket.model.Movie;
import com.example.movieticket.model.Seat;
import com.example.movieticket.model.Show;
import com.example.movieticket.model.TicketValidationResult;
import com.example.movieticket.model.User;
import com.example.movieticket.repository.BookingRepository;
import com.example.movieticket.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TicketService's business rules, every collaborator mocked -
 * same shape as BookingServiceTest. Follows plan/qrtickets.md section 8's
 * scenario list (tests 1-8); test 9 (the concurrent double-scan race) needs a
 * real MySQL honouring SELECT ... FOR UPDATE and is deliberately not here - see
 * claude.md's Known Gaps (Open Decision C, deferred alongside Modules 4-6).
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private QrCodeGenerator qrCodeGenerator;

    private TicketService ticketService;

    private static final User ALICE = User.builder().id(7L).username("alice").email("alice@example.com").role("ROLE_USER").build();
    private static final User GATE_STAFF = User.builder().id(99L).username("gate1").email("gate1@example.com").role("ROLE_ADMIN").build();
    private static final Movie MOVIE = Movie.builder().id(1L).title("Dune").durationMinutes(150).build();

    @BeforeEach
    void setUp() {
        ticketService = new TicketService(bookingRepository, userRepository, qrCodeGenerator);
        ReflectionTestUtils.setField(ticketService, "checkinOpensMinutesBefore", 60L);

        lenient().when(userRepository.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        lenient().when(userRepository.findByUsername("gate1")).thenReturn(Optional.of(GATE_STAFF));
    }

    private Show showAt(LocalDateTime startTime) {
        return Show.builder().id(12L).startTime(startTime).screenName("Screen 1")
                .price(new BigDecimal("200.00")).movie(MOVIE).build();
    }

    private Booking confirmedBooking(Show show) {
        Seat seat = Seat.builder().id(101L).seatNumber("A1").status("BOOKED").show(show).build();
        return Booking.builder().id(5L).show(show).user(ALICE).status(BookingStatus.CONFIRMED)
                .bookingReference("ref-5").totalPrice(new BigDecimal("200.00"))
                .bookingSeats(List.of(BookingSeat.builder().seat(seat).build()))
                .build();
    }

    // --- validate ---

    @Test
    void validate_firstScan_returnsValidAndStampsCheckedInAt() {
        Booking booking = confirmedBooking(showAt(LocalDateTime.now().minusMinutes(10))); // inside window
        when(bookingRepository.findWithLockByBookingReference("ref-5")).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketValidationResponse response = ticketService.validate("ref-5", "gate1");

        assertEquals(TicketValidationResult.VALID, response.getResult());
        assertNotNull(booking.getCheckedInAt());
        assertEquals(99L, booking.getCheckedInBy());
        assertEquals(booking.getCheckedInAt(), response.getCheckedInAt());
        assertEquals(List.of("A1"), response.getSeats());
        verify(bookingRepository).save(booking);
    }

    @Test
    void validate_secondScan_returnsAlreadyUsed_withOriginalTimestamp() {
        Booking booking = confirmedBooking(showAt(LocalDateTime.now().minusMinutes(10)));
        LocalDateTime firstScan = LocalDateTime.now().minusMinutes(2);
        booking.setCheckedInAt(firstScan);
        booking.setCheckedInBy(88L);
        when(bookingRepository.findWithLockByBookingReference("ref-5")).thenReturn(Optional.of(booking));

        TicketValidationResponse response = ticketService.validate("ref-5", "gate1");

        assertEquals(TicketValidationResult.ALREADY_USED, response.getResult());
        assertEquals(firstScan, response.getCheckedInAt());
        assertEquals(firstScan, booking.getCheckedInAt()); // not overwritten by the second scan
        assertEquals(88L, booking.getCheckedInBy()); // still the original staff member
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void validate_pendingBooking_returnsNotConfirmed() {
        Booking booking = confirmedBooking(showAt(LocalDateTime.now().minusMinutes(10)));
        booking.setStatus(BookingStatus.PENDING);
        when(bookingRepository.findWithLockByBookingReference("ref-5")).thenReturn(Optional.of(booking));

        TicketValidationResponse response = ticketService.validate("ref-5", "gate1");

        assertEquals(TicketValidationResult.NOT_CONFIRMED, response.getResult());
        assertNull(response.getCheckedInAt());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void validate_beforeWindowOpens_returnsOutsideWindow() {
        // opens-minutes-before=60, show starts in 3h - window hasn't opened yet
        Booking booking = confirmedBooking(showAt(LocalDateTime.now().plusHours(3)));
        when(bookingRepository.findWithLockByBookingReference("ref-5")).thenReturn(Optional.of(booking));

        TicketValidationResponse response = ticketService.validate("ref-5", "gate1");

        assertEquals(TicketValidationResult.OUTSIDE_WINDOW, response.getResult());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void validate_afterWindowCloses_returnsOutsideWindow() {
        // duration 150 min, show started 200 min ago - window already closed
        Booking booking = confirmedBooking(showAt(LocalDateTime.now().minusMinutes(200)));
        when(bookingRepository.findWithLockByBookingReference("ref-5")).thenReturn(Optional.of(booking));

        TicketValidationResponse response = ticketService.validate("ref-5", "gate1");

        assertEquals(TicketValidationResult.OUTSIDE_WINDOW, response.getResult());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void validate_unknownReference_returnsNotFound_andEchoesNothing() {
        when(bookingRepository.findWithLockByBookingReference("garbage")).thenReturn(Optional.empty());

        TicketValidationResponse response = ticketService.validate("garbage", "gate1");

        assertEquals(TicketValidationResult.NOT_FOUND, response.getResult());
        assertNull(response.getBookingReference());
        assertNull(response.getMovieTitle());
        assertNull(response.getSeats());
        assertNull(response.getCheckedInAt());
    }

    // --- qrPng ---

    @Test
    void qrPng_nonConfirmedBooking_throwsBookingStateException() {
        Booking booking = confirmedBooking(showAt(LocalDateTime.now()));
        booking.setStatus(BookingStatus.PENDING);
        booking.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(bookingRepository.findByIdAndUserId(5L, 7L)).thenReturn(Optional.of(booking));

        assertThrows(BookingStateException.class, () -> ticketService.qrPng(5L, "alice", 320));
        verifyNoInteractions(qrCodeGenerator);
    }

    @Test
    void qrPng_someoneElsesBooking_throwsBookingNotFoundException() {
        // findByIdAndUserId is the owner-scoped query predicate - a non-owner's
        // request never finds the row at all (404, not 403).
        when(bookingRepository.findByIdAndUserId(5L, 7L)).thenReturn(Optional.empty());

        assertThrows(BookingNotFoundException.class, () -> ticketService.qrPng(5L, "alice", 320));
        verify(bookingRepository).findByIdAndUserId(5L, 7L);
        verifyNoInteractions(qrCodeGenerator);
    }

    @Test
    void qrPng_confirmedBooking_returnsGeneratedBytesForTheBareReference() {
        Booking booking = confirmedBooking(showAt(LocalDateTime.now()));
        when(bookingRepository.findByIdAndUserId(5L, 7L)).thenReturn(Optional.of(booking));
        when(qrCodeGenerator.toPng("ref-5", 320)).thenReturn(new byte[]{1, 2, 3});

        byte[] result = ticketService.qrPng(5L, "alice", 320);

        assertArrayEquals(new byte[]{1, 2, 3}, result);
    }

    // --- getTicket ---

    @Test
    void getTicket_confirmedBooking_returnsFullDetailAndQrUrl() {
        Booking booking = confirmedBooking(showAt(LocalDateTime.now()));
        when(bookingRepository.findByIdAndUserIdWithDetail(5L, 7L)).thenReturn(Optional.of(booking));

        TicketResponse response = ticketService.getTicket(5L, "alice");

        assertEquals("ref-5", response.getBookingReference());
        assertEquals("Dune", response.getMovieTitle());
        assertEquals("Screen 1", response.getScreenName());
        assertEquals(List.of("A1"), response.getSeats());
        assertEquals("/bookings/5/qr", response.getQrUrl());
        assertNull(response.getCheckedInAt());
    }

    @Test
    void getTicket_nonConfirmedBooking_throwsBookingStateException() {
        Booking booking = confirmedBooking(showAt(LocalDateTime.now()));
        booking.setStatus(BookingStatus.FAILED);
        when(bookingRepository.findByIdAndUserIdWithDetail(5L, 7L)).thenReturn(Optional.of(booking));

        assertThrows(BookingStateException.class, () -> ticketService.getTicket(5L, "alice"));
    }
}
