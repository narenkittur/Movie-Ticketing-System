package com.example.movieticket.security;

import com.example.movieticket.model.User;
import com.example.movieticket.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Bridges our own User entity to Spring Security's UserDetails contract.
 *
 * IMPORTANT: this is only ever consulted by AuthenticationManager during the
 * actual login call (AuthService.login()), where Spring Security needs to load
 * the stored password hash to compare it against the submitted password. It is
 * NOT consulted on every subsequent request - JwtAuthenticationFilter builds the
 * Authentication object straight from the JWT's claims instead, precisely to
 * avoid a DB round-trip on every single API call (see Issue #3 in the plan).
 */
@Service // Registered as a bean; Spring Boot's security auto-configuration wires it into
         // a DaoAuthenticationProvider automatically because it detects a single
         // UserDetailsService bean + a single PasswordEncoder bean on the context.
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    // Constructor injection (no @Autowired needed - Spring auto-detects a single
    // constructor as the injection point since Spring 4.3).
    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No user found with username: " + username));

        // Spring Security's built-in User implementation of UserDetails. We hand it
        // the already-hashed password (never the plaintext) - PasswordEncoder.matches()
        // is what actually compares the submitted password against this hash.
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                // The role string in our DB is already prefixed, e.g. "ROLE_ADMIN".
                // SimpleGrantedAuthority expects exactly that form - Spring Security's
                // hasRole("ADMIN") checks strip the "ROLE_" prefix internally before
                // comparing, so storing the prefixed form here is what makes
                // @PreAuthorize("hasRole('ADMIN')") work correctly downstream.
                List.of(new SimpleGrantedAuthority(user.getRole()))
        );
    }
}
