package com.example.movieticket.repository;

import com.example.movieticket.model.RefreshToken;
import com.example.movieticket.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// @Repository lets Spring pick this interface up as a bean (and translates any
// raw JDBC/Hibernate exceptions into Spring's DataAccessException hierarchy).
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // Spring Data JPA derives the SQL from the method name: "find the row whose
    // token_hash column equals the given hash". Used by AuthService.refresh()/logout()
    // to look up a presented refresh token by its hash (never by the raw value).
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // Used for a future "logout of all devices" / account-deletion flow: wipes every
    // refresh token belonging to a user in one statement instead of one-by-one.
    void deleteAllByUser(User user);

    // Optional per the plan: powers a future "active sessions" screen that lists
    // every refresh token for a user that hasn't been revoked yet.
    List<RefreshToken> findAllByUserAndRevokedFalse(User user);
}
