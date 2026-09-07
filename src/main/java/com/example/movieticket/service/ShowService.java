package com.example.movieticket.service;

import com.example.movieticket.dto.ShowRequest;
import com.example.movieticket.dto.ShowResponse;
import com.example.movieticket.exception.ScreenTimeConflictException;
import com.example.movieticket.model.Movie;
import com.example.movieticket.model.Show;
import com.example.movieticket.repository.SeatRepository;
import com.example.movieticket.repository.ShowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Business logic behind the Show half of Module 3 (plan/crud.md endpoints #3, #6):
 * scheduling a movie onto a screen, and listing a movie's shows.
 */
@Service
public class ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);

    // Upper bound on how long any real movie could plausibly run. Used only to
    // size the candidate-fetch window in the screen-overlap check below - it is
    // NOT a business rule about movie length, just a generous "look-back" margin
    // so we don't miss a still-running earlier show when checking for overlap
    // (plan/crud.md section 4.2, "no-schema-change fallback" option: Show has no
    // persisted endTime column, so a show's end is always derived from
    // movie.durationMinutes at query time).
    private static final long MAX_CANDIDATE_LOOKBACK_HOURS = 24;

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    // Reused rather than re-implemented: MovieService.getMovieOrThrow() already
    // owns the canonical "movie not found" lookup used by MovieController.
    private final MovieService movieService;

    public ShowService(ShowRepository showRepository, SeatRepository seatRepository, MovieService movieService) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.movieService = movieService;
    }

    /**
     * POST /admin/movies/{movieId}/shows. Validates the parent movie exists, then
     * rejects any show whose time window would overlap another show already
     * scheduled on the same screen (a physical screen can only play one thing at
     * a time) before persisting.
     */
    @Transactional
    public ShowResponse createShow(Long movieId, ShowRequest request) {
        // 404s if the movie doesn't exist - thrown from inside MovieService so
        // both MovieController and ShowController surface an identical error.
        Movie movie = movieService.getMovieOrThrow(movieId);

        LocalDateTime newStart = request.getStartTime();
        // The new show's occupied window is [newStart, newEnd) - derived from the
        // MOVIE's runtime, since Show itself doesn't persist a duration/endTime.
        LocalDateTime newEnd = newStart.plusMinutes(movie.getDurationMinutes());

        assertNoScreenConflict(request.getScreenName(), newStart, newEnd);

        Show show = Show.builder()
                .startTime(newStart)
                .screenName(request.getScreenName())
                .price(request.getPrice())
                .movie(movie)
                .build();

        Show saved = showRepository.save(show);
        log.info("Created show id={} for movie '{}' on screen '{}' at {}",
                saved.getId(), movie.getTitle(), saved.getScreenName(), saved.getStartTime());

        // No seats exist yet (that's a separate admin call, endpoint #4) -
        // totalSeats/availableSeats are left null rather than 0 to distinguish
        // "layout not generated yet" from "generated but sold out" (see
        // ShowResponse's javadoc).
        return toResponse(saved, null, null);
    }

    /**
     * GET /movies/{movieId}/shows. Defaults to future shows only
     * (plan/crud.md section 11-F) - a "now showing" listing has no use for shows
     * that already happened; pass includePast=true for a full history view.
     */
    @Transactional(readOnly = true)
    public List<ShowResponse> listShowsForMovie(Long movieId, boolean includePast) {
        // Confirms the movie exists so an empty result unambiguously means
        // "movie exists, nothing scheduled" rather than "no such movie" (the same
        // disambiguation MovieService.getMovieOrThrow() gives every other lookup).
        movieService.getMovieOrThrow(movieId);

        List<Show> shows = includePast
                ? showRepository.findByMovieId(movieId)
                : showRepository.findByMovieIdAndStartTimeAfter(movieId, LocalDateTime.now());

        return shows.stream()
                .map(this::toResponseWithSeatCounts)
                .toList();
    }

    /**
     * Fetches every show already booked onto {@code screenName} within a window
     * wide enough to contain any possible overlap with [newStart, newEnd), then
     * does the precise interval-overlap test in Java (two intervals overlap iff
     * existingStart &lt; newEnd AND existingEnd &gt; newStart) using each
     * candidate's own movie.durationMinutes to derive its end time.
     */
    private void assertNoScreenConflict(String screenName, LocalDateTime newStart, LocalDateTime newEnd) {
        LocalDateTime windowStart = newStart.minusHours(MAX_CANDIDATE_LOOKBACK_HOURS);
        List<Show> candidates = showRepository.findByScreenNameAndStartTimeBetween(screenName, windowStart, newEnd);

        for (Show candidate : candidates) {
            // Show.movie is a @ManyToOne (EAGER by default), so this doesn't
            // trigger a lazy-init exception outside a transaction - but it IS one
            // extra row's worth of already-fetched data per candidate, which is
            // the accepted N+1-shaped tradeoff of not persisting Show.endTime
            // (plan/crud.md section 4.2).
            LocalDateTime candidateEnd = candidate.getStartTime().plusMinutes(candidate.getMovie().getDurationMinutes());
            boolean overlaps = candidate.getStartTime().isBefore(newEnd) && candidateEnd.isAfter(newStart);
            if (overlaps) {
                log.warn("Screen conflict on '{}': requested [{}, {}) overlaps existing show id={} [{}, {})",
                        screenName, newStart, newEnd, candidate.getId(), candidate.getStartTime(), candidateEnd);
                throw new ScreenTimeConflictException(
                        "Screen '" + screenName + "' already has a show scheduled in that time window");
            }
        }
    }

    /** Adds the availableSeats/totalSeats summary (plan/crud.md section 5.2). */
    private ShowResponse toResponseWithSeatCounts(Show show) {
        // Two extra queries per show in the list - acceptable at this module's
        // expected scale (a single movie's show list is tens of rows, not
        // thousands); flagged in plan/crud.md section 5.2 as the deliberate
        // tradeoff for keeping this endpoint simple instead of a hand-rolled
        // aggregate/count query.
        int total = seatRepository.findByShowId(show.getId()).size();
        int available = seatRepository.findByShowIdAndStatus(show.getId(), "AVAILABLE").size();
        return toResponse(show, total, available);
    }

    private ShowResponse toResponse(Show show, Integer totalSeats, Integer availableSeats) {
        return ShowResponse.builder()
                .id(show.getId())
                .movieId(show.getMovie().getId())
                .movieTitle(show.getMovie().getTitle())
                .startTime(show.getStartTime())
                .screenName(show.getScreenName())
                .price(show.getPrice())
                .totalSeats(totalSeats)
                .availableSeats(availableSeats)
                .build();
    }
}
