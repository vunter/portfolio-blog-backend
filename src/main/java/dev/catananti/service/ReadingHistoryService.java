package dev.catananti.service;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.ReadingHistoryRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Service for tracking and retrieving user reading history.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReadingHistoryService {

    private final ReadingHistoryRepository readingHistoryRepository;
    private final ArticleRepository articleRepository;
    private final ArticleService articleService;
    private final UserRepository userRepository;
    private final IdService idService;

    /**
     * Record that a user read an article. Upserts atomically: increments read_count if a
     * row already exists, creates a new record otherwise.
     *
     * <p>BUG-2: This delegates to an atomic INSERT ... ON CONFLICT upsert against the
     * UNIQUE(user_id, article_id) constraint instead of a find-then-increment-or-insert
     * sequence, which lost increments and threw an unhandled DuplicateKeyException when two
     * reads for the same (user, article) raced.</p>
     */
    @Transactional
    public Mono<Void> recordReading(Long userId, Long articleId) {
        return readingHistoryRepository
                .upsertReading(idService.nextId(), userId, articleId, LocalDateTime.now())
                .doOnNext(rh -> log.debug("Reading history recorded: user={}, article={}, readCount={}",
                        userId, articleId, rh.getReadCount()))
                .then();
    }

    /**
     * Record reading for authenticated user by email and article slug.
     */
    @Transactional
    public Mono<Void> recordReadingByEmailAndSlug(String email, String slug) {
        return userRepository.findByEmail(email)
                .flatMap(user -> articleRepository.findBySlug(slug)
                        .flatMap(article -> recordReading(user.getId(), article.getId())))
                .then();
    }

    /**
     * Get paginated reading history for a user, enriched with article data.
     */
    public Mono<PageResponse<ReadingHistoryResponse>> getReadingHistory(String email, int page, int size) {
        return userRepository.findByEmail(email)
                .flatMap(user -> {
                    int offset = page * size;
                    return readingHistoryRepository.findByUserIdOrderByLastReadAtDesc(user.getId(), size, offset)
                            .flatMap(rh -> articleRepository.findById(rh.getArticleId())
                                    .flatMap(articleService::enrichArticleWithMetadata)
                                    .map(article -> new ReadingHistoryResponse(
                                            articleService.mapToResponse(article),
                                            rh.getLastReadAt(),
                                            rh.getReadCount()
                                    )), 4)
                            .collectList()
                            .zipWith(readingHistoryRepository.countByUserId(user.getId()))
                            .map(tuple -> PageResponse.of(tuple.getT1(), page, size, tuple.getT2()));
                })
                .defaultIfEmpty(PageResponse.of(java.util.List.of(), page, size, 0));
    }

    /**
     * Clear all reading history for a user.
     */
    @Transactional
    public Mono<Void> clearHistory(String email) {
        return userRepository.findByEmail(email)
                .flatMap(user -> readingHistoryRepository.deleteByUserId(user.getId()))
                .doOnSuccess(_ -> log.info("Reading history cleared for user: {}", PiiMasker.maskEmail(email)));
    }

    /**
     * Response DTO wrapping an article with reading metadata.
     */
    public record ReadingHistoryResponse(
            ArticleResponse article,
            LocalDateTime lastReadAt,
            int readCount
    ) {}
}
