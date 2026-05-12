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

/**
 * Publishes articles whose {@code scheduledAt} has elapsed. Returns a
 * {@code Mono<Void>} so Spring's reactive scheduler defers the next run
 * until the current one terminates, eliminating the overlap risk of the
 * previous fire-and-forget {@code .subscribe()} pattern.
 *
 * <p>Distributed locking is delegated to {@link SchedulerLock}; the previous
 * hand-rolled Redis SETNX implementation duplicated that abstraction.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArticlePublishScheduler {

    private static final Duration LOCK_TTL = Duration.ofSeconds(55);

    private final ArticleRepository articleRepository;
    private final CacheService cacheService;
    private final SubscriberRepository subscriberRepository;
    private final EmailService emailService;
    private final SchedulerLock schedulerLock;
    private final PaginationConfig paginationConfig;

    @Scheduled(fixedRateString = "${app.scheduler.article-publish-rate:60000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public Mono<Void> publishScheduledArticles() {
        return schedulerLock.executeWithLock("article-publish", LOCK_TTL,
                doPublishScheduledArticles());
    }

    private Mono<Void> doPublishScheduledArticles() {
        log.debug("Checking for scheduled articles to publish...");
        LocalDateTime now = LocalDateTime.now();
        return articleRepository.findScheduledArticlesToPublish(now)
                .flatMap(this::publishArticle)
                .flatMap(article -> notifySubscribers(article).thenReturn(article))
                .doOnNext(article -> log.info("Auto-published scheduled article: {} (scheduled for: {})",
                        article.getSlug(), article.getScheduledAt()))
                .collectList()
                .flatMap(published -> {
                    if (published.isEmpty()) {
                        return Mono.empty();
                    }
                    log.info("Published {} scheduled article(s), invalidating cache", published.size());
                    return cacheService.invalidateAllArticles().then();
                })
                .doOnError(e -> log.error("Error publishing scheduled articles: {}", e.getMessage(), e))
                .onErrorResume(e -> Mono.empty());
    }

    private Mono<Article> publishArticle(Article article) {
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setPublishedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        article.setNewRecord(false);
        return articleRepository.save(article);
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
