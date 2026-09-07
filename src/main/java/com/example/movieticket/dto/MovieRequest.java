package com.example.movieticket.dto;

// Bean Validation annotations (jakarta.validation.constraints.*) - activated by the
// spring-boot-starter-validation dependency + @Valid on the controller method, same
// pattern as Module 2's RegisterRequest.
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for POST /admin/movies and PUT /admin/movies/{movieId} (plan/crud.md
 * endpoints #1 and #2). Same shape for create and update - PUT is a full replace
 * of these three fields, not a partial patch (see plan/crud.md section 4.1's note
 * on why PATCH isn't offered here).
 *
 * Deliberately has no `id` field (comes from the path on update, is
 * server-generated on create) and no `shows` field (a movie's shows are managed
 * through their own endpoints, never nested inside a movie write).
 */
@Data // Lombok: generates getters/setters/toString/equals/hashCode at compile time.
@NoArgsConstructor // Required so Jackson can construct this via reflection then set fields from JSON.
@AllArgsConstructor // Convenience constructor for building instances directly in tests.
public class MovieRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must be at most 255 characters")
    private String title;

    // No @NotBlank: a movie's synopsis is optional information, unlike its title.
    private String description;

    @Positive(message = "Duration must be a positive number of minutes")
    private int durationMinutes;
}
