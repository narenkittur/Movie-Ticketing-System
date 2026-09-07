package com.example.movieticket.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Central security wiring for the whole app: what's public, what needs a valid
 * JWT, how passwords are hashed, and how auth/authorization failures are reported.
 * Every other module (3, 4, 5, 7 per plan/authentication.md section 7) builds on
 * top of what's configured here via @PreAuthorize + the `role` JWT claim.
 */
@Configuration // Marks this as a source of @Bean definitions, processed at application startup.
// Module 3 fix: without this annotation, every @PreAuthorize("hasRole('ADMIN')")
// on MovieController/ShowController/SeatController (and anywhere else in the app)
// is silently NEVER ENFORCED - Spring Security only evaluates method-security
// annotations when a class carrying this annotation is on the context. The
// Module 2 plan (plan/authentication.md section 7) already assumed @PreAuthorize
// "works downstream", but the annotation enabling it was never actually added
// until now (plan/crud.md section 8-b flagged this as the module's highest-risk
// item). @EnableMethodSecurity is the Spring Security 6+/7 replacement for the
// older @EnableGlobalMethodSecurity(prePostEnabled = true).
@EnableMethodSecurity
public class SecurityConfig {

    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    // Constructor injection - Spring supplies both beans automatically since each
    // has exactly one implementation on the context (@Component on both classes).
    public SecurityConfig(RestAuthenticationEntryPoint restAuthenticationEntryPoint,
                           RestAccessDeniedHandler restAccessDeniedHandler) {
        this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
        this.restAccessDeniedHandler = restAccessDeniedHandler;
    }

    /**
     * JwtAuthenticationFilter has no @Component of its own (it takes a JwtService
     * constructor argument and we want it built here, deliberately, right next to
     * where it's registered into the chain) - this @Bean method is what actually
     * constructs it and hands it to Spring's context.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
        return new JwtAuthenticationFilter(jwtService);
    }

    /**
     * BCrypt is the industry-standard adaptive hash for passwords: it's slow by
     * design (tunable work factor) and salts automatically, which is exactly what
     * we want for User.password (see AuthService.register()/login()) - never store
     * or compare plaintext passwords.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Exposes Spring Security's internally-assembled AuthenticationManager as a
     * bean so AuthService can inject it and call .authenticate(...) during login.
     * Spring Boot auto-wires this manager to use CustomUserDetailsService +
     * the PasswordEncoder bean above via a DaoAuthenticationProvider, since exactly
     * one of each is present on the context - no need to construct that provider
     * by hand.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * The actual HTTP security rules. Spring Security 6+/7 style: a lambda-based
     * DSL instead of the older method-chaining `http.authorizeRequests()` API.
     * jwtAuthenticationFilter is taken as a method parameter (rather than a field)
     * so Spring resolves it from the @Bean method above by type - keeps the filter's
     * construction and its registration into the chain in the same, easy-to-find place.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                // CSRF protection exists to stop browsers from silently replaying a
                // logged-in user's session cookie cross-site. We have no cookies/
                // HttpSession at all (pure bearer-token auth), so CSRF tokens would
                // protect nothing here and would only get in the way of API clients.
                .csrf(AbstractHttpConfigurer::disable)

                // No server-side session is ever created or consulted - every
                // request must carry (or not need) its own credentials. This is
                // what makes horizontal scaling trivial: any instance can handle
                // any request without sticky sessions or a shared session store.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Registration, login, and refresh are how a client *obtains*
                        // credentials in the first place, so they can't themselves
                        // require credentials.
                        .requestMatchers("/auth/register", "/auth/login", "/auth/refresh").permitAll()
                        // Swagger/OpenAPI UI + docs - the springdoc dependency
                        // landed in Module 3 (pom.xml), so these paths are now
                        // actually served, not just defensively permitted ahead of
                        // time as the original Module 2 comment here described.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // --- Module 3: Movie/Show/Seat catalog (plan/crud.md section 8) ---
                        // Public reads: GET-only, so a future POST/PUT/DELETE added
                        // under these same paths does NOT accidentally inherit
                        // permitAll just by matching the URL prefix.
                        .requestMatchers(HttpMethod.GET, "/movies", "/movies/**", "/shows/**").permitAll()
                        // Admin writes: coarse URL-level gate, layer one of two -
                        // layer two is @PreAuthorize("hasRole('ADMIN')") on each
                        // controller method itself (MovieController, ShowController,
                        // SeatController). Placed BEFORE anyRequest().authenticated()
                        // below, since Spring Security evaluates authorizeHttpRequests
                        // rules in declaration order and stops at the first match.
                        .requestMatchers("/admin/**").hasRole("ADMIN")

                        // Deliberately NOT included above: /auth/logout requires a
                        // valid access token (see JwtAuthenticationFilter's javadoc
                        // and logic/jwt.md "Decisions") - it falls through to
                        // .anyRequest().authenticated() below like any other
                        // protected route.
                        .anyRequest().authenticated()
                )

                // Registers our filter to run in the same "slot" as Spring
                // Security's own username/password filter, so it participates in
                // the chain at the point where an Authentication would normally be
                // established - but ahead of it, since form-login isn't used here.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                // Replaces Spring Security's default HTML error pages with the JSON
                // ErrorResponse shape used everywhere else in the app (see both
                // handler classes' own javadoc for the 401 vs 403 distinction).
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler)
                );

        return http.build();
    }
}
