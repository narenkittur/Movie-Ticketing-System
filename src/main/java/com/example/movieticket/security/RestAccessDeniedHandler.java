package com.example.movieticket.security;

import com.example.movieticket.dto.ErrorResponse;
// NOTE: Spring Boot 4.1's web stack ships Jackson 3.x, whose classes live under
// the `tools.jackson.*` package (not the classic Jackson 2 `com.fasterxml.jackson.*`
// used e.g. by jjwt-jackson at runtime for JWT serialization). Importing the wrong
// one compiles against a dependency that's only on the runtime classpath transitively
// via JJWT, not the one Spring Boot auto-configures its ObjectMapper bean from.
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Invoked when a request IS authenticated (valid JWT) but the principal lacks the
 * required authority - e.g. a ROLE_USER token hitting an
 * @PreAuthorize("hasRole('ADMIN')") endpoint. This is the 403 counterpart to
 * RestAuthenticationEntryPoint's 401: "who are you?" vs "I know who you are, but
 * you're not allowed here."
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException, ServletException {

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error(HttpStatus.FORBIDDEN.getReasonPhrase())
                .message("You do not have permission to access this resource")
                .path(request.getRequestURI())
                .build();

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
