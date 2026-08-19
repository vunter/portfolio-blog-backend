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

    @Modifying
    @Query("UPDATE users SET password_hash = :passwordHash, updated_at = :updatedAt WHERE id = :id")
    Mono<Long> updatePasswordHash(Long id, String passwordHash, LocalDateTime updatedAt);

    /** AUD18-M8: persists the Cloudflare rule id created AFTER the role-change commit (CC-07 partial UPDATE). */
    @Modifying
    @Query("UPDATE users SET cf_email_rule_id = :ruleId, updated_at = :updatedAt WHERE id = :id")
    Mono<Long> updateCfEmailRuleId(Long id, String ruleId, LocalDateTime updatedAt);

    /**
     * Marca o e-mail como verificado. A cláusula {@code email_verified = false}
     * torna a operação idempotente e devolve 0 quando já estava verificado, o que
     * o chamador usa para distinguir "acabei de verificar" de "já estava".
     */
    @Modifying
    @Query("UPDATE users SET email_verified = true, updated_at = :updatedAt "
         + "WHERE id = :id AND email_verified = false")
    Mono<Long> markEmailVerified(Long id, LocalDateTime updatedAt);

    /** Site-navigation analytics consent; analytics_consent_at is the proof of when. */
    @Modifying
    @Query("UPDATE users SET analytics_consent = :consent, analytics_consent_at = :at, updated_at = :at "
         + "WHERE id = :id")
    Mono<Long> updateAnalyticsConsent(Long id, Boolean consent, LocalDateTime at);

    /**
     * Level 1 deletion (deactivation): the account goes off the air, PII stays.
     * Conditional on status = 'ACTIVE' so a repeated request returns 0 rows —
     * the caller turns that into a 409 instead of silently rewriting deleted_at.
     */
    @Modifying
    @Query("UPDATE users SET active = false, status = 'DEACTIVATED', deleted_at = :at, updated_at = :at "
         + "WHERE id = :id AND status = 'ACTIVE'")
    Mono<Long> deactivateAccount(Long id, LocalDateTime at);

    /**
     * Level 2 deletion (erasure, LGPD art. 18, VI): every PII column of the row
     * is anonymized in one statement. The row itself stays so user_id references
     * in content tables keep pointing at something that re-identifies no one.
     * A DEACTIVATED account can still be erased; only ERASED is terminal.
     */
    @Modifying
    @Query("UPDATE users SET active = false, status = 'ERASED', deleted_at = :at, "
         + "email = :anonEmail, name = :anonName, username = NULL, avatar_url = NULL, bio = NULL, "
         + "password_hash = :scrambledHash, mfa_enabled = false, mfa_preferred_method = NULL, "
         + "email_verified = false, updated_at = :at "
         + "WHERE id = :id AND status <> 'ERASED'")
    Mono<Long> eraseAccount(Long id, String anonEmail, String anonName, String scrambledHash, LocalDateTime at);
}
