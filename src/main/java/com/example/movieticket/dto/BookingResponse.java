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
 * Response shape for every booking-returning endpoint (plan/payment.md section 2,
 * endpoints 1-2, 4-6). {@code checkoutUrl} is only meaningful while
 * {@code status} is {@code PENDING} - null once a booking has moved past that
 * (there's nothing left to pay). {@code status} reflects BookingService's
 * lazily-derived EXPIRED state (plan/payment.md section 3.3), which may differ
 * from what's actually persisted for a stale PENDING row nobody has touched yet.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingResponse {

    private Long id;
    private String bookingReference;
    private String status;
    private Long showId;
    private BigDecimal totalPrice;
    private List<BookingSeatDto> seats;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime bookingTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;

    private String checkoutUrl;
}
