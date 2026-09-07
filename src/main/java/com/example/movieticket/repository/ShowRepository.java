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

    // Module 3: backs GET /movies/{id}/shows default behavior (plan/crud.md
    // section 11-F) - only shows still to come, not the movie's entire history.
    List<Show> findByMovieIdAndStartTimeAfter(Long movieId, LocalDateTime time);

    // Module 3: the building block for the screen double-booking guard in
    // ShowService (plan/crud.md section 4.2). We deliberately do NOT try to
    // express the full interval-overlap predicate ("existingStart < newEnd AND
    // existingEnd > newStart") in the query itself, because Show has no persisted
    // endTime column - a show's length is derived from its Movie's
    // durationMinutes, which isn't something a derived-query method can reach
    // into. Instead this fetches every show already on that screen inside a
    // window wide enough to contain any possible overlap, and ShowService does
    // the precise per-show overlap arithmetic in Java using each candidate's
    // movie.durationMinutes.
    List<Show> findByScreenNameAndStartTimeBetween(String screenName, LocalDateTime windowStart, LocalDateTime windowEnd);
}