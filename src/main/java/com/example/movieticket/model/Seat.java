package com.example.movieticket.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Table(name = "seats")
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