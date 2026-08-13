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

@Repository
public interface SubscriberRepository extends ReactiveCrudRepository<Subscriber, Long> {

    @Query("SELECT * FROM subscribers WHERE email = LOWER(TRIM(:email))")
    Mono<Subscriber> findByEmail(String email);

    Mono<Subscriber> findByConfirmationToken(String token);

    Mono<Subscriber> findByUnsubscribeToken(String token);

    @Query("SELECT * FROM subscribers WHERE status = :status LIMIT :limit")
    Flux<Subscriber> findByStatus(String status, int limit);

    @Query("SELECT * FROM subscribers WHERE status = 'CONFIRMED' ORDER BY created_at DESC LIMIT :limit")
    Flux<Subscriber> findAllConfirmed(int limit);

    @Query("SELECT * FROM subscribers ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Subscriber> findAllPaginated(int limit, int offset);

    @Query("SELECT * FROM subscribers WHERE status = :status ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Subscriber> findByStatusPaginated(String status, int limit, int offset);

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

    @Query("SELECT COUNT(*) > 0 FROM subscribers WHERE email = LOWER(TRIM(:email))")
    Mono<Boolean> existsByEmail(String email);

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

    @Query("SELECT * FROM subscribers WHERE user_id = :userId")
    Mono<Subscriber> findByUserId(Long userId);

    /**
     * Automatic link (email verification, subscription confirmation, backfill).
     *
     * <p>The whole policy lives in this conditional UPDATE — "only link if nobody
     * linked before and the holder did not refuse" — because verification and
     * confirmation can race: whoever gets 1 row back won, whoever gets 0 does
     * nothing. The same rule as a Java {@code if} would pass tests and lose the
     * race in production.
     */
    @Modifying
    @Query("UPDATE subscribers SET user_id = :userId, linked_at = :now, link_origin = :origin, "
         + "unlinked_at = NULL, unlinked_by = NULL "
         + "WHERE id = :id AND user_id IS NULL AND (unlinked_by IS NULL OR unlinked_by <> 'USER')")
    Mono<Long> autoLink(Long id, Long userId, String origin, LocalDateTime now);

    /**
     * Explicit link (MANUAL_USER/MANUAL_ADMIN): when the holder asks for the link
     * back, the earlier refusal is exactly what is being revoked, so the
     * {@code unlinked_by <> 'USER'} guard must not apply. The {@code user_id IS
     * NULL} clause stays — the race protection holds for every path.
     */
    @Modifying
    @Query("UPDATE subscribers SET user_id = :userId, linked_at = :now, link_origin = :origin, "
         + "unlinked_at = NULL, unlinked_by = NULL "
         + "WHERE id = :id AND user_id IS NULL")
    Mono<Long> linkIgnoringRefusal(Long id, Long userId, String origin, LocalDateTime now);

    /**
     * Undoes the link keeping linked_at as history; unlinked_by decides whether
     * the automatic path may ever re-link ('USER' blocks it).
     */
    @Modifying
    @Query("UPDATE subscribers SET user_id = NULL, unlinked_at = :now, unlinked_by = :by "
         + "WHERE user_id = :userId")
    Mono<Long> unlink(Long userId, String by, LocalDateTime now);

    /** Email open/click tracking consent — a different purpose than users.analytics_consent. */
    @Modifying
    @Query("UPDATE subscribers SET analytics_consent = :consent WHERE id = :id")
    Mono<Long> updateAnalyticsConsent(Long id, Boolean consent);

    /**
     * Cancels the emails for the linked subscription without touching the link —
     * used when the holder, while deleting the account, chose to also cancel the
     * newsletter. Unsubscribing and unlinking stay separate operations.
     */
    @Modifying
    @Query("UPDATE subscribers SET status = 'UNSUBSCRIBED', unsubscribed_at = :at "
         + "WHERE user_id = :userId AND status <> 'UNSUBSCRIBED'")
    Mono<Long> unsubscribeByUserId(Long userId, LocalDateTime at);
}
