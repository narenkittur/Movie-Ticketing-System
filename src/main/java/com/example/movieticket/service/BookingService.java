package com.example.movieticket.service;

import com.example.movieticket.config.PaymentProperties;
import com.example.movieticket.dto.BookingResponse;
import com.example.movieticket.dto.BookingSeatDto;
import com.example.movieticket.dto.ConfirmPaymentRequest;
import com.example.movieticket.dto.CreateBookingRequest;
import com.example.movieticket.exception.BookingNotFoundException;
import com.example.movieticket.exception.BookingStateException;
import com.example.movieticket.exception.SeatNotFoundException;
import com.example.movieticket.exception.SeatUnavailableException;
import com.example.movieticket.exception.ShowNotFoundException;
import com.example.movieticket.model.Booking;
import com.example.movieticket.model.BookingSeat;
import com.example.movieticket.model.BookingStatus;
import com.example.movieticket.model.Payment;
import com.example.movieticket.model.PaymentStatus;
import com.example.movieticket.model.Seat;
import com.example.movieticket.model.Show;
import com.example.movieticket.model.User;
import com.example.movieticket.repository.BookingRepository;
import com.example.movieticket.repository.BookingSeatRepository;
import com.example.movieticket.repository.PaymentRepository;
import com.example.movieticket.repository.SeatRepository;
import com.example.movieticket.repository.ShowRepository;
import com.example.movieticket.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Module 5's business logic (plan/payment.md) - turns an ephemeral Redis hold
 * (Module 4) into a durable, paid-for sale without ever letting two users end up
 * with the same seat. See {@link #createBooking} and {@link #confirmPaid} for the
 * two halves of the async state machine (plan/payment.md section 3).
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    // Matches SeatService/SeatLockService's own literal - see those classes for
    // why these aren't enums yet.
    private static final String STATUS_BOOKED = "BOOKED";

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final SeatRepository seatRepository;
    private final ShowRepository showRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final SeatLockService seatLockService;
    private final PaymentGateway paymentGateway;
    private final PaymentProperties paymentProperties;
    private final ApplicationEventPublisher eventPublisher;

    // A proxy of THIS bean, injected lazily - see createBooking()'s javadoc for
    // why this exists. Never call the @Transactional methods below via `this.`;
    // always via `self.`. Deliberately not `final`: BookingServiceTest sets it via
    // ReflectionTestUtils to self-reference the instance under test (a plain unit
    // test has no real Spring proxy to inject here at all).
    private BookingService self;

    @Value("${seatlock.ttl-seconds}")
    private long seatLockTtlSeconds;

    public BookingService(BookingRepository bookingRepository,
                           BookingSeatRepository bookingSeatRepository,
                           SeatRepository seatRepository,
                           ShowRepository showRepository,
                           UserRepository userRepository,
                           PaymentRepository paymentRepository,
                           SeatLockService seatLockService,
                           PaymentGateway paymentGateway,
                           PaymentProperties paymentProperties,
                           ApplicationEventPublisher eventPublisher,
                           @Lazy BookingService self) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.seatRepository = seatRepository;
        this.showRepository = showRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.seatLockService = seatLockService;
        this.paymentGateway = paymentGateway;
        this.paymentProperties = paymentProperties;
        this.eventPublisher = eventPublisher;
        this.self = self;
    }

    /**
     * POST /bookings - three steps, not one (plan/payment.md section 5): step A
     * opens a DB transaction (re-validate seats + lock assertion + persist
     * PENDING booking); step B calls the gateway over the network with NO
     * transaction open (section 5.1 - never hold a pooled JDBC connection and
     * {@code FOR UPDATE} row locks for the duration of a call to somebody else's
     * server); step C opens a second, short transaction to persist what the
     * gateway returned.
     *
     * <p>All three steps live in this one method, in this one class, per
     * claude.md principle #4 ("payment orchestration lives in the service layer,
     * not controllers") - but {@link #createPendingBooking} and
     * {@link #attachIntent} are invoked via {@code self}, an injected proxy of
     * this very bean, NOT via {@code this}. Spring's {@code @Transactional} is
     * proxy-based AOP: a same-class ("self-invocation") call bypasses the proxy
     * entirely and runs with NO transaction at all, silently, rather than the one
     * the annotation promises - a real correctness bug here, since step A's
     * {@code FOR UPDATE} locks are meaningless without an actual open
     * transaction. Routing through {@code self} sends the call back through the
     * proxy, so each step gets the real transaction boundary its
     * {@code @Transactional} annotation declares.
     */
    public BookingResponse createBooking(CreateBookingRequest request, String username) {
        BookingCreationResult result = self.createPendingBooking(request, username);
        if (!result.created()) {
            // Open Decision B (plan/payment.md section 13-B): a double-click
            // gets back the SAME pending booking (and its existing checkoutUrl)
            // instead of a second payable one for the same seats.
            return toResponse(result.booking());
        }

        Booking booking = result.booking();
        PaymentIntentCommand command = new PaymentIntentCommand(
                booking.getBookingReference(),
                booking.getTotalPrice(),
                paymentProperties.getCurrency(),
                booking.getUser().getEmail(),
                paymentProperties.getCallbackBaseUrl() + "/bookings/" + booking.getId() + "/confirm");

        // Step B - deliberately NOT @Transactional. If this throws
        // PaymentGatewayException (502), the booking stays PENDING with a null
        // providerOrderId: it expires on its own with no cleanup, the seat lock
        // TTLs out, and the caller can retry the whole booking (Open Decision C:
        // no in-request retry here - fail fast, per claude.md principle #3).
        PaymentIntent intent = paymentGateway.createIntent(command);

        return self.attachIntent(booking.getId(), intent);
    }

    /** Step A of {@link #createBooking} - see that method's javadoc. Package-visible only so BookingServiceTest can exercise it directly without a second HTTP round trip. */
    @Transactional
    BookingCreationResult createPendingBooking(CreateBookingRequest request, String username) {
        User user = resolveUser(username);
        Show show = showRepository.findById(request.getShowId())
                .orElseThrow(() -> new ShowNotFoundException("No show found with id " + request.getShowId()));

        // Dedup + sort ascending in one step - required both for the reusable-
        // booking comparison below and for the deadlock-avoidance ordering
        // SeatRepository.findByIdInAndShowIdForUpdate's javadoc describes
        // (plan/payment.md section 7.5).
        List<Long> seatIds = List.copyOf(new TreeSet<>(request.getSeatIds()));

        Booking existing = findReusablePendingBooking(user.getId(), show.getId(), seatIds);
        if (existing != null) {
            log.info("Reusing existing PENDING booking {} for user {} show {} seats {}",
                    existing.getId(), user.getId(), show.getId(), seatIds);
            return new BookingCreationResult(existing, false);
        }

        List<Seat> seats = seatRepository.findByIdInAndShowIdForUpdate(seatIds, show.getId());
        if (seats.size() != seatIds.size()) {
            throw new SeatNotFoundException("One or more seat ids do not belong to show " + show.getId());
        }

        List<String> alreadyBooked = seats.stream()
                .filter(seat -> STATUS_BOOKED.equals(seat.getStatus()))
                .map(seat -> "seat " + seat.getId() + " is already booked")
                .toList();
        if (!alreadyBooked.isEmpty()) {
            throw new SeatUnavailableException("One or more seats are no longer available", alreadyBooked);
        }

        // Section 5.2: runs AFTER the FOR UPDATE seat re-read, not before - we
        // are already holding the row locks that make this decision meaningful
        // before asking Redis anything. Both checks are mandatory; neither
        // replaces the other (plan/redis.md section 11.1).
        seatLockService.assertHoldsAll(show.getId(), seatIds, user.getId());

        BigDecimal totalPrice = show.getPrice().multiply(BigDecimal.valueOf(seatIds.size()));
        LocalDateTime now = LocalDateTime.now();

        Booking booking = Booking.builder()
                .bookingTime(now)
                .totalPrice(totalPrice)
                .status(BookingStatus.PENDING)
                .expiresAt(now.plusSeconds(seatLockTtlSeconds))
                .bookingReference(UUID.randomUUID().toString())
                .user(user)
                .show(show)
                .build();
        Booking savedBooking = bookingRepository.save(booking);

        List<BookingSeat> bookingSeats = seats.stream()
                .map(seat -> BookingSeat.builder().booking(savedBooking).seat(seat).build())
                .toList();
        booking.setBookingSeats(bookingSeatRepository.saveAll(bookingSeats));

        Payment payment = Payment.builder()
                .booking(booking)
                .provider(paymentGateway.name())
                .amount(totalPrice)
                .currency(paymentProperties.getCurrency())
                .status(PaymentStatus.CREATED)
                .build();
        booking.setPayment(paymentRepository.save(payment));

        log.info("Created PENDING booking {} (ref {}) for user {} show {} seats {}, total {}",
                booking.getId(), booking.getBookingReference(), user.getId(), show.getId(), seatIds, totalPrice);

        return new BookingCreationResult(booking, true);
    }

    /** Step C of {@link #createBooking}. Package-visible for the same reason as {@link #createPendingBooking}. */
    @Transactional
    BookingResponse attachIntent(Long bookingId, PaymentIntent intent) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking " + bookingId + " vanished between steps A and C"));
        Payment payment = booking.getPayment();
        payment.setProviderOrderId(intent.providerOrderId());
        payment.setCheckoutUrl(intent.checkoutUrl());
        paymentRepository.save(payment);

        log.info("Booking {} attached to gateway order {}", booking.getId(), intent.providerOrderId());
        return toResponse(booking);
    }

    /**
     * POST /bookings/{bookingId}/confirm's ownership + signature check
     * (plan/payment.md section 8.4). Deliberately returns just the
     * {@code providerOrderId} rather than calling {@link #confirmPaid} itself -
     * BookingController calls both this and {@link #confirmPaid} directly, so
     * each runs through the Spring proxy with its own transaction (same
     * self-invocation concern as {@link #createBooking}, avoided here by keeping
     * the two calls at the controller instead of nesting them in this class).
     *
     * <p>Resolves the payment to verify via the AUTHENTICATED CALLER's own
     * booking, never off any order id a request body could echo back - a
     * signature that's internally consistent for someone else's order must never
     * be accepted here.
     */
    @Transactional(readOnly = true)
    public String verifyCallbackAndResolveOrderId(Long bookingId, ConfirmPaymentRequest request, String username) {
        Long userId = resolveUser(username).getId();
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException("No booking found with id " + bookingId));

        Payment payment = booking.getPayment();
        if (payment == null || payment.getProviderOrderId() == null) {
            throw new BookingStateException("Booking " + bookingId + " has no payment order to confirm yet");
        }

        paymentGateway.verifyCallbackSignature(payment.getProviderOrderId(), request.getProviderPaymentId(), request.getSignature());
        return payment.getProviderOrderId();
    }

    /**
     * The single idempotent method both confirmation paths funnel into
     * (plan/payment.md section 4) - the browser callback
     * ({@link #verifyCallbackAndResolveOrderId} + this, called in sequence by
     * BookingController) and the webhook (PaymentWebhookController, called
     * directly) are two independent deliveries of the same fact. Either can
     * arrive first, both can arrive, either can arrive twice.
     *
     * <p>Step 1 - {@code findWithLockByProviderOrderId}'s {@code PESSIMISTIC_WRITE}
     * row lock - is the WHOLE concurrency story (section 4.2): idempotency gate, race
     * gate between the two paths, and serialization point for the seat writes, all
     * from one lock on one row. {@code providerOrderId} is the idempotency key
     * itself (UNIQUE NOT NULL) - not a separate manufactured one.
     */
    @Transactional
    public BookingResponse confirmPaid(String providerOrderId, String providerPaymentId) {
        Payment payment = paymentRepository.findWithLockByProviderOrderId(providerOrderId)
                .orElseThrow(() -> new BookingNotFoundException("No payment found for order " + providerOrderId));
        Booking booking = payment.getBooking();

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            log.info("confirmPaid: booking {} already CONFIRMED - idempotent no-op", booking.getId());
            return toResponse(booking);
        }

        // Section 4.3: the paid-too-late case. The user paid, but the window
        // closed first (or the booking already FAILED, e.g. was cancelled). The
        // money is real; the seats are not ours to give - we NEVER confirm here,
        // since that would mean honouring a hold that expired, exactly the
        // lock-extension claude.md forbids.
        boolean tooLate = booking.getStatus() == BookingStatus.FAILED
                || (booking.getStatus() == BookingStatus.PENDING && LocalDateTime.now().isAfter(booking.getExpiresAt()));
        if (tooLate) {
            booking.setStatus(BookingStatus.REFUND_PENDING);
            payment.setStatus(PaymentStatus.REFUND_PENDING);
            payment.setProviderPaymentId(providerPaymentId);
            bookingRepository.save(booking);
            paymentRepository.save(payment);
            log.error("Payment {} for booking {} arrived too late (status={}, expiresAt={}) - REFUND_PENDING",
                    providerPaymentId, booking.getId(), booking.getStatus(), booking.getExpiresAt());
            publishBookingStatusChanged(booking);
            return toResponse(booking);
        }

        List<Long> seatIds = booking.getBookingSeats().stream().map(bs -> bs.getSeat().getId()).sorted().toList();
        List<Seat> seats = seatRepository.findByIdInAndShowIdForUpdate(seatIds, booking.getShow().getId());

        // Section 4.4: the paid-but-seat-gone case. The lock expired, another
        // user locked and confirmed the seat, and THEN our payment landed. This
        // FOR UPDATE read is what catches it - the DB is the backstop here
        // exactly as claude.md describes; the Redis lock was the primary gate
        // and it already lost.
        boolean seatGone = seats.stream().anyMatch(s -> STATUS_BOOKED.equals(s.getStatus()));
        if (seatGone) {
            booking.setStatus(BookingStatus.REFUND_PENDING);
            payment.setStatus(PaymentStatus.REFUND_PENDING);
            payment.setProviderPaymentId(providerPaymentId);
            bookingRepository.save(booking);
            paymentRepository.save(payment);
            log.error("Payment {} for booking {} landed but a seat was already BOOKED elsewhere - REFUND_PENDING",
                    providerPaymentId, booking.getId());
            publishBookingStatusChanged(booking);
            return toResponse(booking);
        }

        seats.forEach(s -> s.setStatus(STATUS_BOOKED));
        seatRepository.saveAll(seats);

        booking.setStatus(BookingStatus.CONFIRMED);
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setProviderPaymentId(providerPaymentId);
        bookingRepository.save(booking);
        paymentRepository.save(payment);

        // Section 4.5: called INSIDE this transaction, on purpose - it registers
        // an afterCommit callback, so calling it before commit is what causes the
        // release to happen AFTER commit. Never release before commit (claude.md's
        // fixed booking-confirmation order); if this transaction rolls back, the
        // callback never fires and the locks stand until TTL - correct, since the
        // user may retry. BOOKED, not RELEASED: these seats were just sold, not
        // freed (plan/websockets.md section 4.2) - releaseAfterCommit publishes
        // SeatLockChangedEvent(BOOKED) itself once the unlock's afterCommit hook runs.
        seatLockService.releaseAfterCommit(booking.getShow().getId(), seatIds, booking.getUser().getId(),
                SeatLockChangedEvent.Type.BOOKED);

        log.info("Booking {} CONFIRMED via payment {}", booking.getId(), providerPaymentId);
        publishBookingStatusChanged(booking);
        return toResponse(booking);
    }

    /** GET /bookings/{bookingId} - owner only (section 8.5: 404, not 403, so booking ids aren't enumerable). */
    @Transactional(readOnly = true)
    public BookingResponse getBooking(Long bookingId, String username) {
        Long userId = resolveUser(username).getId();
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException("No booking found with id " + bookingId));
        return toResponse(booking);
    }

    /** GET /bookings - the caller's own bookings, most recent first. */
    @Transactional(readOnly = true)
    public List<BookingResponse> listBookings(String username) {
        Long userId = resolveUser(username).getId();
        return bookingRepository.findAllForUserWithSeats(userId).stream().map(this::toResponse).toList();
    }

    /** POST /bookings/{bookingId}/cancel - owner only. Only a (non-expired) PENDING booking can be cancelled; releases the locks now rather than waiting out the TTL. */
    @Transactional
    public BookingResponse cancelBooking(Long bookingId, String username) {
        Long userId = resolveUser(username).getId();
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException("No booking found with id " + bookingId));

        BookingStatus effective = effectiveStatus(booking);
        if (effective != BookingStatus.PENDING) {
            throw new BookingStateException("Booking " + bookingId + " cannot be cancelled from status " + effective);
        }

        List<Long> seatIds = booking.getBookingSeats().stream().map(bs -> bs.getSeat().getId()).toList();
        booking.setStatus(BookingStatus.FAILED);
        bookingRepository.save(booking);

        // Called inside this transaction for the same reason as confirmPaid's
        // call (section 4.5) - release only after the FAILED status actually
        // commits, never before, so a rollback can't leave the seat unlocked in
        // Redis while the booking still (incorrectly) claims to hold it. RELEASED,
        // not BOOKED: these seats are genuinely free again (plan/websockets.md
        // section 4.2).
        seatLockService.releaseAfterCommit(booking.getShow().getId(), seatIds, userId,
                SeatLockChangedEvent.Type.RELEASED);

        log.info("Booking {} cancelled by user {}, locks queued for release after commit", bookingId, userId);
        return toResponse(booking);
    }

    /**
     * Open Decision B (plan/payment.md section 13-B): looks for a non-expired
     * PENDING booking this user already holds, for this exact show + seat set.
     * Deliberately an exact-set match, not "any overlap" - a partially-different
     * seat selection is a genuinely new booking request.
     */
    private Booking findReusablePendingBooking(Long userId, Long showId, List<Long> sortedRequestedSeatIds) {
        List<Booking> candidates = bookingRepository.findByUserIdAndShowIdAndStatusWithSeats(userId, showId, BookingStatus.PENDING);
        LocalDateTime now = LocalDateTime.now();
        for (Booking candidate : candidates) {
            if (candidate.getExpiresAt().isBefore(now)) {
                continue; // stale PENDING row - not reusable, let it read as EXPIRED on its own
            }
            List<Long> candidateSeatIds = candidate.getBookingSeats().stream()
                    .map(bs -> bs.getSeat().getId()).sorted().toList();
            if (candidateSeatIds.equals(sortedRequestedSeatIds)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Module 6 (plan/websockets.md section 6): publishes
     * {@link BookingStatusChangedEvent} from all three terminal branches of
     * {@link #confirmPaid} - CONFIRMED, and both REFUND_PENDING outcomes
     * (paid-too-late, paid-but-seat-gone). Those two REFUND_PENDING cases are
     * exactly the ones a user must not have to discover by refreshing: their
     * money moved and their seats did not. Published mid-transaction on purpose -
     * {@code BookingBroadcaster} is a {@code @TransactionalEventListener(AFTER_COMMIT)},
     * so it does the waiting; publishing here (rather than registering a second
     * {@code afterCommit} callback) keeps this method's control flow flat.
     */
    private void publishBookingStatusChanged(Booking booking) {
        eventPublisher.publishEvent(new BookingStatusChangedEvent(
                booking.getId(), booking.getUser().getUsername(), booking.getStatus()));
    }

    private User resolveUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user '" + username + "' has no matching row in the users table"));
    }

    private BookingResponse toResponse(Booking booking) {
        BookingStatus status = effectiveStatus(booking);

        List<BookingSeatDto> seatDtos = booking.getBookingSeats() == null ? List.of() :
                booking.getBookingSeats().stream()
                        .map(bs -> BookingSeatDto.builder().seatId(bs.getSeat().getId()).seatNumber(bs.getSeat().getSeatNumber()).build())
                        .toList();

        String checkoutUrl = null;
        if (status == BookingStatus.PENDING && booking.getPayment() != null) {
            checkoutUrl = booking.getPayment().getCheckoutUrl();
        }

        return BookingResponse.builder()
                .id(booking.getId())
                .bookingReference(booking.getBookingReference())
                .status(status.name())
                .showId(booking.getShow().getId())
                .totalPrice(booking.getTotalPrice())
                .seats(seatDtos)
                .bookingTime(booking.getBookingTime())
                .expiresAt(booking.getExpiresAt())
                .checkoutUrl(checkoutUrl)
                .build();
    }

    /**
     * EXPIRED is derived at read time, never written by a scheduler
     * (plan/payment.md section 3.3, mirroring Module 4's TTL-as-reconciliation and
     * SeatService.toSeatDto's status precedence). Deliberate simplification vs.
     * the design doc's exact wording: this computes the DISPLAY status on every
     * read rather than opportunistically persisting EXPIRED the first time a
     * stale PENDING row is touched - functionally identical to every caller (they
     * always see the correct derived status), and avoids adding a write path to
     * what would otherwise be a plain read-only query. See logic/payment.md.
     */
    private BookingStatus effectiveStatus(Booking booking) {
        if (booking.getStatus() == BookingStatus.PENDING && LocalDateTime.now().isAfter(booking.getExpiresAt())) {
            return BookingStatus.EXPIRED;
        }
        return booking.getStatus();
    }

    /** Internal result of {@link #createPendingBooking} - never leaves this class. */
    record BookingCreationResult(Booking booking, boolean created) {
    }
}
