package com.example.movieticket.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Module 5 (plan/payment.md section 7.2): one row per booking's payment attempt.
 * {@code @OneToOne}, not {@code @OneToMany} - a user who wants to retry after a
 * failure creates a NEW booking (their Redis lock has to be re-acquired anyway),
 * not a second {@code Payment} against the same booking; see {@link Booking}'s
 * javadoc for why.
 *
 * <p>{@code providerOrderId} is the module's idempotency key - see
 * {@code BookingService.confirmPaid}'s javadoc for how the
 * {@code PESSIMISTIC_WRITE} lock taken on this row does the entire module's
 * concurrency work by itself.
 *
 * <p><b>Deviation from plan/payment.md section 7.2's schema table:</b> that table
 * marks {@code provider_order_id} "unique, not null", but section 5 step A
 * explicitly persists this row with a null {@code providerOrderId} (the gateway
 * hasn't been called yet) and step C fills it in afterwards. The column is
 * therefore nullable at the DB level - MySQL's unique index already treats
 * multiple {@code NULL}s as distinct, so this doesn't weaken the idempotency
 * guarantee once a real order id is set. Also added {@code checkoutUrl}, which
 * section 5 step C says to persist but section 7.2's table omits entirely -
 * without storing it, neither a later {@code GET /bookings/{id}} on a still-PENDING
 * booking nor the Open-Decision-B "reuse an existing PENDING booking" path could
 * hand back a checkout link. See logic/payment.md.
 */
@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    // "mock" | "razorpay" - PaymentGateway.name(), persisted so a booking always
    // records which implementation actually handled it (plan/payment.md section 6).
    @Column(nullable = false, length = 20)
    private String provider;

    // Null immediately after POST /bookings step A, set in step C once the
    // gateway call returns (see this class's javadoc deviation note above).
    @Column(name = "provider_order_id", unique = true, length = 100)
    private String providerOrderId;

    @Column(name = "provider_payment_id", length = 100)
    private String providerPaymentId;

    // Persisted so GET /bookings/{id} and the create-time dedupe reuse path
    // (Open Decision B) can hand back the SAME checkout link rather than
    // reconstructing one - see this class's javadoc.
    @Column(name = "checkout_url", length = 500)
    private String checkoutUrl;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
