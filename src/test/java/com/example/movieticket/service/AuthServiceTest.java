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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService's business rules, with all collaborators mocked
 * (this is service-layer logic, not a DB/Spring-Security integration test - the
 * things being verified are AuthService's own decisions, not whether Hibernate or
 * DaoAuthenticationProvider work correctly). See logic/jwt.md ("Test case
 * rationale") for the narrative explanation of why each scenario is here.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, authenticationManager, jwtService);
        ReflectionTestUtils.setField(authService, "refreshTokenExpirationMs", 604_800_000L); // 7 days
        ReflectionTestUtils.setField(authService, "accessTokenExpirationMs", 1_800_000L);    // 30 min
    }

    @Test
    void register_forcesRoleUser_regardlessOfInput() {
        // Why this matters: RegisterRequest has no `role` field at all (see that
        // class's javadoc), but this test documents/locks in the actual security
        // invariant - that AuthService.register() is the one and only place a
        // role gets assigned, and it always hardcodes ROLE_USER.
        RegisterRequest request = new RegisterRequest("newuser", "newuser@test.com", "password123");
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("newuser@test.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");

        authService.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("ROLE_USER", saved.getRole());
        assertEquals("hashed-password", saved.getPassword()); // Never the raw password.
    }

    @Test
    void register_preCheckCatchesObviousDuplicate_withoutHittingTheDatabaseSave() {
        // Why this matters: this is the "cheap early exit" half of Issue #1's
        // mitigation - the common case (not a race) should fail fast without an
        // INSERT attempt at all.
        RegisterRequest request = new RegisterRequest("existing", "existing@test.com", "password123");
        when(userRepository.existsByUsername("existing")).thenReturn(true);

        assertThrows(DataIntegrityViolationException.class, () -> authService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_letsDbConstraintViolationPropagate_forConcurrentDuplicateRace() {
        // Why this matters: this is the *authoritative* half of Issue #1's
        // mitigation. Simulates two requests racing past the existsByUsername()
        // pre-check (both see `false`) before either commits - the second INSERT
        // hits the DB's UNIQUE constraint and Hibernate throws
        // DataIntegrityViolationException. AuthService must NOT catch this itself;
        // it has to propagate up to GlobalExceptionHandler, which is what turns it
        // into a clean 409 instead of a raw 500.
        RegisterRequest request = new RegisterRequest("racer", "racer@test.com", "password123");
        when(userRepository.existsByUsername("racer")).thenReturn(false);
        when(userRepository.existsByEmail("racer@test.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThrows(DataIntegrityViolationException.class, () -> authService.register(request));
    }

    @Test
    void login_withBadCredentials_neverReachesTokenIssuance() {
        // Why this matters: proves a failed AuthenticationManager.authenticate()
        // call short-circuits before any token is generated - otherwise a caller
        // could get a valid AuthResponse issued alongside/despite a rejected login.
        LoginRequest request = new LoginRequest("alice", "wrongpassword");
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThrows(BadCredentialsException.class, () -> authService.login(request));
        verifyNoInteractions(jwtService);
    }

    @Test
    void login_onSuccess_issuesAccessAndRefreshTokenAndPersistsOnlyTheHash() {
        // Why this matters: the raw refresh token must be returned to the client
        // exactly once (in the response) but the DB row must only ever contain its
        // hash - this test would fail if AuthService ever accidentally persisted
        // the raw token instead of (or in addition to) the hash.
        LoginRequest request = new LoginRequest("alice", "correctpassword");
        User user = User.builder().id(1L).username("alice").role("ROLE_USER").build();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("fake.access.token");
        when(jwtService.generateRefreshToken()).thenReturn("raw-refresh-token");
        when(jwtService.hashToken("raw-refresh-token")).thenReturn("hashed-refresh-token");

        AuthResponse response = authService.login(request);

        assertEquals("fake.access.token", response.getAccessToken());
        assertEquals("raw-refresh-token", response.getRefreshToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(1800L, response.getExpiresIn()); // 1_800_000 ms -> 1800 s

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertEquals("hashed-refresh-token", captor.getValue().getTokenHash());
        assertFalse(captor.getValue().isRevoked());
    }

    @Test
    void refresh_withRevokedToken_isRejectedAndNoNewTokenIssued() {
        // Why this matters: this is the core defense against refresh-token replay
        // after rotation (plan section 4.4) - a revoked token must never mint a new
        // access token, no matter how "valid-looking" (unexpired) it otherwise is.
        User user = User.builder().id(1L).username("alice").role("ROLE_USER").build();
        RefreshToken revoked = RefreshToken.builder()
                .id(10L).user(user).tokenHash("hash-of-old-token")
                .expiryDate(LocalDateTime.now().plusDays(1))
                .revoked(true)
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();

        when(jwtService.hashToken("old-token")).thenReturn("hash-of-old-token");
        when(refreshTokenRepository.findByTokenHash("hash-of-old-token")).thenReturn(Optional.of(revoked));

        assertThrows(InvalidRefreshTokenException.class, () -> authService.refresh("old-token"));
        verify(jwtService, never()).generateAccessToken(any());
    }

    @Test
    void refresh_withExpiredToken_isRejected() {
        User user = User.builder().id(1L).username("alice").role("ROLE_USER").build();
        RefreshToken expired = RefreshToken.builder()
                .id(11L).user(user).tokenHash("hash-of-expired")
                .expiryDate(LocalDateTime.now().minusMinutes(1)) // expired one minute ago
                .revoked(false)
                .createdAt(LocalDateTime.now().minusDays(8))
                .build();

        when(jwtService.hashToken("expired-token")).thenReturn("hash-of-expired");
        when(refreshTokenRepository.findByTokenHash("hash-of-expired")).thenReturn(Optional.of(expired));

        assertThrows(InvalidRefreshTokenException.class, () -> authService.refresh("expired-token"));
    }

    @Test
    void refresh_withValidToken_rotatesOldTokenAndIssuesNewPair() {
        // Why this matters: rotation means the OLD row must be marked revoked=true
        // as a side effect of a successful refresh, not just left alone - otherwise
        // a leaked-but-not-yet-used old refresh token would remain valid forever
        // instead of being single-use.
        User user = User.builder().id(1L).username("alice").role("ROLE_USER").build();
        RefreshToken valid = RefreshToken.builder()
                .id(12L).user(user).tokenHash("hash-of-valid")
                .expiryDate(LocalDateTime.now().plusDays(1))
                .revoked(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(jwtService.hashToken("valid-token")).thenReturn("hash-of-valid");
        when(refreshTokenRepository.findByTokenHash("hash-of-valid")).thenReturn(Optional.of(valid));
        when(jwtService.generateAccessToken(user)).thenReturn("new.access.token");
        when(jwtService.generateRefreshToken()).thenReturn("new-raw-refresh-token");
        when(jwtService.hashToken("new-raw-refresh-token")).thenReturn("hash-of-new-token");

        AuthResponse response = authService.refresh("valid-token");

        assertEquals("new.access.token", response.getAccessToken());
        assertEquals("new-raw-refresh-token", response.getRefreshToken());
        assertTrue(valid.isRevoked(), "the old token row must be revoked after a successful rotation");

        // Saved twice: once to persist the old row's revoked=true flip, once to
        // persist the brand-new refresh token row.
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
    }

    @Test
    void logout_withUnknownToken_isSilentlyANoOp() {
        // Why this matters: logout must not leak whether a given refresh token
        // ever existed (see AuthService.logout() javadoc) - an unknown token should
        // behave identically to a successfully-revoked one from the caller's
        // perspective (204 either way), never a 404/error.
        when(jwtService.hashToken("never-issued")).thenReturn("hash-of-nothing");
        when(refreshTokenRepository.findByTokenHash("hash-of-nothing")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> authService.logout("never-issued"));
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void logout_withKnownToken_marksItRevoked() {
        User user = User.builder().id(1L).username("alice").role("ROLE_USER").build();
        RefreshToken token = RefreshToken.builder()
                .id(13L).user(user).tokenHash("hash-of-live-token")
                .expiryDate(LocalDateTime.now().plusDays(1))
                .revoked(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(jwtService.hashToken("live-token")).thenReturn("hash-of-live-token");
        when(refreshTokenRepository.findByTokenHash("hash-of-live-token")).thenReturn(Optional.of(token));

        authService.logout("live-token");

        assertTrue(token.isRevoked());
        verify(refreshTokenRepository).save(token);
    }
}
