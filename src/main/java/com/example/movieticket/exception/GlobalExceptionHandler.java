package com.example.movieticket.exception;

import com.example.movieticket.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Module 3 exception types (movie/show/seat catalog) - see plan/crud.md section 9's
// exception-to-status table.

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

    // Thrown when a UNIQUE constraint is violated at the DB level - originally
    // just the users username/email constraint (Module 2), now also the Module 3
    // seats UNIQUE(show_id, seat_number) constraint (see Seat's javadoc). This is
    // the *authoritative* guard in both cases; the service-layer pre-checks
    // (existsByUsername/existsByEmail in AuthService, existsByShowId in
    // SeatService) are fast-path rejections for the common case, not the real
    // guard - see Issue #1 in plan/authentication.md for why a check-then-act
    // pre-check alone isn't safe under concurrent requests. Message is
    // deliberately generic (not "username already exists") since this handler
    // is now shared across more than one uniqueness constraint.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "A record with these values already exists", request, null);
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

    // --- Module 3: Movie/Show/Seat catalog (plan/crud.md section 9) ---

    // Unknown movieId path variable - MovieService.updateMovie()/getMovieOrThrow(),
    // also surfaced through ShowService when a show's parent movie doesn't exist.
    @ExceptionHandler(MovieNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMovieNotFound(MovieNotFoundException ex, HttpServletRequest request) {
        log.warn("Movie not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    // Unknown showId path variable - SeatService.generateLayout()/getSeatLayout().
    @ExceptionHandler(ShowNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleShowNotFound(ShowNotFoundException ex, HttpServletRequest request) {
        log.warn("Show not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    // A new show's [startTime, startTime + duration) window overlaps another show
    // already scheduled on the same screen - ShowService.assertNoScreenConflict().
    @ExceptionHandler(ScreenTimeConflictException.class)
    public ResponseEntity<ErrorResponse> handleScreenTimeConflict(ScreenTimeConflictException ex, HttpServletRequest request) {
        log.warn("Screen time conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    // Seat layout generation called twice for the same show - SeatService.generateLayout().
    @ExceptionHandler(SeatsAlreadyGeneratedException.class)
    public ResponseEntity<ErrorResponse> handleSeatsAlreadyGenerated(SeatsAlreadyGeneratedException ex, HttpServletRequest request) {
        log.warn("Duplicate seat-layout generation attempt on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    // Cross-field validation that Bean Validation can't express on its own -
    // currently only SeatService's "rowLabels size must match rows" check
    // (plan/crud.md: SeatLayoutRequest's javadoc explains why this isn't a
    // @Constraint annotation instead). Deliberately narrow: this does NOT become
    // a catch-all for every IllegalArgumentException in the app, only the ones
    // thrown deliberately as request-validation failures from the service layer.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Invalid request on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    // --- Module 4: Redis seat locking (plan/redis.md section 9) ---

    // Requested seat id doesn't belong to this show - SeatLockService.lock().
    @ExceptionHandler(SeatNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSeatNotFound(SeatNotFoundException ex, HttpServletRequest request) {
        log.warn("Seat not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    // One or more seats already BOOKED, or held by another user - SeatLockService.lock().
    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleSeatUnavailable(SeatUnavailableException ex, HttpServletRequest request) {
        log.warn("Seat unavailable on {}: {} {}", request.getRequestURI(), ex.getMessage(), ex.getDetails());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, ex.getDetails());
    }

    // Module 5's assertHoldsAll() found the caller no longer holds every seat
    // they're trying to book - SeatLockService.assertHoldsAll().
    @ExceptionHandler(SeatLockExpiredException.class)
    public ResponseEntity<ErrorResponse> handleSeatLockExpired(SeatLockExpiredException ex, HttpServletRequest request) {
        log.warn("Seat lock expired on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    // Thrown deliberately by SeatLockService/RedisSeatLockView when Redis is
    // unreachable or a command times out. claude.md's fail-closed rule: never let
    // a Redis outage be read as "the seat is free".
    @ExceptionHandler(SeatLockUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleSeatLockUnavailable(SeatLockUnavailableException ex, HttpServletRequest request) {
        log.error("Seat lock service unavailable on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request, null);
    }

    // Defense-in-depth catch-all: any DataAccessException (Redis or otherwise)
    // that escapes uncaught anywhere still fails closed as a 503 instead of
    // falling through to a raw, unhelpful 500 - consistent with the rule above.
    // Declared after the more specific handlers so Spring's exception resolver
    // prefers those first (it always dispatches to the most specific match, but
    // the ordering here mirrors that intent for readability).
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccessException(DataAccessException ex, HttpServletRequest request) {
        log.error("Unhandled data access error on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable", request, null);
    }

    // --- Module 5: Payment + booking (plan/payment.md section 9) ---

    // Unknown booking id, or one belonging to someone other than the caller -
    // BookingService.getBooking()/cancelBooking()/confirmPaid() etc. Deliberately
    // 404, not 403 (plan/payment.md section 8.5), so booking ids aren't enumerable.
    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBookingNotFound(BookingNotFoundException ex, HttpServletRequest request) {
        log.warn("Booking not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    // An operation illegal for the booking's current status - e.g. cancelling an
    // already-CONFIRMED booking, or confirming one with no payment order yet -
    // BookingService.cancelBooking()/verifyCallbackAndResolveOrderId().
    @ExceptionHandler(BookingStateException.class)
    public ResponseEntity<ErrorResponse> handleBookingState(BookingStateException ex, HttpServletRequest request) {
        log.warn("Illegal booking state transition on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    // Gateway signature mismatch, on either the browser-callback or webhook path -
    // PaymentGateway.verifyCallbackSignature()/verifyAndParseWebhook() (section 8.3).
    @ExceptionHandler(PaymentVerificationException.class)
    public ResponseEntity<ErrorResponse> handlePaymentVerification(PaymentVerificationException ex, HttpServletRequest request) {
        log.warn("Payment signature verification failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    // The payment gateway itself is unreachable, times out, or returns a non-2xx -
    // RazorpayPaymentGateway.createIntent()/refund(). 502, not 503: 503 in this
    // codebase already means "WE are degraded, fail closed" (Redis down); a third
    // party being down is a distinct condition (plan/payment.md section 9).
    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ErrorResponse> handlePaymentGateway(PaymentGatewayException ex, HttpServletRequest request) {
        log.error("Payment gateway error on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY, ex.getMessage(), request, null);
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
