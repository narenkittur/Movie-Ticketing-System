package com.example.movieticket.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
// Module 5 (plan/payment.md section 7.3, correcting claude.md's earlier claim that
// a uk_seat_show_seatnumber-style constraint already backstops booking rows - it
// doesn't, and the obvious UNIQUE(seat_id) can't be added: BookingSeat rows are
// created at PENDING time (before payment), so the same seat legitimately appears
// in an earlier abandoned booking AND a later successful one. This constraint only
// prevents a seat being duplicated WITHIN one booking. The real cross-booking
// guard is the `SELECT ... FOR UPDATE` seat re-read in
// BookingService.confirmPaid, which is actually STRONGER than a per-row unique
// index since it also catches the cross-booking case a constraint here cannot see.
@Table(name = "booking_seats", uniqueConstraints = @UniqueConstraint(
        name = "uk_booking_seat", columnNames = {"booking_id", "seat_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;
}
