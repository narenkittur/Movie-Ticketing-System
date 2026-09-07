package com.example.movieticket.controller;

import com.example.movieticket.dto.MovieRequest;
import com.example.movieticket.dto.MovieResponse;
import com.example.movieticket.service.MovieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Movie half of Module 3 (plan/crud.md endpoints #1, #2, #5). Thin per
 * claude.md's "decoupling" principle - every method just validates the request
 * shape and delegates straight to MovieService.
 */
@RestController
// @Tag groups these endpoints under one heading in the generated Swagger UI -
// purely documentation, no runtime effect.
@Tag(name = "Movies", description = "Admin movie management and public movie catalog")
public class MovieController {

    private final MovieService movieService;

    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    /**
     * POST /admin/movies - admin only. @PreAuthorize is the SECOND layer of
     * defense here; the FIRST is the coarse "/admin/**" URL matcher in
     * SecurityConfig (plan/crud.md section 8) - either one alone would reject a
     * non-admin caller, but both together mean a mistake in one doesn't leave the
     * endpoint open.
     */
    @PostMapping("/admin/movies")
    @PreAuthorize("hasRole('ADMIN')") // Requires @EnableMethodSecurity on SecurityConfig to actually take effect - see that class's javadoc.
    @Operation(summary = "Create a movie", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MovieResponse> createMovie(@Valid @RequestBody MovieRequest request) {
        MovieResponse response = movieService.createMovie(request);
        // 201 Created + Location header pointing at the new resource - standard
        // REST convention for a successful POST that creates something.
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/movies/" + response.getId())
                .body(response);
    }

    /** PUT /admin/movies/{movieId} - admin only. Full replace of the mutable movie fields. */
    @PutMapping("/admin/movies/{movieId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a movie", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MovieResponse> updateMovie(@PathVariable Long movieId, @Valid @RequestBody MovieRequest request) {
        MovieResponse response = movieService.updateMovie(movieId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /movies?search=&page=&size=&sort= - public, paginated. `Pageable` is
     * resolved automatically from those query parameters by Spring Data's web
     * support (auto-configured because spring-boot-starter-data-jpa is on the
     * classpath) - no manual parsing needed here.
     */
    @GetMapping("/movies")
    @Operation(summary = "List or search movies")
    public ResponseEntity<Page<MovieResponse>> listMovies(
            @RequestParam(required = false) String search,
            // Default: 20 per page, sorted by title ascending, if the client
            // supplies no page/size/sort query params at all.
            @PageableDefault(size = 20, sort = "title") Pageable pageable) {
        Page<MovieResponse> response = movieService.listMovies(search, pageable);
        return ResponseEntity.ok(response);
    }
}
