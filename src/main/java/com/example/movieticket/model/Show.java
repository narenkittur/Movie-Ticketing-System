package com.example.movieticket.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "shows")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime startTime;

    private String screenName; // e.g., "Screen 1", "IMAX"

    // Flat per-show ticket price (Module 3). BigDecimal, not double/float, because
    // money must never lose cents to binary floating-point rounding - this is what
    // Module 5's Booking.totalPrice gets multiplied out from (seatCount * price).
    // Lives on Show rather than Seat: this project prices a whole showing the same
    // regardless of which seat you pick, not per-seat tiers (premium/regular) -
    // simplest model that still unblocks booking. See plan/crud.md section 11-A.
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @ManyToOne
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @OneToMany(mappedBy = "show", cascade = CascadeType.ALL)
    private List<Seat> seats;
}