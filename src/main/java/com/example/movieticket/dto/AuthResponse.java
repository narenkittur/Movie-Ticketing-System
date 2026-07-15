package com.example.movieticket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response body for POST /auth/login and POST /auth/refresh. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {

    // Short-lived JWT. Sent as "Authorization: Bearer <accessToken>" on every
    // subsequent protected request (see JwtAuthenticationFilter).
    private String accessToken;

    // Long-lived opaque token. Sent only to /auth/refresh (to get a new access
    // token) or /auth/logout (to revoke it). Never parsed as a JWT.
    private String refreshToken;

    // Always "Bearer" - tells the client which HTTP Authorization scheme to use.
    // Kept as a field (rather than hardcoding client-side) so it's discoverable
    // from the API response itself and shows up correctly in the OpenAPI schema.
    @Builder.Default
    private String tokenType = "Bearer";

    // Access-token lifetime in seconds (not milliseconds) - seconds is the
    // conventional unit for `expires_in` in OAuth2-style token responses, so
    // clients following that convention can consume this without surprises.
    private long expiresIn;
}
