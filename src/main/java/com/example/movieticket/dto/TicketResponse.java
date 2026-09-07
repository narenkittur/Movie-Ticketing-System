package com.example.movieticket.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response for GET /bookings/{id}/ticket (plan/qrtickets.md section 4.4) - the
 * printable face of a booking, which BookingResponse can't serve since it only
 * exposes {@code showId}. {@code qrUrl} is server-built so a client never
 * string-builds it itself. {@code checkedInAt} is nullable - non-null once the
 * ticket has been redeemed at the gate (section 5).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketResponse {

    private String bookingReference;
    private String movieTitle;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startTime;

    private String screenName;
    private List<String> seats;
    private BigDecimal totalPrice;
    private String qrUrl;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime checkedInAt;
}
