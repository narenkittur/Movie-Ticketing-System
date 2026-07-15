package com.example.movieticket.service;

import com.example.movieticket.dto.AuthResponse;
import com.example.movieticket.dto.LoginRequest;
import com.example.movieticket.dto.RegisterRequest;
import com.example.movieticket.exception.InvalidRefreshTokenException;
import com.example.movieticket.model.RefreshToken;
import com.example.movieticket.model.User;
import com.example.movieticket.repository.RefreshTokenRepository;
import com.example.movieticket.repository.UserRepository;
import com.example.movieticket.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Owns every piece of business logic behind the four /auth endpoints. Controllers
 * stay thin (per claude.md's "decoupling" principle) - AuthController just
 * validates the request shape and delegates here.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    // How long a freshly-issued refresh token lives, in milliseconds - read from
    // config (application.properties: jwt.refresh-token-expiration-ms) rather than
    // hardcoded, matching how JwtService reads the access-token TTL.
    @Value("${jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    // How long an access token lives, in milliseconds - reused here purely to
    // compute AuthResponse.expiresIn (in seconds) for the client; JwtService itself
    // still owns the value that actually gets baked into the JWT's "exp" claim.
    @Value("${jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    public AuthService(UserRepository userRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        PasswordEncoder passwordEncoder,
                        AuthenticationManager authenticationManager,
                        JwtService jwtService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /**
     * Creates a new ROLE_USER account. Note there is no token issued here - the
     * client must call /auth/login separately afterward (see the sequence diagram
     * in plan/authentication.md section 4.1). Keeping registration and token
     * issuance as two separate steps means a compromised/leaked registration
     * response can never itself grant access.
     */
    @Transactional // Wraps the existsBy checks + save in one transaction/connection; doesn't by itself close the TOCTOU race below - see the comment on save().
    public void register(RegisterRequest request) {
        // Cheap early exit for the common case (typo'd duplicate username/email) -
        // gives a fast, friendly rejection *most* of the time. This is NOT the
        // authoritative guard: see the DataIntegrityViolationException comment below.
        if (userRepository.existsByUsername(request.getUsername())) {
            log.warn("Registration rejected: username '{}' already exists", request.getUsername());
            throw new DataIntegrityViolationException("Username already exists");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("Registration rejected: email already in use");
            throw new DataIntegrityViolationException("Email already in use");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                // BCrypt hash, never the raw password - see SecurityConfig.passwordEncoder().
                .password(passwordEncoder.encode(request.getPassword()))
                // Server-forced, always ROLE_USER. RegisterRequest has no `role`
                // field for a client to influence (see that class's javadoc) - this
                // literal is the ONLY place a role gets assigned during registration.
                .role("ROLE_USER")
                .build();

        // Issue #1 (plan/authentication.md, "Production-grade issues"): two
        // concurrent registrations with the same username can both pass the
        // existsByUsername() check above before either INSERT commits (classic
        // TOCTOU race). The real guard is the UNIQUE constraint on users.username/
        // email at the DB level - if this save() violates it, Hibernate/JDBC throws
        // DataIntegrityViolationException, which we deliberately do NOT catch here.
        // It propagates up to GlobalExceptionHandler, which turns it into a clean
        // 409 Conflict instead of a raw 500.
        userRepository.save(user);
        log.info("Registered new user '{}'", user.getUsername());
    }

    /**
     * Authenticates credentials and issues a fresh access + refresh token pair.
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Delegates the actual credential check to Spring Security:
        // AuthenticationManager -> DaoAuthenticationProvider -> CustomUserDetailsService
        // (loads the user) -> PasswordEncoder.matches() (compares hashes). Throws
        // BadCredentialsException on any mismatch, caught by GlobalExceptionHandler.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        // Authentication succeeded, so this user is guaranteed to exist - re-fetch
        // our own domain User (rather than reusing Spring Security's generic
        // UserDetails) because JwtService.generateAccessToken() needs the actual
        // entity to read its role/username off of.
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalStateException(
                        "User authenticated but missing from DB: " + request.getUsername()));

        AuthResponse response = issueTokenPair(user);
        log.info("User '{}' logged in successfully", user.getUsername());
        return response;
    }

    /**
     * Exchanges a valid, unrevoked, unexpired refresh token for a new access token,
     * rotating the refresh token in the process (old one revoked, brand-new one issued).
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        String tokenHash = jwtService.hashToken(rawRefreshToken);

        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token not recognized"));

        if (storedToken.isRevoked()) {
            // Rotation theft signal (plan section 4.4): if a refresh token gets
            // reused after it was already rotated away, that means either the
            // legitimate client retried a stale token, or an attacker who stole an
            // earlier token is now replaying it. We can't tell which from here, so
            // it's only logged as a WARN (not auto-acted-on) rather than e.g.
            // proactively revoking the whole family - flagged in the plan as a
            // v2 improvement once there's a place to route a real security alert.
            log.warn("Attempted reuse of an already-revoked refresh token for user '{}'", storedToken.getUser().getUsername());
            throw new InvalidRefreshTokenException("Refresh token has been revoked");
        }

        if (storedToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        // Rotate: kill the old token so it can never be exchanged again, whether or
        // not the caller ever uses the new one.
        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        AuthResponse response = issueTokenPair(storedToken.getUser());
        log.info("Refreshed tokens for user '{}'", storedToken.getUser().getUsername());
        return response;
    }

    /**
     * Revokes a refresh token so it can no longer be used to mint new access
     * tokens. Deliberately idempotent/silent if the token is already unknown or
     * already revoked - logout should never leak whether a given token was "real"
     * to a caller who's already past the access-token check to reach this endpoint.
     *
     * NOTE: this does NOT invalidate the access token that was used to authenticate
     * *this very call* - that token remains valid until it naturally expires. This
     * is a deliberate, documented tradeoff of pure statelessness (Issue #2 in the
     * plan), bounded by keeping access tokens short-lived (30 min default).
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        String tokenHash = jwtService.hashToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            log.info("Revoked refresh token for user '{}'", token.getUser().getUsername());
        });
    }

    /** Shared by login() and refresh(): builds a brand-new access+refresh pair and persists the refresh token row. */
    private AuthResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = jwtService.generateRefreshToken();

        RefreshToken tokenEntity = RefreshToken.builder()
                .tokenHash(jwtService.hashToken(rawRefreshToken)) // Only the hash is persisted - see RefreshToken's javadoc.
                .user(user)
                .expiryDate(LocalDateTime.now().plus(Duration.ofMillis(refreshTokenExpirationMs)))
                .createdAt(LocalDateTime.now())
                .build();
        refreshTokenRepository.save(tokenEntity);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken) // Raw value returned to the client exactly once, at issuance - never stored, never logged.
                .tokenType("Bearer")
                .expiresIn(accessTokenExpirationMs / 1000) // ms -> seconds for the client-facing field.
                .build();
    }
}
