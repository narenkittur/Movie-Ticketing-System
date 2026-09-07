package com.example.movieticket.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "bookings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime bookingTime;

    // Module 5: was `private double totalPrice` - in direct contradiction of
    // claude.md's own pricing note (money must never lose cents to binary
    // floating-point rounding). Nothing ever wrote to this field before Module 5,
    // so this is the first and only moment it could be fixed for free.
    // ddl-auto=update will NOT convert an existing `double` column on a database
    // where `bookings` already exists - see README.md's runbook for the manual
    // ALTER TABLE this needs (plan/payment.md section 7.1).
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPrice;

    // Module 5 (plan/payment.md section 3): PENDING -> CONFIRMED/FAILED/EXPIRED/
    // REFUND_PENDING -> REFUNDED. See BookingStatus's own javadoc for why this is
    // an enum while Seat.status/User.role stay plain Strings.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    // bookingTime + seatlock.ttl-seconds AT CREATION TIME (plan/payment.md section
    // 3.2) - stored, not recomputed from bookingTime + the current TTL property,
    // so a later TTL change never retroactively moves the deadline of a booking
    // already in flight. Can never outlive the Redis lock: claude.md forbids
    // extending that lock, so this deadline is derived from the same property,
    // never hardcoded or independently configurable.
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    // A UUID, not the numeric id - Module 7 puts this value in a QR code, and a
    // sequential id there is enumerable (guess bookingId+1, scan someone else's
    // ticket). Adding it now costs one column; retrofitting after Module 7 ships
    // means reissuing tickets (plan/payment.md section 7.1).
    @Column(name = "booking_reference", nullable = false, unique = true)
    private String bookingReference;

    // Module 7 (plan/qrtickets.md section 5.1): null until the ticket is scanned
    // at the gate; the instant of the FIRST successful scan thereafter. Deliberately
    // NOT a new BookingStatus value - see that class's javadoc. New nullable
    // column - ddl-auto=update adds this cleanly, no manual migration needed
    // (unlike totalPrice's type change above).
    @Column(name = "checked_in_at")
    private LocalDateTime checkedInAt;

    // Module 7 (plan/qrtickets.md section 5.1, Open Decision A): the staff user's
    // id who performed the check-in scan - an audit breadcrumb ("which turnstile
    // let this person in"), not a navigable relationship, so a plain Long rather
    // than a @ManyToOne that would drag a User into every ticket load.
    @Column(name = "checked_in_by")
    private Long checkedInBy;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    // This links to the bridge table to show which specific seats were booked
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    private List<BookingSeat> bookingSeats;

    // Inverse side of Payment.booking - @OneToOne default fetch is EAGER, so this
    // is always a real loaded object (or null), never a lazy proxy that could
    // throw LazyInitializationException outside an open session. BookingService
    // relies on that when building a response from a Booking object returned by a
    // transaction that has since closed (see BookingService.createBooking's
    // self-invocation javadoc).
    @OneToOne(mappedBy = "booking")
    private Payment payment;
}
