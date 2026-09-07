package com.example.movieticket.controller;

import com.example.movieticket.dto.ShowRequest;
import com.example.movieticket.dto.ShowResponse;
import com.example.movieticket.service.ShowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Show half of Module 3 (plan/crud.md endpoints #3, #6). Nested under
 * /admin/movies/{movieId}/shows and /movies/{movieId}/shows respectively, since a
 * Show cannot exist without a parent Movie (Show.movie is a NOT NULL FK) - the URL
 * shape mirrors that ownership (plan/crud.md section 2's routing convention).
 */
@RestController
@Tag(name = "Shows", description = "Admin show scheduling and public show listings")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    /** POST /admin/movies/{movieId}/shows - admin only. See ShowService.createShow() for the screen-overlap rule. */
    @PostMapping("/admin/movies/{movieId}/shows")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Schedule a show for a movie", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ShowResponse> createShow(@PathVariable Long movieId, @Valid @RequestBody ShowRequest request) {
        ShowResponse response = showService.createShow(movieId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/shows/" + response.getId())
                .body(response);
    }

    /**
     * GET /movies/{movieId}/shows - public. Defaults to future shows only; pass
     * ?includePast=true for the full schedule (plan/crud.md section 11-F).
     */
    @GetMapping("/movies/{movieId}/shows")
    @Operation(summary = "List shows for a movie")
    public ResponseEntity<List<ShowResponse>> listShowsForMovie(
            @PathVariable Long movieId,
            @Parameter(description = "Include shows that already started/ended")
            @RequestParam(defaultValue = "false") boolean includePast) {
        List<ShowResponse> response = showService.listShowsForMovie(movieId, includePast);
        return ResponseEntity.ok(response);
    }
}
