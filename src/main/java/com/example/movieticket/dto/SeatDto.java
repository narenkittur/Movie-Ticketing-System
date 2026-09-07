package com.example.movieticket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One cell in a seat-layout grid (plan/crud.md section 6). `status` here is the
 * EFFECTIVE status SeatService computes - Seat.status from the DB overlaid with
 * whatever SeatLockView reports for Redis locks - never a raw copy of the entity
 * field. See SeatLockView's javadoc for why "LOCKED" can appear here despite never
 * being written to the seats table.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatDto {

    private Long id;
    private String seatNumber;

    // One of "AVAILABLE" / "LOCKED" / "BOOKED" (plain String, matching how
    // Seat.status itself is modeled - see claude.md's note on why these aren't
    // enums yet).
    private String status;
}
