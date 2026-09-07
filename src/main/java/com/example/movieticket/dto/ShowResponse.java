package com.example.movieticket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response body for show-returning endpoints (create show, list shows for a
 * movie). Flattened: carries movieId/movieTitle directly instead of nesting a full
 * MovieResponse, so the client doesn't have to chase a second field for the one
 * piece of movie context it actually needs on a show list.
 *
 * availableSeats/totalSeats are an optional convenience summary (plan/crud.md
 * section 5.2) so a "now showing" list can render "42 / 96 seats left" without a
 * second round trip to GET /shows/{id}/seats. ShowService only populates these
 * for the list-shows-for-a-movie endpoint (one extra query per show - acceptable
 * at this module's scale per the plan's N+1 note); they're left null on the
 * create-show response, where no seats exist yet.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowResponse {

    private Long id;
    private Long movieId;
    private String movieTitle;
    private LocalDateTime startTime;
    private String screenName;
    private BigDecimal price;
    private Integer totalSeats;
    private Integer availableSeats;
}
