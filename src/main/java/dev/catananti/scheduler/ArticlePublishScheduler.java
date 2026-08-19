package dev.catananti.scheduler;

import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.service.ArticleAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
    private final ArticleAdminService articleAdminService;
    private final SchedulerLock schedulerLock;

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
                // race for are emitted downstream.
                .concatMap(article -> claimForPublication(article, now))
                // AUD19C-2: the scheduler's private notifySubscribers duplicate is gone —
                // post-publish side effects (SSE broadcast, article/feed cache
                // invalidation, notified_at-gated subscriber e-mail fan-out) are shared
                // with every other publish path via ArticleAdminService. The claim above
                // (status CAS) decides WHO publishes; the notified_at CAS inside the
                // shared method decides WHO e-mails — both are once-only.
                .concatMap(article -> articleAdminService.applyPublishSideEffects(article))
                .doOnNext(article -> log.info("Auto-published scheduled article: {} (scheduled for: {})",
                        article.getSlug(), article.getScheduledAt()))
                .count()
                .doOnNext(count -> {
                    if (count > 0) {
                        log.info("Published {} scheduled article(s)", count);
                    }
                })
                .then()
                .doOnError(e -> log.error("Error publishing scheduled articles: {}", e.getMessage(), e))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Atomically transitions a single article from SCHEDULED to PUBLISHED.
     *
     * <p>Returns the article only when this instance won the compare-and-swap
     * ({@code rows == 1}); a 0-row result means another replica (or a manual publish)
     * already published it, so we emit nothing and apply no side effects.</p>
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
}
