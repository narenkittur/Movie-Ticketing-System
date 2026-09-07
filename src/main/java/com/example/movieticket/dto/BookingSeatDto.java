package com.example.movieticket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One seat within a {@link BookingResponse} (plan/payment.md section 11). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingSeatDto {
    private Long seatId;
    private String seatNumber;
}
