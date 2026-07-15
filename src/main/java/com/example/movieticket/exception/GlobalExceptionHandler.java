package com.example.movieticket.exception;

import com.example.movieticket.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Central place that turns exceptions thrown anywhere in a @RestController call
 * into the uniform ErrorResponse JSON shape, instead of Spring Boot's default
 * HTML/whitelabel error page. @RestControllerAdvice = @ControllerAdvice + @ResponseBody
 * applied to every method, so each handler below can just return a body.
 *
 * Note: this only covers exceptions thrown *inside* a controller method. 401s for
 * missing/invalid JWTs and 403s for insufficient role are thrown by the security
 * filter chain itself (before the controller runs) and are handled separately by
 * RestAuthenticationEntryPoint / RestAccessDeniedHandler - see SecurityConfig.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Bean Validation (@Valid) failures on @RequestBody DTOs, e.g. blank username,
    // password under 8 characters, malformed email. Spring throws this before the
    // controller method body ever runs.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        // Collect every field error into a human-readable "field: message" list,
        // rather than reporting only the first failure - lets the client fix all
        // invalid fields in one round trip instead of one-at-a-time.
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .toList();

        log.warn("Validation failed for {}: {}", request.getRequestURI(), details);

        return build(HttpStatus.BAD_REQUEST, "Validation failed", request, details);
    }

    // Thrown when a UNIQUE constraint (username or email) is violated at the DB
    // level. This is the *authoritative* guard against duplicate registrations -
    // see Issue #1 in plan/authentication.md for why the service-layer existsBy...
    // pre-check alone isn't sufficient under concurrent requests.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "Username or email already in use", request, null);
    }

    // Thrown by AuthenticationManager.authenticate() in AuthService.login() when
    // the username doesn't exist or the password doesn't match the stored hash.
    // Same message for both cases so the response never confirms whether a given
    // username exists.
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        log.warn("Failed login attempt on {}", request.getRequestURI());
        return build(HttpStatus.UNAUTHORIZED, "Invalid username or password", request, null);
    }

    // Unknown / revoked / expired refresh token presented to /auth/refresh or
    // /auth/logout - see InvalidRefreshTokenException's own javadoc.
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException ex, HttpServletRequest request) {
        log.warn("Invalid refresh token presented on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request, null);
    }

    // Catch-all for any other Spring Security AuthenticationException subtype that
    // reaches the controller layer (defensive - most auth failures are caught more
    // specifically above or never reach the controller at all).
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        log.warn("Authentication error on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, "Authentication failed", request, null);
    }

    // Small helper so every handler above builds the same ErrorResponse shape
    // with one line instead of repeating the builder chain five times.
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request, List<String> details) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .details(details)
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
