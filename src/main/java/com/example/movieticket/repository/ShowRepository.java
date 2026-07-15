package com.example.movieticket.repository;

import com.example.movieticket.model.Show;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ShowRepository extends JpaRepository<Show, Long> {

    // Find all showtimes for a specific movie
    List<Show> findByMovieId(Long movieId);

    // Find all shows happening after a certain time (useful for the "Now Showing" page)
    List<Show> findByStartTimeAfter(LocalDateTime time);

    // Find shows for a movie within a specific date range
    List<Show> findByMovieIdAndStartTimeBetween(Long movieId, LocalDateTime start, LocalDateTime end);
}