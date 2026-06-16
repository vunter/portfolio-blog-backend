package dev.catananti.repository;

import dev.catananti.entity.ReadingHistory;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ReadingHistoryRepository extends ReactiveCrudRepository<ReadingHistory, Long> {

    @Query("SELECT * FROM reading_history WHERE user_id = :userId ORDER BY last_read_at DESC LIMIT :limit OFFSET :offset")
    Flux<ReadingHistory> findByUserIdOrderByLastReadAtDesc(Long userId, int limit, int offset);

    @Query("SELECT COUNT(*) FROM reading_history WHERE user_id = :userId")
    Mono<Long> countByUserId(Long userId);

    Mono<ReadingHistory> findByUserIdAndArticleId(Long userId, Long articleId);

    /**
     * BUG-2: Atomic upsert against the UNIQUE(user_id, article_id) constraint.
     * Inserts a new row (read_count = 1) or, on conflict, increments read_count and
     * refreshes last_read_at in a single statement — avoiding the find-then-modify race
     * that lost increments and surfaced DuplicateKeyException under concurrent reads.
     */
    @Query("""
            INSERT INTO reading_history (id, user_id, article_id, last_read_at, read_count)
            VALUES (:id, :userId, :articleId, :lastReadAt, 1)
            ON CONFLICT (user_id, article_id)
            DO UPDATE SET read_count = reading_history.read_count + 1,
                          last_read_at = EXCLUDED.last_read_at
            RETURNING *
            """)
    Mono<ReadingHistory> upsertReading(Long id, Long userId, Long articleId, java.time.LocalDateTime lastReadAt);

    Mono<Void> deleteByUserId(Long userId);
}
