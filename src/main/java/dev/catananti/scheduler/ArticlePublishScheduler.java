package dev.catananti.scheduler;

import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.config.PaginationConfig;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.service.CacheService;
import dev.catananti.service.EmailService;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class ArticlePublishScheduler {

    // The distributed lock is a best-effort optimisation to avoid redundant work
    // across replicas; correctness (no double-publish, no duplicate subscriber e-mails)
    // is guaranteed by the atomic compare-and-swap in claimForPublication, so a lock
    // that expires mid-run while notifying subscribers is harmless.
    private static final Duration LOCK_TTL = Duration.ofSeconds(55);

    private final ArticleRepository articleRepository;
    private final CacheService cacheService;
    private final SubscriberRepository subscriberRepository;
    private final EmailService emailService;
    private final SchedulerLock schedulerLock;
    private final PaginationConfig paginationConfig;

    public Mono<Void> publishScheduledArticles() {
        return schedulerLock.executeWithLock("article-publish", LOCK_TTL,
                doPublishScheduledArticles());
    }

    /**
     * Publishes articles whose {@code scheduledAt} has elapsed.
     *
     * <p>Distributed locking is delegated to {@link SchedulerLock}; the previous
     * hand-rolled Redis SETNX implementation duplicated that abstraction.</p>
     */
    @Scheduled(fixedRateString = "${app.scheduler.article-publish-rate:60000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void publishScheduledArticlesScheduled() {
        publishScheduledArticles().subscribe();
    }

    private Mono<Void> doPublishScheduledArticles() {
        log.debug("Checking for scheduled articles to publish...");
        LocalDateTime now = LocalDateTime.now();
        return articleRepository.findScheduledArticlesToPublish(now)
                // concatMap (not flatMap) so the atomic claim of each candidate is
                // observed sequentially; only articles this instance actually won the
                // race for are emitted downstream and notified.
                .concatMap(article -> claimForPublication(article, now))
                .flatMap(article -> notifySubscribers(article).thenReturn(article))
                .doOnNext(article -> log.info("Auto-published scheduled article: {} (scheduled for: {})",
                        article.getSlug(), article.getScheduledAt()))
                .collectList()
                .flatMap(published -> {
                    if (published.isEmpty()) {
                        return Mono.empty();
                    }
                    log.info("Published {} scheduled article(s), invalidating cache", published.size());
                    // Invalidate both the article read-through caches AND the RSS/sitemap
                    // feed caches, otherwise a scheduler-published article is missing from
                    // the feeds for up to their TTL (15m/30m).
                    return Mono.when(
                            cacheService.invalidateAllArticles(),
                            cacheService.invalidateFeedCache()
                    );
                })
                .doOnError(e -> log.error("Error publishing scheduled articles: {}", e.getMessage(), e))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Atomically transitions a single article from SCHEDULED to PUBLISHED.
     *
     * <p>Returns the article only when this instance won the compare-and-swap
     * ({@code rows == 1}); a 0-row result means another replica already published it,
     * so we emit nothing and never notify subscribers twice.</p>
     */
    private Mono<Article> claimForPublication(Article article, LocalDateTime now) {
        return articleRepository.markPublishedIfScheduled(article.getId(), now)
                .flatMap(rows -> {
                    if (rows == null || rows < 1) {
                        log.debug("Article '{}' already published by another instance — skipping notification",
                                article.getSlug());
                        return Mono.empty();
                    }
                    // Reflect the persisted state on the in-memory copy used downstream.
                    article.setStatus(ArticleStatus.PUBLISHED);
                    article.setPublishedAt(now);
                    article.setUpdatedAt(now);
                    article.setNewRecord(false);
                    return Mono.just(article);
                });
    }

    private Mono<Void> notifySubscribers(Article article) {
        return subscriberRepository.findAllConfirmed(paginationConfig.getBulkQueryMax())
                .buffer(50)
                .concatMap(batch -> Flux.fromIterable(batch)
                        .flatMap(subscriber -> emailService.sendNewArticleNotification(
                                subscriber.getEmail(),
                                subscriber.getName(),
                                article.getTitle(),
                                article.getSlug(),
                                article.getExcerpt(),
                                subscriber.getUnsubscribeToken()
                        ).onErrorResume(e -> {
                            log.warn("Failed to send article notification to {}: {}",
                                    PiiMasker.maskEmail(subscriber.getEmail()), e.getMessage());
                            return Mono.empty();
                        }), 5))
                .then()
                .doOnSuccess(v -> log.info("Notified subscribers about scheduled article: {}", article.getSlug()));
    }
}
