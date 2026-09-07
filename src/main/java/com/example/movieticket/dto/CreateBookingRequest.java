package com.example.movieticket.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body for POST /bookings (plan/payment.md section 2). The 10-seat cap mirrors
 * SeatLockRequest's own cap: a booking can never legitimately exceed what a
 * single lock request could have acquired in the first place.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateBookingRequest {

    @NotNull(message = "showId is required")
    private Long showId;

    @NotEmpty(message = "seatIds must contain at least one seat id")
    @Size(max = 10, message = "cannot book more than 10 seats in a single request")
    private List<Long> seatIds;
}
