package com.example.movieticket.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payload for POST /admin/movies/{movieId}/shows (plan/crud.md endpoint #3).
 *
 * Deliberately has no `movieId` field - the parent movie comes from the URL path,
 * not the request body, so there's no way for the body to disagree with the path
 * (same "identity comes from the route, not the payload" principle as Module 2's
 * RegisterRequest omitting `role`).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShowRequest {

    @NotNull(message = "Start time is required")
    @Future(message = "Start time must be in the future") // Rejects scheduling a show in the past.
    private LocalDateTime startTime;

    @NotBlank(message = "Screen name is required")
    private String screenName;

    // Flat per-show ticket price - see Show.price's javadoc for why this lives on
    // Show rather than Movie or Seat.
    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Price must be greater than zero")
    private BigDecimal price;
}
