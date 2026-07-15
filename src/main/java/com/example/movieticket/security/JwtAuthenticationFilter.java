package com.example.movieticket.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Runs once per incoming request (OncePerRequestFilter guarantees this even if the
 * request gets forwarded/included internally), sitting in the filter chain BEFORE
 * Spring Security's UsernamePasswordAuthenticationFilter (wired in SecurityConfig).
 *
 * Its entire job: if a valid "Authorization: Bearer <jwt>" header is present,
 * populate the SecurityContext so downstream @PreAuthorize checks and
 * controller-level `Authentication`/`Principal` params work. It never itself
 * rejects a request - it only ever adds an Authentication or leaves the context
 * empty, and always calls filterChain.doFilter() to continue the chain. Rejection
 * (401/403) is left entirely to Spring Security's own entry point / access-denied
 * handler once the request reaches an endpoint that actually requires auth.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * NOTE on a deliberate deviation from the original file-by-file plan in
     * plan/authentication.md: that doc says this filter "skips /auth/**". Taken
     * literally, that would also skip POST /auth/logout - but the endpoint table in
     * the same document (section 5) marks logout as requiring a valid access token,
     * and SecurityConfig only permits register/login/refresh without auth. If this
     * filter skipped /auth/logout too, the SecurityContext would never get
     * populated for that request, and logout would always 401 even with a perfectly
     * valid Bearer token. So this filter only skips the three genuinely public,
     * unauthenticated auth endpoints - logout still runs through here like any
     * other protected route. See logic/jwt.md, "Decisions" section, for the full
     * writeup of this fix.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/auth/register")
                || path.equals("/auth/login")
                || path.equals("/auth/refresh");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // No header, or a header that isn't a Bearer token: leave the context
        // untouched and move on. This lets permitAll routes (and, for an
        // unauthenticated caller, protected routes that will correctly 401 further
        // down the chain) pass through without this filter ever throwing.
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Strip the "Bearer " prefix (7 characters) to get the raw JWT string.
        String jwt = authHeader.substring(BEARER_PREFIX.length());

        // Validates signature + expiry. Never throws (see JwtService.isAccessTokenValid) -
        // an invalid token simply results in `false`, and we fall through leaving
        // the context empty, exactly like the "no header" case above.
        if (jwtService.isAccessTokenValid(jwt)) {
            String username = jwtService.extractUsername(jwt);
            String role = jwtService.extractRole(jwt);

            // Build a fully-authenticated token directly from the JWT's own claims -
            // no call to CustomUserDetailsService/UserRepository here. This is the
            // whole point of embedding the role in the JWT (see Issue #3 in the
            // plan): authorizing a request costs zero DB queries.
            var authToken = new UsernamePasswordAuthenticationToken(
                    username,
                    null, // No credentials needed post-authentication - we're not re-checking a password here.
                    List.of(new SimpleGrantedAuthority(role))
            );
            // Attaches request-specific details (remote address, session id) that
            // Spring Security conventionally records on the Authentication object.
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // This is what actually makes the request "authenticated" for every
            // downstream check (@PreAuthorize, SecurityContextHolder.getContext()
            // .getAuthentication() in a controller, etc.) for the remainder of this
            // request's thread.
            SecurityContextHolder.getContext().setAuthentication(authToken);
            log.debug("Authenticated request for user '{}' via JWT (role={})", username, role);
        } else {
            log.warn("Rejected request to {} with invalid/expired bearer token", request.getRequestURI());
        }

        // Always continue the chain - rejection is Spring Security's job, not this filter's.
        filterChain.doFilter(request, response);
    }
}
