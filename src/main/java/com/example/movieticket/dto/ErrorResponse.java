package com.example.movieticket.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Uniform JSON error body for every 4xx/5xx response this module produces -
 * GlobalExceptionHandler, RestAuthenticationEntryPoint (401) and
 * RestAccessDeniedHandler (403) all return this same shape instead of Spring's
 * default HTML error page, so API clients only ever have to parse one error format.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErrorResponse {

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") // Fixes the JSON date format instead of leaking Jackson's default array/epoch form.
    private LocalDateTime timestamp;

    private int status;      // HTTP status code, e.g. 401
    private String error;    // HTTP reason phrase, e.g. "Unauthorized"
    private String message;  // Human-readable detail, e.g. "Invalid username or password"
    private String path;     // Request URI that triggered the error, e.g. "/auth/login"

    // Populated only for 400 validation failures: one entry per invalid field
    // (e.g. "password: must be at least 8 characters"). Null/omitted otherwise.
    private List<String> details;
}
