package dev.catananti.repository;

import dev.catananti.entity.PasswordResetToken;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Repository for password reset tokens.
 * TODO F-254: Verify DB has index on 'token' column for O(1) lookup in findByToken queries
 */
@Repository
public interface PasswordResetTokenRepository extends ReactiveCrudRepository<PasswordResetToken, Long> {

    Mono<PasswordResetToken> findByToken(String token);

    Mono<PasswordResetToken> findByTokenAndUsedFalse(String token);

    @Query("SELECT * FROM password_reset_tokens WHERE user_id = :userId AND used = false AND expires_at > NOW() ORDER BY created_at DESC LIMIT 1")
    Mono<PasswordResetToken> findValidTokenByUserId(Long userId);

    @Modifying
    @Query("UPDATE password_reset_tokens SET used = true, used_at = :usedAt WHERE id = :id")
    Mono<Void> markAsUsed(Long id, LocalDateTime usedAt);

    @Modifying
    @Query("DELETE FROM password_reset_tokens WHERE expires_at < :cutoff OR (used = true AND used_at < :cutoff)")
    Mono<Void> deleteExpiredTokens(LocalDateTime cutoff);

    @Query("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = :userId AND created_at > :since")
    Mono<Long> countRecentTokensByUserId(Long userId, LocalDateTime since);
}
