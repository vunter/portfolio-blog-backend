package dev.catananti.repository;

import dev.catananti.entity.Subscriber;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

// TODO F-285: Add pg_trgm GIN index for LIKE queries
@Repository
public interface SubscriberRepository extends ReactiveCrudRepository<Subscriber, Long> {

    Mono<Subscriber> findByEmail(String email);

    Mono<Subscriber> findByConfirmationToken(String token);

    Mono<Subscriber> findByUnsubscribeToken(String token);

    Flux<Subscriber> findByStatus(String status);

    @Query("SELECT * FROM subscribers WHERE status = 'CONFIRMED' ORDER BY created_at DESC")
    Flux<Subscriber> findAllConfirmed();

    @Query("SELECT * FROM subscribers ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Subscriber> findAllPaginated(int limit, int offset);

    @Query("SELECT * FROM subscribers WHERE status = :status ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Subscriber> findByStatusPaginated(String status, int limit, int offset);

    // TODO F-291: Sanitize LIKE pattern to escape %, _, \ characters
    @Query("SELECT * FROM subscribers WHERE LOWER(email) LIKE LOWER(CONCAT('%', :email, '%')) ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Subscriber> findByEmailContainingPaginated(String email, int limit, int offset);

    @Query("SELECT COUNT(*) FROM subscribers WHERE LOWER(email) LIKE LOWER(CONCAT('%', :email, '%'))")
    Mono<Long> countByEmailContaining(String email);

    @Query("SELECT * FROM subscribers WHERE status = :status AND LOWER(email) LIKE LOWER(CONCAT('%', :email, '%')) ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Subscriber> findByStatusAndEmailContainingPaginated(String status, String email, int limit, int offset);

    @Query("SELECT COUNT(*) FROM subscribers WHERE status = :status AND LOWER(email) LIKE LOWER(CONCAT('%', :email, '%'))")
    Mono<Long> countByStatusAndEmailContaining(String status, String email);

    @Query("SELECT COUNT(*) FROM subscribers WHERE status = :status")
    Mono<Long> countByStatus(String status);

    @Query("SELECT COUNT(*) FROM subscribers WHERE status = 'CONFIRMED'")
    Mono<Long> countConfirmed();

    @Query("SELECT COUNT(*) FROM subscribers WHERE status = 'PENDING'")
    Mono<Long> countPending();

    Mono<Boolean> existsByEmail(String email);

    @Modifying
    @Query("UPDATE subscribers SET status = 'UNSUBSCRIBED', unsubscribed_at = CURRENT_TIMESTAMP WHERE email = :email")
    Mono<Integer> unsubscribeByEmail(String email);

    /**
     * Find pending subscriptions older than the given date (for cleanup).
     */
    @Query("SELECT * FROM subscribers WHERE status = 'PENDING' AND created_at < :expirationDate")
    Flux<Subscriber> findExpiredPendingSubscriptions(LocalDateTime expirationDate);

    /**
     * Delete expired pending subscriptions.
     */
    @Modifying
    @Query("DELETE FROM subscribers WHERE status = 'PENDING' AND created_at < :expirationDate")
    Mono<Integer> deleteExpiredPendingSubscriptions(LocalDateTime expirationDate);

    /**
     * Count expired pending subscriptions.
     */
    @Query("SELECT COUNT(*) FROM subscribers WHERE status = 'PENDING' AND created_at < :expirationDate")
    Mono<Long> countExpiredPendingSubscriptions(LocalDateTime expirationDate);

    /**
     * Batch delete subscribers by IDs in a single query.
     */
    @Modifying
    @Query("DELETE FROM subscribers WHERE id IN (:ids)")
    Mono<Long> deleteAllByIdIn(List<Long> ids);
}
