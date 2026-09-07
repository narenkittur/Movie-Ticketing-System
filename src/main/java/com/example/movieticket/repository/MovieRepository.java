package com.example.movieticket.repository;

import com.example.movieticket.model.Movie;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
// JpaRepository<Movie, Long> already brings a paged findAll(Pageable) for free
// (it extends PagingAndSortingRepository under the hood) - that's what backs the
// "no search term" branch of GET /movies (Module 3, plan/crud.md section 5.1).
public interface MovieRepository extends JpaRepository<Movie, Long> {

    // Find movies by title (exact match)
    List<Movie> findByTitle(String title);

    // Find movies where the title contains the search string (great for search bars)
    List<Movie> findByTitleContainingIgnoreCase(String title);

    // Paged variant of the search above (Module 3): Spring Data recognizes the
    // Pageable parameter automatically and returns a Page<Movie> carrying
    // content + totalElements + totalPages, instead of loading every match into
    // memory at once - this is what backs GET /movies?search=... .
    Page<Movie> findByTitleContainingIgnoreCase(String title, Pageable pageable);
}