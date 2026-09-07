package com.example.movieticket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for every Movie-returning endpoint (create, update, list, search).
 * Deliberately flat - does NOT embed the movie's `shows` list. Movie <-> Show is a
 * bidirectional JPA relationship (Movie.shows / Show.movie); serializing the entity
 * directly would either recurse infinitely or, with Jackson reference annotations,
 * silently trigger a lazy-load per movie when listing (an N+1 query bug). Callers
 * that need a movie's shows call GET /movies/{id}/shows instead (plan/crud.md
 * endpoint #6), which is its own paged/filtered query.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovieResponse {

    private Long id;
    private String title;
    private String description;
    private int durationMinutes;
}
