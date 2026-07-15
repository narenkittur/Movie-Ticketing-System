package com.example.movieticket.repository;

import com.example.movieticket.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    
    // Find all bookings for a specific user (for "My Orders" page)
    List<Booking> findByUserId(Long userId);
    
    // Find all bookings for a specific show (useful for admins to see theater occupancy)
    List<Booking> findByShowId(Long showId);
}