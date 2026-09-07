package com.example.movieticket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body for POST /admin/tickets/validate (plan/qrtickets.md section 6) - the bare scanned code. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketValidationRequest {

    @NotBlank(message = "code is required")
    private String code;
}
