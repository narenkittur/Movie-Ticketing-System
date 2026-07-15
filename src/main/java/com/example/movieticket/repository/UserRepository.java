package com.example.movieticket.repository;

import com.example.movieticket.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    // Spring Data JPA automatically generates the SQL query for this!
    // It will look for a user where the 'username' column matches the provided string.
    Optional<User> findByUsername(String username);

    // You can also add other useful lookup methods:
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}