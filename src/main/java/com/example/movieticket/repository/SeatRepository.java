package com.example.movieticket.repository;

import com.example.movieticket.model.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {

    // Retrieve all seats for a specific show (e.g., to render the seating map)
    List<Seat> findByShowId(Long showId);

    // Filter seats by status (e.g., to count how many are still available)
    List<Seat> findByShowIdAndStatus(Long showId, String status);
}