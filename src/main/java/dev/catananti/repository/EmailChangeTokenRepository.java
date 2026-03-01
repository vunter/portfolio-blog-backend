package dev.catananti.repository;

import dev.catananti.entity.EmailChangeToken;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface EmailChangeTokenRepository extends ReactiveCrudRepository<EmailChangeToken, Long> {

    @Query("SELECT * FROM email_change_tokens WHERE token = :token AND used = false")
    Mono<EmailChangeToken> findByTokenAndUsedFalse(String token);

    @Modifying
    @Query("UPDATE email_change_tokens SET used = true, used_at = :usedAt WHERE id = :id")
    Mono<Void> markAsUsed(Long id, LocalDateTime usedAt);

    @Modifying
    @Query("DELETE FROM email_change_tokens WHERE expires_at < :cutoff OR (used = true AND used_at < :cutoff)")
    Mono<Void> deleteExpiredTokens(LocalDateTime cutoff);

    @Query("SELECT COUNT(*) FROM email_change_tokens WHERE user_id = :userId AND created_at > :since")
    Mono<Long> countRecentTokensByUserId(Long userId, LocalDateTime since);
}
