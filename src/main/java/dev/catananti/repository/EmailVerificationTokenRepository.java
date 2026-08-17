package dev.catananti.repository;

import dev.catananti.entity.EmailVerificationToken;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface EmailVerificationTokenRepository extends ReactiveCrudRepository<EmailVerificationToken, Long> {

    @Query("SELECT * FROM email_verification_tokens WHERE token = :token AND used = false")
    Mono<EmailVerificationToken> findByTokenAndUsedFalse(String token);

    /**
     * Consome o token de forma atômica. Retorna 1 para quem venceu a corrida e 0
     * para quem chegou depois — duas requisições simultâneas com o mesmo link não
     * podem ambas verificar. Um {@code if (token.isUsed())} em Java passaria no
     * teste e falharia sob concorrência.
     */
    @Modifying
    @Query("UPDATE email_verification_tokens SET used = true, used_at = :usedAt "
         + "WHERE id = :id AND used = false")
    Mono<Long> consumeIfUnused(Long id, LocalDateTime usedAt);

    @Query("SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = :userId AND created_at > :since")
    Mono<Long> countRecentByUserId(Long userId, LocalDateTime since);

    @Modifying
    @Query("DELETE FROM email_verification_tokens WHERE expires_at < :cutoff OR (used = true AND used_at < :cutoff)")
    Mono<Long> deleteExpired(LocalDateTime cutoff);

    // Account deletion: the rows carry the address being verified in plain text.
    @Modifying
    @Query("DELETE FROM email_verification_tokens WHERE user_id = :userId")
    Mono<Long> deleteByUserId(Long userId);
}
