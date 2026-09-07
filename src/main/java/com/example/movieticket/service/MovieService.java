package com.example.movieticket.service;

import com.example.movieticket.dto.MovieRequest;
import com.example.movieticket.dto.MovieResponse;
import com.example.movieticket.exception.MovieNotFoundException;
import com.example.movieticket.model.Movie;
import com.example.movieticket.repository.MovieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Business logic behind the Movie half of Module 3 (plan/crud.md endpoints #1, #2,
 * #5). MovieController stays thin and just delegates here, per claude.md's
 * "decoupling" principle - same shape as AuthService in Module 2.
 */
@Service // Registers this class as a Spring-managed singleton bean, picked up by component scanning and injected wherever a MovieService is asked for (here: MovieController).
public class MovieService {

    // SLF4J logger, one per class (claude.md constraint: "every service method
    // gets basic SLF4J logging") - static+final so it's created once per class,
    // not once per instance.
    private static final Logger log = LoggerFactory.getLogger(MovieService.class);

    private final MovieRepository movieRepository;

    // Constructor injection (no @Autowired needed - Spring auto-wires the sole
    // constructor of a @Service bean) - same style as AuthService, and preferred
    // over field injection because it makes the dependency final/immutable and
    // testable without reflection.
    public MovieService(MovieRepository movieRepository) {
        this.movieRepository = movieRepository;
    }

    /**
     * POST /admin/movies. Movie titles are NOT required to be unique (see
     * plan/crud.md section 11-C: remakes and re-releases legitimately share a
     * title, so we disambiguate by id rather than rejecting duplicates).
     */
    @Transactional // Wraps the save() in a transaction; mainly here for consistency with the rest of the codebase since a single insert doesn't itself need multi-statement atomicity.
    public MovieResponse createMovie(MovieRequest request) {
        Movie movie = Movie.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .durationMinutes(request.getDurationMinutes())
                .build();

        Movie saved = movieRepository.save(movie);
        log.info("Created movie '{}' (id={})", saved.getTitle(), saved.getId());
        return toResponse(saved);
    }

    /**
     * PUT /admin/movies/{movieId}. Full replace of title/description/duration -
     * see MovieRequest's javadoc for why this isn't a partial PATCH.
     */
    @Transactional
    public MovieResponse updateMovie(Long movieId, MovieRequest request) {
        // findById + orElseThrow, not getReferenceById: we need the real row (and
        // a 404 if it's missing) before mutating it, not a lazy proxy.
        Movie movie = movieRepository.findById(movieId)
                .orElseThrow(() -> new MovieNotFoundException("No movie found with id " + movieId));

        // Mutate the MANAGED entity in place (do not `new Movie(...)` and re-save
        // with the same id) - JPA's dirty-checking flushes these field changes at
        // transaction commit, and re-constructing the entity would risk detaching
        // it from its `shows` collection, which this DTO doesn't even carry.
        movie.setTitle(request.getTitle());
        movie.setDescription(request.getDescription());
        movie.setDurationMinutes(request.getDurationMinutes());

        Movie saved = movieRepository.save(movie);
        log.info("Updated movie id={}", saved.getId());
        return toResponse(saved);
    }

    /**
     * GET /movies?search=&page=&size=. Blank/absent search returns every movie
     * (paged); a non-blank search filters by case-insensitive title substring
     * match. Returning a Spring Page (not a List) gives the client
     * totalElements/totalPages for free (plan/crud.md section 5.1).
     */
    @Transactional(readOnly = true) // readOnly=true lets Hibernate skip dirty-checking on the loaded entities - a small optimization for a query that never writes.
    public Page<MovieResponse> listMovies(String search, Pageable pageable) {
        Page<Movie> page = StringUtils.hasText(search)
                ? movieRepository.findByTitleContainingIgnoreCase(search, pageable)
                : movieRepository.findAll(pageable);

        // Page.map() transforms each element while preserving the page's
        // pagination metadata (totalElements, totalPages, etc.) - avoids manually
        // rebuilding a new PageImpl from scratch.
        return page.map(this::toResponse);
    }

    /**
     * Package-private lookup shared with ShowService, which needs the parent
     * Movie (and its durationMinutes, for the screen-overlap check) when creating
     * a show. Kept here rather than duplicated in ShowService so "movie not
     * found" always produces the exact same exception/message.
     */
    Movie getMovieOrThrow(Long movieId) {
        return movieRepository.findById(movieId)
                .orElseThrow(() -> new MovieNotFoundException("No movie found with id " + movieId));
    }

    // Hand-written entity -> DTO mapping (see claude.md's note on the MapStruct
    // decision for Module 3: kept hand-written rather than adding the MapStruct
    // annotation-processor dependency, since a mapping this small stays more
    // readable - and more easily commentable line-by-line - as a plain method).
    private MovieResponse toResponse(Movie movie) {
        return MovieResponse.builder()
                .id(movie.getId())
                .title(movie.getTitle())
                .description(movie.getDescription())
                .durationMinutes(movie.getDurationMinutes())
                .build();
    }
}
