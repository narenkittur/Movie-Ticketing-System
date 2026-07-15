package com.example.movieticket.model;

// jakarta.persistence.* gives us JPA annotations (@Entity, @Table, @Id, etc.)
// so Hibernate knows how to map this class onto a database table.
import jakarta.persistence.*;
// lombok.* generates getters/setters/constructors/builder at compile time so we
// don't have to hand-write boilerplate (see the class-level annotations below).
import lombok.*;
import java.time.LocalDateTime;

/**
 * Durable, DB-backed record of an issued refresh token.
 *
 * We never store the raw opaque refresh token string - only a SHA-256 hash of it
 * (see JwtService#hashToken). This mirrors password hashing: if the `refresh_tokens`
 * table were ever leaked, an attacker still couldn't present a usable token because
 * they'd only have the hash, not the original value.
 */
@Entity // Tells Spring Data JPA/Hibernate "this class maps to a DB table".
@Table(name = "refresh_tokens") // Explicit table name (snake_case, matches project convention).
@Data // Lombok: generates getters, setters, toString, equals/hashCode for all fields.
@NoArgsConstructor // Lombok: generates a public no-arg constructor (required by JPA/Hibernate).
@AllArgsConstructor // Lombok: generates a constructor taking every field (handy in tests).
@Builder // Lombok: generates a fluent builder, e.g. RefreshToken.builder().user(u).build().
public class RefreshToken {

    @Id // Marks this field as the table's primary key.
    @GeneratedValue(strategy = GenerationType.IDENTITY) // DB auto-increments the id (matches other entities in this project).
    private Long id;

    // SHA-256 hex digest of the raw opaque refresh token. Unique so two tokens can
    // never collide, and indexed implicitly by the unique constraint for fast lookup
    // in AuthService.refresh()/logout().
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    // Owning user. Many refresh tokens can belong to one user (e.g. logged in on
    // multiple devices), hence @ManyToOne rather than @OneToOne.
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false) // FK column in refresh_tokens pointing at users.id
    private User user;

    // When this refresh token stops being valid, regardless of revoked status.
    // Plan: issued_at + 7 days (see AuthService).
    @Column(name = "expiry_date", nullable = false)
    private LocalDateTime expiryDate;

    // Flipped to true on logout, or automatically when a new token is issued to
    // replace this one (rotation - see AuthService.refresh()). A revoked token can
    // never be exchanged for a new access token again, even if not yet expired.
    @Column(nullable = false)
    @Builder.Default // Lombok's @Builder normally leaves un-set fields null; this keeps the "false" default even when built via the builder.
    private boolean revoked = false;

    // Audit trail / lets a future cleanup job purge old rows (e.g. "delete anything
    // older than 30 days"), independent of expiry_date.
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
