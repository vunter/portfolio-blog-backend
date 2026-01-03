package dev.catananti.scheduler;

import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.service.CacheService;
import dev.catananti.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Scheduler for publishing scheduled articles.
 * Runs every minute to check for articles that should be published.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArticlePublishScheduler {

    private final ArticleRepository articleRepository;
    private final CacheService cacheService;
    private final SubscriberRepository subscriberRepository;
    private final EmailService emailService;

    /**
     * Check for scheduled articles and publish them.
     * Runs every minute (configurable).
     */
    // TODO F-315: Add distributed lock (ShedLock/Redis SETNX) to prevent duplicate execution
    @Scheduled(fixedRateString = "${app.scheduler.article-publish-rate:60000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void publishScheduledArticles() {
        log.debug("Checking for scheduled articles to publish...");
        
        LocalDateTime now = LocalDateTime.now();
        
        articleRepository.findScheduledArticlesToPublish(now)
                .flatMap(this::publishArticle)
                .flatMap(article -> notifySubscribers(article).thenReturn(article))
                .doOnNext(article -> log.info("Auto-published scheduled article: {} (scheduled for: {})", 
                        article.getSlug(), article.getScheduledAt()))
                .then(cacheService.invalidateAllArticles())
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
        // TODO F-316: Reset isNew flag after successful publish
        return articleRepository.save(article);
    }

    private reactor.core.publisher.Mono<Void> notifySubscribers(Article article) {
        // TODO F-317: Use pagination for subscriber list instead of loading all at once
        return subscriberRepository.findAllConfirmed()
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
                }), 5) // Limit concurrency to 5 parallel email sends
                .then()
                .doOnSuccess(v -> log.info("Notified subscribers about scheduled article: {}", article.getSlug()));
    }
}
