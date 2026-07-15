package com.example.movieticket.repository;

import com.example.movieticket.model.BookingSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingSeatRepository extends JpaRepository<BookingSeat, Long> {
    
    // Find all seats associated with a specific booking (the receipt details)
    List<BookingSeat> findByBookingId(Long bookingId);
    
    // Find all seat records for a specific show (to see which seats are taken)
    List<BookingSeat> findByBookingShowId(Long showId);
}
