package com.example.movieticket.service;

import com.example.movieticket.dto.TicketResponse;
import com.example.movieticket.dto.TicketValidationResponse;
import com.example.movieticket.exception.BookingNotFoundException;
import com.example.movieticket.exception.BookingStateException;
import com.example.movieticket.model.Booking;
import com.example.movieticket.model.BookingStatus;
import com.example.movieticket.model.Show;
import com.example.movieticket.model.TicketValidationResult;
import com.example.movieticket.model.User;
import com.example.movieticket.repository.BookingRepository;
import com.example.movieticket.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Module 7's business logic (plan/qrtickets.md) - all three rules
 * (claude.md principle #4: this lives in the service layer, not the
 * controllers) for turning a CONFIRMED booking into something a person carries
 * to a door, and reading it back exactly once. See {@link #validate} for the
 * module's actual substance - the single-use state transition guarded by a row
 * lock (section 5), one step downstream of {@code BookingService.confirmPaid}'s
 * identical pattern.
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final QrCodeGenerator qrCodeGenerator;

    // How early before showtime the gate accepts a ticket (plan/qrtickets.md
    // section 5.3) - the window closes at startTime + movie.durationMinutes,
    // derived on every call rather than persisted (same reasoning as
    // ShowService.assertNoScreenConflict's screen-conflict end-time derivation).
    @Value("${ticket.checkin.opens-minutes-before}")
    private long checkinOpensMinutesBefore;

    public TicketService(BookingRepository bookingRepository, UserRepository userRepository, QrCodeGenerator qrCodeGenerator) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.qrCodeGenerator = qrCodeGenerator;
    }

    /**
     * GET /bookings/{bookingId}/qr - owner only, CONFIRMED only (plan/qrtickets.md
     * section 4.3). The QR payload is the bare {@code bookingReference} and
     * nothing else (section 4.1) - a random UUID looked up in our own database is
     * a nonce, not a claim, so there is nothing to sign or tamper with.
     */
    @Transactional(readOnly = true)
    public byte[] qrPng(Long bookingId, String username, int sizePx) {
        Booking booking = ownedConfirmedBooking(bookingId, username);
        return qrCodeGenerator.toPng(booking.getBookingReference(), sizePx);
    }

    /** GET /bookings/{bookingId}/ticket - owner only, CONFIRMED only (plan/qrtickets.md section 4.4). */
    @Transactional(readOnly = true)
    public TicketResponse getTicket(Long bookingId, String username) {
        Long userId = resolveUser(username).getId();
        Booking booking = bookingRepository.findByIdAndUserIdWithDetail(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException("No booking found with id " + bookingId));
        assertConfirmed(booking, bookingId);
        return toTicketResponse(booking);
    }

    /**
     * POST /admin/tickets/validate (plan/qrtickets.md section 5) - the module's
     * actual substance. Read, decide, and write all happen in this ONE
     * {@code @Transactional} method, guarded by
     * {@link BookingRepository#findWithLockByBookingReference}'s
     * {@code PESSIMISTIC_WRITE} row lock: two turnstiles scanning the same
     * forwarded screenshot at the same instant serialise on this one row, so the
     * second scanner blocks until the first commits and then observes a non-null
     * {@code checkedInAt}. Deliberately NOT {@code readOnly = true} - however much
     * a scan reads like a lookup, it is a write.
     *
     * <p>Always returns 200 with an explicit {@link TicketValidationResult} -
     * see that enum's javadoc for why this deliberately does not throw through
     * GlobalExceptionHandler like every other endpoint in the project.
     */
    @Transactional
    public TicketValidationResponse validate(String code, String staffUsername) {
        Long staffId = resolveUser(staffUsername).getId();

        Booking booking = bookingRepository.findWithLockByBookingReference(code).orElse(null);
        if (booking == null) {
            log.warn("Ticket validation: staff {} scanned an unrecognised reference", staffId);
            return TicketValidationResponse.builder().result(TicketValidationResult.NOT_FOUND).build();
        }

        // Raw status, not effectiveStatus - PENDING (whether or not it's actually
        // stale) and every other non-CONFIRMED value all collapse to the same
        // NOT_CONFIRMED outcome here, so the EXPIRED distinction getBooking/getTicket
        // care about doesn't matter to a gate operator.
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            log.info("Ticket validation: booking {} not confirmed (status={}), staff {}",
                    booking.getId(), booking.getStatus(), staffId);
            return response(TicketValidationResult.NOT_CONFIRMED, booking, false);
        }

        if (booking.getCheckedInAt() != null) {
            log.warn("Ticket validation: booking {} already checked in at {} - duplicate scan by staff {}",
                    booking.getId(), booking.getCheckedInAt(), staffId);
            return response(TicketValidationResult.ALREADY_USED, booking, true);
        }

        Show show = booking.getShow();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowOpen = show.getStartTime().minusMinutes(checkinOpensMinutesBefore);
        LocalDateTime windowClose = show.getStartTime().plusMinutes(show.getMovie().getDurationMinutes());
        if (now.isBefore(windowOpen) || now.isAfter(windowClose)) {
            log.info("Ticket validation: booking {} outside check-in window [{}, {}], now={}, staff {}",
                    booking.getId(), windowOpen, windowClose, now, staffId);
            return response(TicketValidationResult.OUTSIDE_WINDOW, booking, false);
        }

        booking.setCheckedInAt(now);
        booking.setCheckedInBy(staffId);
        bookingRepository.save(booking);

        log.info("Ticket validation: booking {} VALID, checked in by staff {}", booking.getId(), staffId);
        return response(TicketValidationResult.VALID, booking, true);
    }

    private Booking ownedConfirmedBooking(Long bookingId, String username) {
        Long userId = resolveUser(username).getId();
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException("No booking found with id " + bookingId));
        assertConfirmed(booking, bookingId);
        return booking;
    }

    private void assertConfirmed(Booking booking, Long bookingId) {
        if (effectiveStatus(booking) != BookingStatus.CONFIRMED) {
            throw new BookingStateException("Booking " + bookingId + " is not CONFIRMED");
        }
    }

    private TicketResponse toTicketResponse(Booking booking) {
        return TicketResponse.builder()
                .bookingReference(booking.getBookingReference())
                .movieTitle(booking.getShow().getMovie().getTitle())
                .startTime(booking.getShow().getStartTime())
                .screenName(booking.getShow().getScreenName())
                .seats(sortedSeatNumbers(booking))
                .totalPrice(booking.getTotalPrice())
                .qrUrl("/bookings/" + booking.getId() + "/qr")
                .checkedInAt(booking.getCheckedInAt())
                .build();
    }

    private TicketValidationResponse response(TicketValidationResult result, Booking booking, boolean includeCheckedInAt) {
        return TicketValidationResponse.builder()
                .result(result)
                .bookingReference(booking.getBookingReference())
                .movieTitle(booking.getShow().getMovie().getTitle())
                .startTime(booking.getShow().getStartTime())
                .screenName(booking.getShow().getScreenName())
                .seats(sortedSeatNumbers(booking))
                .checkedInAt(includeCheckedInAt ? booking.getCheckedInAt() : null)
                .build();
    }

    private List<String> sortedSeatNumbers(Booking booking) {
        if (booking.getBookingSeats() == null) {
            return List.of();
        }
        return booking.getBookingSeats().stream()
                .map(bs -> bs.getSeat().getSeatNumber())
                .sorted()
                .toList();
    }

    private User resolveUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user '" + username + "' has no matching row in the users table"));
    }

    /**
     * Mirrors {@code BookingService.effectiveStatus} (plan/payment.md section 3.3)
     * - a stale PENDING row must read as EXPIRED here too, so an unpaid-and-expired
     * booking never mints a QR/ticket view. Duplicated rather than exposed from
     * BookingService: Module 7's class inventory (plan/qrtickets.md section 6)
     * doesn't touch BookingService.java, and this is a 2-line derivation, not a
     * shared algorithm worth a cross-service dependency.
     */
    private BookingStatus effectiveStatus(Booking booking) {
        if (booking.getStatus() == BookingStatus.PENDING && LocalDateTime.now().isAfter(booking.getExpiresAt())) {
            return BookingStatus.EXPIRED;
        }
        return booking.getStatus();
    }
}
