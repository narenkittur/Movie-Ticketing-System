package com.example.movieticket.repository;

import com.example.movieticket.model.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MovieRepository extends JpaRepository<Movie, Long> {

    // Find movies by title (exact match)
    List<Movie> findByTitle(String title);

    // Find movies where the title contains the search string (great for search bars)
    List<Movie> findByTitleContainingIgnoreCase(String title);
}