package com.example.movieticket.dto;

import com.example.movieticket.model.TicketValidationResult;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response for POST /admin/tickets/validate (plan/qrtickets.md section 5.4) -
 * ALWAYS 200, {@code result} is what tells the gate operator what to do. Every
 * field but {@code result} is populated for every outcome except NOT_FOUND
 * (booking detail is meaningless for a reference that matched nothing - and the
 * submitted code is never echoed back, since it's attacker-controlled text an
 * operator's screen would render). {@code checkedInAt} is only ever non-null on
 * VALID/ALREADY_USED.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketValidationResponse {

    private TicketValidationResult result;
    private String bookingReference;
    private String movieTitle;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startTime;

    private String screenName;
    private List<String> seats;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime checkedInAt;
}
