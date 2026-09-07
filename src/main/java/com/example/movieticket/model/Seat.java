package com.example.movieticket.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
// Belt-and-braces against a double-generated layout (plan/crud.md section 4.3):
// the service layer already guards with existsByShowId before inserting, but that
// check-then-act is not atomic under concurrent admin requests. This DB-level
// UNIQUE constraint is the authoritative guard - same pattern as the users
// username/email constraint backing AuthService.register() (see GlobalExceptionHandler's
// DataIntegrityViolationException handler, extended in Module 3 to cover this too).
@Table(name = "seats", uniqueConstraints = @UniqueConstraint(
        name = "uk_seat_show_seatnumber", columnNames = {"show_id", "seat_number"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String seatNumber;

    @Column(nullable = false)
    private String status; 

    @ManyToOne
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    // ADD THIS: This allows you to navigate from a Seat to all its BookingSeat records
    @OneToMany(mappedBy = "seat")
    private List<BookingSeat> bookingSeats;
}