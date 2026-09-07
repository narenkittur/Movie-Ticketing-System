package com.example.movieticket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Response body for both POST /admin/shows/{showId}/seats and GET /shows/{showId}/seats. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatLayoutResponse {

    private Long showId;
    private int rows;
    private int seatsPerRow;
    private List<SeatDto> seats;
}
