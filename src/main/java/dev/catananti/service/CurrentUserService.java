package dev.catananti.service;

import dev.catananti.entity.User;
import dev.catananti.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * ARCH-3: Centralizes the reactive "current user" resolution chain that was previously
 * copy-pasted across multiple services and controllers. Resolves the authenticated
 * principal's email from the reactive security context and loads the matching
 * {@link User} from the repository.
 *
 * <p>Behavior matches the previous duplicated helpers exactly: the returned Mono
 * completes empty when there is no authentication, the principal is not authenticated,
 * or no user matches the principal's email.</p>
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    /**
     * Resolve the current authenticated user from the reactive security context.
     *
     * @return the authenticated {@link User}, or an empty Mono if unauthenticated/not found.
     */
    public Mono<User> currentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .map(auth -> auth.getName())
                .flatMap(email -> userRepository.findByEmail(email));
    }

    /**
     * Resolve the current authenticated user's id.
     *
     * @return the authenticated user's id, or an empty Mono if unauthenticated/not found.
     */
    public Mono<Long> currentUserId() {
        return currentUser().map(User::getId);
    }
}
