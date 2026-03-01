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

    Mono<Void> deleteByUserId(Long userId);
}
