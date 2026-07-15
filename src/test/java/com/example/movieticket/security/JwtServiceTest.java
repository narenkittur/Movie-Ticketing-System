package com.example.movieticket.security;

import com.example.movieticket.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the trickiest logic in this module: JWT issuance/validation and
 * the opaque-refresh-token/hash pairing. See logic/jwt.md ("Test case rationale")
 * for the plain-English explanation of *why* each of these matters, not just what
 * it asserts.
 *
 * @Value fields (jwtSecret, accessTokenExpirationMs) are normally populated by
 * Spring from application.properties - since this is a plain unit test (no
 * ApplicationContext), we set them directly via ReflectionTestUtils instead of
 * spinning up a full Spring context just to test pure token logic.
 */
class JwtServiceTest {

    private JwtService jwtService;

    // A valid Base64-encoded 256-bit key, same shape as the real config value -
    // arbitrary but fixed so test runs are deterministic.
    private static final String TEST_SECRET = "MVynaSNpUoMB5ZFtE63tgPiBTJcrF6A30gwnIfJ2V+Q=";

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "jwtSecret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "accessTokenExpirationMs", 1_800_000L); // 30 min
    }

    @Test
    void generateAccessToken_roundTrip_extractsOriginalUsernameAndRole() {
        // Why this matters: the whole point of embedding claims in the JWT is that
        // JwtAuthenticationFilter can reconstruct the caller's identity/role with
        // zero DB calls (Issue #3 in the plan). If round-tripping ever breaks,
        // every authenticated request breaks with it.
        User user = User.builder().id(1L).username("alice").role("ROLE_ADMIN").build();

        String token = jwtService.generateAccessToken(user);

        assertTrue(jwtService.isAccessTokenValid(token));
        assertEquals("alice", jwtService.extractUsername(token));
        assertEquals("ROLE_ADMIN", jwtService.extractRole(token));
    }

    @Test
    void isAccessTokenValid_returnsFalse_forExpiredToken() {
        // Why this matters: this is the mechanism the whole "30-minute exposure
        // window" mitigation (Issue #2) depends on. If expired tokens were ever
        // accepted, that mitigation would be worthless.
        ReflectionTestUtils.setField(jwtService, "accessTokenExpirationMs", -1_000L); // already expired the instant it's issued
        User user = User.builder().id(1L).username("bob").role("ROLE_USER").build();

        String token = jwtService.generateAccessToken(user);

        assertFalse(jwtService.isAccessTokenValid(token));
    }

    @Test
    void isAccessTokenValid_returnsFalse_forTamperedSignature() {
        // Why this matters: proves an attacker can't hand-edit a token's payload
        // (e.g. change "role":"ROLE_USER" to "ROLE_ADMIN") without invalidating the
        // signature - the entire security guarantee of using signed JWTs instead of
        // plain Base64-encoded claims.
        User user = User.builder().id(1L).username("carol").role("ROLE_USER").build();
        String token = jwtService.generateAccessToken(user);

        // Flip the last character of the signature segment to corrupt it.
        String tampered = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');

        assertFalse(jwtService.isAccessTokenValid(tampered));
    }

    @Test
    void isAccessTokenValid_returnsFalse_forMalformedToken() {
        // Why this matters: isAccessTokenValid() must never throw - the filter
        // relies on that guarantee to always safely fall through to
        // filterChain.doFilter() (see JwtAuthenticationFilter). A garbage string
        // is the simplest possible malformed input.
        assertFalse(jwtService.isAccessTokenValid("not-a-real-jwt"));
    }

    @Test
    void generateRefreshToken_producesHighEntropyUniqueValues() {
        // Why this matters: the refresh token is a bare lookup key with no
        // signature protecting it - its only defense against guessing is sheer
        // randomness. Two calls must never collide, and each value should be a
        // reasonably long, URL-safe string (no signature verification is possible
        // for an opaque token, unlike an access token).
        String tokenA = jwtService.generateRefreshToken();
        String tokenB = jwtService.generateRefreshToken();

        assertNotEquals(tokenA, tokenB);
        assertTrue(tokenA.length() > 40);
    }

    @Test
    void hashToken_isDeterministicAndOneWay() {
        // Why this matters: AuthService looks up a presented refresh token by
        // re-hashing it and comparing against the stored hash (findByTokenHash) -
        // this only works if hashing the same input always produces the same
        // output, and two different inputs don't collide.
        String raw = jwtService.generateRefreshToken();

        String hash1 = jwtService.hashToken(raw);
        String hash2 = jwtService.hashToken(raw);

        assertEquals(hash1, hash2);
        assertNotEquals(raw, hash1);
        assertEquals(64, hash1.length()); // SHA-256 -> 32 bytes -> 64 hex characters
    }
}
