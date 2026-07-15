package com.example.movieticket.dto;

// jakarta.validation.constraints.* are the Bean Validation annotations activated by
// the spring-boot-starter-validation dependency + @Valid on the controller method.
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for POST /auth/register.
 *
 * Deliberately has NO `role` field. Self-service registration always produces a
 * ROLE_USER account (see AuthService.register()) - there is nothing for a client
 * to bind a "role": "ROLE_ADMIN" value to, even if they include it in the JSON body,
 * because this class simply doesn't declare that property. Jackson will silently
 * ignore unknown JSON fields by default, so this is a real security boundary, not
 * just documentation.
 */
@Data // Lombok: getters/setters/toString/equals/hashCode.
@NoArgsConstructor // Required so Jackson can deserialize the incoming JSON into this class.
@AllArgsConstructor // Convenience constructor for building instances directly in tests.
public class RegisterRequest {

    @NotBlank(message = "Username is required") // Rejects null AND empty/whitespace-only strings.
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address") // Basic format check (not a mailbox-existence check).
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters") // Matches claude.md/plan's min length requirement.
    private String password;
}
