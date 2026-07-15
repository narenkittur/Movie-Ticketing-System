package com.example.movieticket.controller;

import com.example.movieticket.dto.AuthResponse;
import com.example.movieticket.dto.LoginRequest;
import com.example.movieticket.dto.RefreshRequest;
import com.example.movieticket.dto.RegisterRequest;
import com.example.movieticket.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP layer for Module 2 - every method just validates the request shape
 * (via @Valid) and delegates straight to AuthService, per claude.md's
 * "decoupling" principle (business logic belongs in the service layer, not here).
 *
 * TODO (tracked in claude.md "Known Gaps"): once springdoc-openapi-starter-webmvc-ui
 * is added to pom.xml, annotate these methods with @Operation/@ApiResponse
 * (io.swagger.v3.oas.annotations) so they show up in Swagger UI - not added yet
 * because that dependency isn't on the classpath, and importing annotations from
 * a library that isn't declared would break compilation.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * POST /auth/register - public. Creates a ROLE_USER account; does not log the
     * client in. Returns 201 with no body - the client must call /auth/login next.
     */
    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * POST /auth/login - public. Exchanges valid credentials for a fresh
     * access + refresh token pair.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /auth/refresh - public (the refresh token itself is the credential
     * here, so no Authorization header is required to call this endpoint).
     * Rotates the refresh token: the one submitted is revoked and a new one is
     * returned alongside a new access token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        AuthResponse response = authService.refresh(request.getRefreshToken());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /auth/logout - requires a valid access token (enforced by
     * SecurityConfig: this path is NOT in the permitAll list, unlike the three
     * above). Revokes the given refresh token; the access token used to call this
     * endpoint keeps working until it naturally expires (see AuthService.logout()
     * javadoc and Issue #2 in plan/authentication.md).
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }
}
