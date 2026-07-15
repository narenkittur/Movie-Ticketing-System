package com.example.movieticket.security;

import com.example.movieticket.dto.ErrorResponse;
// See the matching note in RestAccessDeniedHandler: Spring Boot 4.1 uses Jackson
// 3.x (`tools.jackson.*`) for its own web stack/auto-configured ObjectMapper bean.
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Invoked by Spring Security whenever an unauthenticated request hits an endpoint
 * that requires authentication (i.e. SecurityContext is empty when access is
 * checked) - the standard trigger case is a missing/invalid/expired JWT on a
 * protected route (see JwtAuthenticationFilter, which leaves the context empty
 * rather than rejecting the request itself).
 *
 * Without this bean, Spring Boot's default behavior is to render its whitelabel
 * HTML error page even for a JSON API - registering this as the entryPoint in
 * SecurityConfig replaces that with the same ErrorResponse JSON shape used
 * everywhere else (GlobalExceptionHandler), so API clients never have to
 * special-case security-layer errors.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        // Reuse Spring Boot's auto-configured ObjectMapper bean (already set up
        // with the app's Jackson modules, e.g. JavaTimeModule for LocalDateTime)
        // instead of `new ObjectMapper()`, so serialization behaves identically to
        // every other JSON response.
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException, ServletException {

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
                .message("Authentication required or token is invalid/expired")
                .path(request.getRequestURI())
                .build();

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Writing directly to the response's writer because this runs inside the
        // servlet filter chain, before/outside of Spring MVC's normal
        // @RestController message-conversion machinery.
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
