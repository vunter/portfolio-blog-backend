package dev.catananti.repository;

import dev.catananti.entity.User;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface UserRepository extends ReactiveCrudRepository<User, Long> {

    @Query("SELECT * FROM users WHERE email = LOWER(TRIM(:email))")
    Mono<User> findByEmail(String email);

    @Query("SELECT COUNT(*) > 0 FROM users WHERE email = LOWER(TRIM(:email))")
    Mono<Boolean> existsByEmail(String email);

    @Query("SELECT * FROM users WHERE LOWER(username) = LOWER(:username)")
    Mono<User> findByUsername(String username);

    @Query("SELECT COUNT(*) > 0 FROM users WHERE LOWER(username) = LOWER(:username)")
    Mono<Boolean> existsByUsername(String username);

    @Query("SELECT * FROM users WHERE role = :role LIMIT :limit")
    Flux<User> findByRole(String role, int limit);

    @Query("SELECT * FROM users ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<User> findAllPaged(int limit, int offset);

    @Query("SELECT COUNT(*) FROM users")
    Mono<Long> countAll();

    @Query("SELECT * FROM users WHERE LOWER(name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(email) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(role) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<User> searchUsers(String search, int limit, int offset);

    @Query("SELECT COUNT(*) FROM users WHERE LOWER(name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(email) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(role) LIKE LOWER(CONCAT('%', :search, '%'))")
    Mono<Long> countSearch(String search);

    @Query("SELECT COUNT(*) FROM users WHERE role = :role")
    Mono<Long> countByRole(String role);

    // CC-07: security-critical flags are written via partial UPDATEs instead of
    // full-row save() so an overlapping save() from an unrelated flow (e.g. a
    // profile edit holding a stale entity) cannot silently revert them.

    @Modifying
    @Query("UPDATE users SET mfa_enabled = true, mfa_preferred_method = :method, updated_at = :updatedAt WHERE id = :id")
    Mono<Long> enableMfa(Long id, String method, LocalDateTime updatedAt);

    @Modifying
    @Query("UPDATE users SET mfa_enabled = false, mfa_preferred_method = NULL, updated_at = :updatedAt WHERE id = :id")
    Mono<Long> disableMfa(Long id, LocalDateTime updatedAt);

    @Modifying
    @Query("UPDATE users SET mfa_preferred_method = :method, updated_at = :updatedAt WHERE id = :id")
    Mono<Long> updateMfaPreferredMethod(Long id, String method, LocalDateTime updatedAt);

    /** Enables MFA keeping an already-chosen preferred method; :fallbackMethod applies only when none is set. */
    @Modifying
    @Query("UPDATE users SET mfa_enabled = true, mfa_preferred_method = COALESCE(mfa_preferred_method, :fallbackMethod), updated_at = :updatedAt WHERE id = :id")
    Mono<Long> enableMfaWithFallbackPreferred(Long id, String fallbackMethod, LocalDateTime updatedAt);
}
