package dev.catananti.scheduler;

import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.service.CacheService;
import dev.catananti.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Scheduler for publishing scheduled articles.
 * Runs every minute to check for articles that should be published.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArticlePublishScheduler {

    private static final String LOCK_KEY = "scheduler:article-publish:lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(55);

    private final ArticleRepository articleRepository;
    private final CacheService cacheService;
    private final SubscriberRepository subscriberRepository;
    private final EmailService emailService;
    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * Check for scheduled articles and publish them.
     * Runs every minute (configurable).
     * Uses Redis SETNX lock to prevent duplicate execution in multi-instance deployments.
     */
    @Scheduled(fixedRateString = "${app.scheduler.article-publish-rate:60000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void publishScheduledArticles() {
        redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, "locked", LOCK_TTL)
                .onErrorResume(e -> {
                    log.debug("Redis unavailable for scheduler lock, proceeding without lock: {}", e.getMessage());
                    return reactor.core.publisher.Mono.just(true);
                })
                .flatMap(acquired -> {
                    if (!Boolean.TRUE.equals(acquired)) {
                        log.debug("Skipping scheduled article publish — another instance holds the lock");
                        return reactor.core.publisher.Mono.empty();
                    }
                    log.debug("Checking for scheduled articles to publish...");
                    LocalDateTime now = LocalDateTime.now();
                    return articleRepository.findScheduledArticlesToPublish(now)
                            .flatMap(this::publishArticle)
                            .flatMap(article -> notifySubscribers(article).thenReturn(article))
                            .doOnNext(article -> log.info("Auto-published scheduled article: {} (scheduled for: {})",
                                    article.getSlug(), article.getScheduledAt()))
                            .then(cacheService.invalidateAllArticles())
                            .doFinally(signal -> redisTemplate.delete(LOCK_KEY)
                                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                                    .subscribe());
                })
                .subscribe(
                        null,
                        error -> log.error("Error publishing scheduled articles: {}", error.getMessage()),
                        () -> log.debug("Scheduled articles check completed")
                );
    }

    private reactor.core.publisher.Mono<Article> publishArticle(Article article) {
        article.setStatus(ArticleStatus.PUBLISHED.name());
        article.setPublishedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        article.setNewRecord(false);
        return articleRepository.save(article);
    }

    private reactor.core.publisher.Mono<Void> notifySubscribers(Article article) {
        return subscriberRepository.findAllConfirmed()
                .buffer(50)
                .concatMap(batch -> reactor.core.publisher.Flux.fromIterable(batch)
                        .flatMap(subscriber -> emailService.sendNewArticleNotification(
                                subscriber.getEmail(),
                                subscriber.getName(),
                                article.getTitle(),
                                article.getSlug(),
                                article.getExcerpt(),
                                subscriber.getUnsubscribeToken()
                        ).onErrorResume(e -> {
                            log.warn("Failed to send article notification to {}: {}", subscriber.getEmail(), e.getMessage());
                            return reactor.core.publisher.Mono.empty();
                        }), 5))
                .then()
                .doOnSuccess(v -> log.info("Notified subscribers about scheduled article: {}", article.getSlug()));
    }
}
