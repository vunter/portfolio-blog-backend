package dev.catananti.repository;

import dev.catananti.entity.RefreshToken;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface RefreshTokenRepository extends ReactiveCrudRepository<RefreshToken, Long> {

    Mono<RefreshToken> findByToken(String token);

    Mono<RefreshToken> findByTokenAndRevokedFalse(String token);

    @Modifying
    @Query("UPDATE refresh_tokens SET revoked = true WHERE user_id = :userId")
    Mono<Void> revokeAllByUserId(Long userId);

    @Modifying
    @Query("UPDATE refresh_tokens SET revoked = true WHERE user_id = :userId AND token != :currentToken")
    Mono<Void> revokeAllByUserIdExcept(Long userId, String currentToken);

    // SI-6: expiry rows are WRITTEN with the JVM clock (LocalDateTime.now()), so the
    // validity comparison must use the same clock — NOW() compared against a column
    // in the Postgres server's timezone, which silently shifts token lifetimes when
    // app and DB clocks/timezones differ.
    @Query("SELECT * FROM refresh_tokens WHERE user_id = :userId AND revoked = false AND expires_at > :now ORDER BY created_at DESC")
    Flux<RefreshToken> findActiveByUserId(Long userId, java.time.LocalDateTime now);

    /**
     * Revokes a token by setting revoked=true. Returns the number of updated rows.
     * In combination with findByToken, this replaces the PostgreSQL-specific RETURNING approach.
     */
    @Modifying
    @Query("UPDATE refresh_tokens SET revoked = true WHERE token = :token AND revoked = false")
    Mono<Long> revokeByTokenIfActive(String token);

    @Modifying
    @Query("DELETE FROM refresh_tokens WHERE expires_at < :now")
    Mono<Void> deleteExpired(LocalDateTime now);

    Mono<Void> deleteByUserId(Long userId);
}
