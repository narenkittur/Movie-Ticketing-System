package com.example.movieticket.service;

import com.example.movieticket.config.PaymentProperties;
import com.example.movieticket.dto.CreateBookingRequest;
import com.example.movieticket.exception.BookingStateException;
import com.example.movieticket.exception.PaymentGatewayException;
import com.example.movieticket.exception.SeatLockExpiredException;
import com.example.movieticket.exception.SeatNotFoundException;
import com.example.movieticket.exception.SeatUnavailableException;
import com.example.movieticket.model.*;
import com.example.movieticket.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BookingService's business rules, with every collaborator mocked -
 * same shape as SeatLockServiceTest (all repositories + SeatLockService/
 * PaymentGateway mocked, no real transactions/network). Follows the scenario list
 * in plan/payment.md section 10.1. Tests requiring a real MySQL honouring
 * SELECT ... FOR UPDATE (e.g. a true concurrent double-confirm race) are
 * deliberately NOT here - see claude.md's Known Gaps (Open Decision D, deferred).
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingSeatRepository bookingSeatRepository;
    @Mock private SeatRepository seatRepository;
    @Mock private ShowRepository showRepository;
    @Mock private UserRepository userRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private SeatLockService seatLockService;
    @Mock private PaymentGateway paymentGateway;
    @Mock private ApplicationEventPublisher eventPublisher;

    private BookingService bookingService;

    private static final User ALICE = User.builder().id(7L).username("alice").email("alice@example.com").role("ROLE_USER").build();
    private static final Show SHOW = Show.builder().id(12L).price(new BigDecimal("200.00")).build();

    @BeforeEach
    void setUp() {
        PaymentProperties properties = new PaymentProperties();
        properties.setCurrency("INR");
        properties.setCallbackBaseUrl("http://localhost:8080");

        bookingService = new BookingService(bookingRepository, bookingSeatRepository, seatRepository,
                showRepository, userRepository, paymentRepository, seatLockService, paymentGateway, properties,
                eventPublisher, null);
        // A plain unit test has no real Spring AOP proxy to inject - self-reference
        // the instance under test so self.createPendingBooking(...)/attachIntent(...)
        // calls resolve to this same object (see BookingService's own javadoc on why
        // `self` exists at all).
        ReflectionTestUtils.setField(bookingService, "self", bookingService);
        ReflectionTestUtils.setField(bookingService, "seatLockTtlSeconds", 300L);

        lenient().when(userRepository.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        lenient().when(showRepository.findById(12L)).thenReturn(Optional.of(SHOW));
        lenient().when(bookingRepository.findByUserIdAndShowIdAndStatusWithSeats(anyLong(), anyLong(), eq(BookingStatus.PENDING)))
                .thenReturn(List.of());
        lenient().when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bookingSeatRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paymentGateway.name()).thenReturn("mock");
    }

    private Seat seat(long id, String status) {
        return Seat.builder().id(id).seatNumber("A" + id).status(status).show(SHOW).build();
    }

    // --- creation ---

    @Test
    void createBooking_happyPath_leavesBookingPending_seatsNotBooked_locksNotReleased() {
        when(seatRepository.findByIdInAndShowIdForUpdate(List.of(101L, 102L), 12L))
                .thenReturn(List.of(seat(101L, "AVAILABLE"), seat(102L, "AVAILABLE")));
        when(paymentGateway.createIntent(any())).thenReturn(new PaymentIntent("mock_order_1", "http://localhost:8080/mock-gateway/pay/mock_order_1"));
        when(bookingRepository.findById(any())).thenAnswer(inv -> Optional.of(
                Booking.builder().id(1L).show(SHOW).user(ALICE).status(BookingStatus.PENDING)
                        .totalPrice(new BigDecimal("400.00")).bookingReference("ref-1")
                        .bookingTime(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusSeconds(300))
                        .bookingSeats(List.of())
                        .payment(Payment.builder().status(PaymentStatus.CREATED).build())
                        .build()));

        CreateBookingRequest request = CreateBookingRequest.builder().showId(12L).seatIds(List.of(101L, 102L)).build();
        var response = bookingService.createBooking(request, "alice");

        assertEquals("PENDING", response.getStatus());
        assertEquals("http://localhost:8080/mock-gateway/pay/mock_order_1", response.getCheckoutUrl());
        verify(seatLockService, never()).releaseAfterCommit(any(), any(), any(), any());
        verify(seatLockService).assertHoldsAll(eq(12L), eq(List.of(101L, 102L)), eq(7L));
    }

    @Test
    void createBooking_assertHoldsAllThrows_nothingPersisted_gatewayNeverCalled() {
        when(seatRepository.findByIdInAndShowIdForUpdate(List.of(101L), 12L)).thenReturn(List.of(seat(101L, "AVAILABLE")));
        doThrow(new SeatLockExpiredException("expired")).when(seatLockService).assertHoldsAll(any(), any(), any());

        CreateBookingRequest request = CreateBookingRequest.builder().showId(12L).seatIds(List.of(101L)).build();

        assertThrows(SeatLockExpiredException.class, () -> bookingService.createBooking(request, "alice"));
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void createBooking_seatAlreadyBooked_throwsConflict_beforeAssertHoldsAllOrGateway() {
        when(seatRepository.findByIdInAndShowIdForUpdate(List.of(101L), 12L)).thenReturn(List.of(seat(101L, "BOOKED")));

        CreateBookingRequest request = CreateBookingRequest.builder().showId(12L).seatIds(List.of(101L)).build();

        assertThrows(SeatUnavailableException.class, () -> bookingService.createBooking(request, "alice"));
        verifyNoInteractions(seatLockService);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void createBooking_seatBelongingToDifferentShow_throwsNotFound() {
        when(seatRepository.findByIdInAndShowIdForUpdate(List.of(101L, 999L), 12L)).thenReturn(List.of(seat(101L, "AVAILABLE")));

        CreateBookingRequest request = CreateBookingRequest.builder().showId(12L).seatIds(List.of(101L, 999L)).build();

        assertThrows(SeatNotFoundException.class, () -> bookingService.createBooking(request, "alice"));
        verifyNoInteractions(seatLockService);
    }

    @Test
    void createBooking_totalPriceIsShowPriceTimesSeatCount_exactBigDecimal() {
        when(seatRepository.findByIdInAndShowIdForUpdate(List.of(101L, 102L, 103L), 12L))
                .thenReturn(List.of(seat(101L, "AVAILABLE"), seat(102L, "AVAILABLE"), seat(103L, "AVAILABLE")));
        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        when(paymentGateway.createIntent(any())).thenReturn(new PaymentIntent("o1", "url"));
        when(bookingRepository.findById(any())).thenAnswer(inv -> Optional.of(
                Booking.builder().id(1L).show(SHOW).user(ALICE).status(BookingStatus.PENDING)
                        .totalPrice(new BigDecimal("600.00")).bookingSeats(List.of())
                        .expiresAt(LocalDateTime.now().plusSeconds(300))
                        .payment(Payment.builder().build()).build()));

        CreateBookingRequest request = CreateBookingRequest.builder().showId(12L).seatIds(List.of(101L, 102L, 103L)).build();
        bookingService.createBooking(request, "alice");

        verify(bookingRepository, atLeastOnce()).save(captor.capture());
        assertEquals(0, new BigDecimal("600.00").compareTo(captor.getAllValues().get(0).getTotalPrice()));
    }

    @Test
    void createBooking_gatewayFailure_leavesBookingPendingWithNoOrderId_propagates502() {
        when(seatRepository.findByIdInAndShowIdForUpdate(List.of(101L), 12L)).thenReturn(List.of(seat(101L, "AVAILABLE")));
        when(paymentGateway.createIntent(any())).thenThrow(new PaymentGatewayException("gateway down"));

        CreateBookingRequest request = CreateBookingRequest.builder().showId(12L).seatIds(List.of(101L)).build();

        assertThrows(PaymentGatewayException.class, () -> bookingService.createBooking(request, "alice"));
        // Step A already committed (in real Spring; here just verify save happened) -
        // step C (attachIntent) never runs, so no providerOrderId is ever persisted.
        verify(bookingRepository, atLeastOnce()).save(any());
        verify(paymentRepository, never()).save(argThat(p -> p.getProviderOrderId() != null));
    }

    @Test
    void createBooking_duplicateRequest_reusesExistingPendingBooking_gatewayCalledOnlyOnce() {
        Booking existing = Booking.builder().id(5L).show(SHOW).user(ALICE).status(BookingStatus.PENDING)
                .totalPrice(new BigDecimal("200.00")).bookingReference("ref-existing")
                .expiresAt(LocalDateTime.now().plusSeconds(200))
                .bookingSeats(List.of(BookingSeat.builder().seat(seat(101L, "AVAILABLE")).build()))
                .payment(Payment.builder().status(PaymentStatus.CREATED).checkoutUrl("http://existing").build())
                .build();
        when(bookingRepository.findByUserIdAndShowIdAndStatusWithSeats(7L, 12L, BookingStatus.PENDING))
                .thenReturn(List.of(existing));

        CreateBookingRequest request = CreateBookingRequest.builder().showId(12L).seatIds(List.of(101L)).build();
        var response = bookingService.createBooking(request, "alice");

        assertEquals("http://existing", response.getCheckoutUrl());
        assertEquals(5L, response.getId());
        verifyNoInteractions(paymentGateway);
        verify(seatRepository, never()).findByIdInAndShowIdForUpdate(any(), any());
    }

    // --- confirmation ---

    private Payment confirmablePayment(Booking booking) {
        Payment payment = Payment.builder().booking(booking).status(PaymentStatus.CREATED)
                .providerOrderId("order-1").build();
        booking.setPayment(payment);
        return payment;
    }

    @Test
    void confirmPaid_calledTwice_exactlyOneConfirmedBooking_seatsSetBookedOnce() {
        Booking booking = Booking.builder().id(1L).show(SHOW).user(ALICE).status(BookingStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusSeconds(120))
                .bookingSeats(List.of(BookingSeat.builder().seat(seat(101L, "AVAILABLE")).build()))
                .build();
        Payment payment = confirmablePayment(booking);
        when(paymentRepository.findWithLockByProviderOrderId("order-1")).thenReturn(Optional.of(payment));
        when(seatRepository.findByIdInAndShowIdForUpdate(List.of(101L), 12L)).thenReturn(List.of(seat(101L, "AVAILABLE")));

        var first = bookingService.confirmPaid("order-1", "pay-1");
        assertEquals("CONFIRMED", first.getStatus());
        verify(seatLockService, times(1)).releaseAfterCommit(eq(12L), eq(List.of(101L)), any(), eq(SeatLockChangedEvent.Type.BOOKED));
        verify(seatRepository, times(1)).saveAll(any());
        verify(eventPublisher, times(1)).publishEvent(argThat((BookingStatusChangedEvent evt) -> evt.status() == BookingStatus.CONFIRMED));

        // Second delivery of the same webhook - booking object is now CONFIRMED
        // (mutated in place above), so this must be a pure idempotent no-op: the
        // early-return branch at the top of confirmPaid never reaches the publish
        // call, so the browser is never told "confirmed" twice.
        var second = bookingService.confirmPaid("order-1", "pay-1");
        assertEquals("CONFIRMED", second.getStatus());
        verify(seatLockService, times(1)).releaseAfterCommit(any(), any(), any(), any()); // still just once
        verify(seatRepository, times(1)).saveAll(any()); // still just once
        verify(eventPublisher, times(1)).publishEvent(any(BookingStatusChangedEvent.class)); // still just once
    }

    @Test
    void confirmPaid_afterExpiresAt_goesRefundPending_seatsUntouched_neverConfirmed() {
        Booking booking = Booking.builder().id(2L).show(SHOW).user(ALICE).status(BookingStatus.PENDING)
                .expiresAt(LocalDateTime.now().minusSeconds(1))
                .bookingSeats(List.of(BookingSeat.builder().seat(seat(101L, "AVAILABLE")).build()))
                .build();
        Payment payment = confirmablePayment(booking);
        when(paymentRepository.findWithLockByProviderOrderId("order-1")).thenReturn(Optional.of(payment));

        var response = bookingService.confirmPaid("order-1", "pay-1");

        assertEquals("REFUND_PENDING", response.getStatus());
        verifyNoInteractions(seatRepository);
        verify(seatLockService, never()).releaseAfterCommit(any(), any(), any(), any());
        // Paid-too-late is exactly the outcome the user must not discover by
        // refreshing (plan/websockets.md section 6) - the money moved and the
        // seats did not, so this branch publishes too, not just the happy path.
        verify(eventPublisher, times(1)).publishEvent(argThat((BookingStatusChangedEvent evt) -> evt.status() == BookingStatus.REFUND_PENDING));
    }

    @Test
    void confirmPaid_seatAlreadyBookedByAnotherBooking_goesRefundPending() {
        Booking booking = Booking.builder().id(3L).show(SHOW).user(ALICE).status(BookingStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusSeconds(120))
                .bookingSeats(List.of(BookingSeat.builder().seat(seat(101L, "AVAILABLE")).build()))
                .build();
        Payment payment = confirmablePayment(booking);
        when(paymentRepository.findWithLockByProviderOrderId("order-1")).thenReturn(Optional.of(payment));
        // The FOR UPDATE re-read finds the seat already BOOKED by someone else's
        // (already-confirmed) booking.
        when(seatRepository.findByIdInAndShowIdForUpdate(List.of(101L), 12L)).thenReturn(List.of(seat(101L, "BOOKED")));

        var response = bookingService.confirmPaid("order-1", "pay-1");

        assertEquals("REFUND_PENDING", response.getStatus());
        verify(seatLockService, never()).releaseAfterCommit(any(), any(), any(), any());
        verify(eventPublisher, times(1)).publishEvent(argThat((BookingStatusChangedEvent evt) -> evt.status() == BookingStatus.REFUND_PENDING));
    }

    // --- cancel ---

    @Test
    void cancel_pendingBooking_becomesFailed_releasesLocks() {
        Booking booking = Booking.builder().id(4L).show(SHOW).status(BookingStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusSeconds(120))
                .bookingSeats(List.of(BookingSeat.builder().seat(seat(101L, "AVAILABLE")).build()))
                .build();
        when(bookingRepository.findByIdAndUserId(4L, 7L)).thenReturn(Optional.of(booking));

        var response = bookingService.cancelBooking(4L, "alice");

        assertEquals("FAILED", response.getStatus());
        verify(seatLockService).releaseAfterCommit(eq(12L), eq(List.of(101L)), eq(7L), eq(SeatLockChangedEvent.Type.RELEASED));
        // Cancellation is not a payment outcome - BookingStatusChangedEvent is only
        // published from confirmPaid's three terminal branches (plan/websockets.md
        // section 6), never from cancelBooking.
        verify(eventPublisher, never()).publishEvent(any(BookingStatusChangedEvent.class));
    }

    @Test
    void cancel_confirmedBooking_throwsConflict() {
        Booking booking = Booking.builder().id(4L).show(SHOW).status(BookingStatus.CONFIRMED)
                .expiresAt(LocalDateTime.now().plusSeconds(120)).bookingSeats(List.of()).build();
        when(bookingRepository.findByIdAndUserId(4L, 7L)).thenReturn(Optional.of(booking));

        assertThrows(BookingStateException.class, () -> bookingService.cancelBooking(4L, "alice"));
        verify(seatLockService, never()).releaseAfterCommit(any(), any(), any(), any());
    }
}
