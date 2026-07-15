package com.example.movieticket.security;

import com.example.movieticket.model.User;
// io.jsonwebtoken (JJWT) is the library that actually builds/signs/parses/verifies
// JWTs. jjwt-api is the compile-time API; jjwt-impl + jjwt-jackson (runtime-scoped
// in pom.xml) provide the actual implementation and JSON (de)serialization - we
// never import those two directly, JJWT wires them in via ServiceLoader.
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/**
 * Everything that touches raw JWT bytes or the opaque refresh-token secret lives
 * here, and nowhere else. Two very different kinds of "token" are handled by this
 * one class - see plan/authentication.md section 6 and Issue #3:
 *
 *  - Access token: a real signed JWT (JJWT-built), self-contained (carries username
 *    + role as claims), short-lived, never touches the DB after issuance. The
 *    filter trusts its signature instead of re-querying the DB on every request.
 *  - Refresh token: a long, random, opaque string (SecureRandom, NOT a JWT). It
 *    carries no claims of its own - it's just a high-entropy lookup key. The only
 *    thing that gives it meaning is the DB row found via its hash (see RefreshToken).
 */
@Service // Registers this class as a Spring bean so it can be @Autowired/constructor-injected elsewhere.
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    // Number of random bytes used to build the opaque refresh token. 64 bytes = 512
    // bits of entropy, comfortably more than enough to make guessing infeasible.
    private static final int REFRESH_TOKEN_BYTE_LENGTH = 64;

    // A single shared SecureRandom instance - cryptographically strong PRNG, safe
    // for concurrent use from multiple request threads (its internal methods are
    // synchronized), so one instance can be reused rather than constructing a new
    // one per call.
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Injected from application.properties (jwt.secret). Must be a Base64-encoded
    // string of at least 32 bytes (256 bits) so it's usable as an HS256 signing key.
    // Read via @Value rather than hardcoded so the real deployment secret only ever
    // lives in an environment variable, never in source control.
    @Value("${jwt.secret}")
    private String jwtSecret;

    // How long an access token stays valid, in milliseconds. Read from config so
    // it can be tuned per-environment without a code change/redeploy.
    @Value("${jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    /**
     * Builds the HMAC-SHA signing key from the configured Base64 secret. Recomputed
     * per call rather than cached in a field - this is a cheap decode-and-wrap
     * operation, and avoids caching a key derived from a value that (in theory)
     * could be reloaded at runtime with @RefreshScope in a future iteration.
     */
    private SecretKey signingKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Issues a new signed access token for the given user. Embeds username (as the
     * standard "sub" claim) and role (as a custom "role" claim) directly in the
     * token so JwtAuthenticationFilter can authorize a request without a DB
     * round-trip - see Issue #3 in the plan.
     */
    public String generateAccessToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirationMs);

        String token = Jwts.builder()
                .subject(user.getUsername())      // "sub" claim - JJWT 0.12's fluent builder (replaces the old setSubject()).
                .claim("role", user.getRole())    // Custom claim carrying e.g. "ROLE_USER" / "ROLE_ADMIN".
                .issuedAt(now)                     // "iat" claim.
                .expiration(expiry)                // "exp" claim - JJWT rejects the token automatically once this passes.
                .signWith(signingKey())            // HMAC-SHA256 signature (key length picks the exact algorithm variant).
                .compact();                        // Serializes to the final "header.payload.signature" string.

        log.info("Issued access token for user '{}' (role={}, expiresAt={})", user.getUsername(), user.getRole(), expiry);
        return token;
    }

    /**
     * Generates a brand-new opaque refresh token. Deliberately NOT a JWT - it's
     * just random bytes, Base64url-encoded so it's safe to put in JSON/URLs. The
     * server never needs to "read" anything out of it; it only ever looks it up by
     * its hash in the refresh_tokens table (see AuthService).
     */
    public String generateRefreshToken() {
        byte[] randomBytes = new byte[REFRESH_TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);
        // URL-safe, no padding, so the token can be dropped straight into a JSON
        // string or a query param without further escaping.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * One-way SHA-256 hash of a raw token (used for both refresh tokens here).
     * We store only this hash in the DB - never the raw token - identically to how
     * User.password stores a BCrypt hash, not the plaintext password.
     */
    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes); // Renders the hash as a lowercase hex string for easy storage/comparison.
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandatory algorithm (every conforming JVM ships it),
            // so this branch is unreachable in practice - but the checked exception
            // still has to be handled to compile.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Parses the JWT and returns its claims, verifying the signature in the
     * process. Package-private: only this class and its validity checks need raw
     * Claims access; callers outside the package go through extractUsername/
     * extractRole/isAccessTokenValid instead.
     */
    private Claims parseClaims(String jwt) {
        return Jwts.parser()               // JJWT 0.12's parser builder (replaces the old parserBuilder()).
                .verifyWith(signingKey())  // Supplies the key used to check the signature (replaces setSigningKey()).
                .build()
                .parseSignedClaims(jwt)    // Throws JwtException/subclasses if signature, format, or expiry is invalid.
                .getPayload();             // The verified Claims body.
    }

    /** Extracts the username ("sub" claim) from an access token. Assumes the token was already validated. */
    public String extractUsername(String jwt) {
        return parseClaims(jwt).getSubject();
    }

    /** Extracts the role claim (e.g. "ROLE_USER") from an access token. Assumes the token was already validated. */
    public String extractRole(String jwt) {
        return parseClaims(jwt).get("role", String.class);
    }

    /**
     * Returns whether an access token is structurally valid, correctly signed, and
     * not expired. Deliberately swallows every JwtException/IllegalArgumentException
     * and returns false instead of throwing - JwtAuthenticationFilter relies on this
     * never throwing so it can always fall through to filterChain.doFilter() and let
     * Spring Security's entry point decide the response for an unauthenticated request.
     */
    public boolean isAccessTokenValid(String jwt) {
        try {
            parseClaims(jwt); // Throws if signature is wrong, token is malformed, or "exp" has passed.
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException covers ExpiredJwtException, MalformedJwtException,
            // SignatureException, UnsupportedJwtException, etc. IllegalArgumentException
            // covers a null/blank token string being passed in.
            log.warn("Rejected invalid access token: {}", e.getMessage());
            return false;
        }
    }
}
