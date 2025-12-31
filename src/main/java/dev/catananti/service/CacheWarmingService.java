package dev.catananti.service;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.TagRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache Warming Service for proactive cache population.
 * TODO F-239: Add cache warming health indicator to expose warm/cold status via /actuator/health
 * 
 * Benefits:
 * - First requests are served from warm cache
 * - Reduces latency spikes after deployments
 * - Prefetches related data for common access patterns
 * 
 * Warming strategies:
 * - On startup: Warm critical paths
 * - On schedule: Refresh popular content
 * - On access: Prefetch related content
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheWarmingService {

    private final ArticleService articleService;
    private final TagService tagService;
    private final CacheService cacheService;
    private final ArticleRepository articleRepository;
    private final TagRepository tagRepository;

    private final Set<String> warmingInProgress = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean startupWarmingComplete = new AtomicBoolean(false);
    private final AtomicLong backgroundErrors = new AtomicLong(0);

    /**
     * MIN-05: Centralized background task subscriber.
     * Logs errors with task name and tracks error count for monitoring.
     */
    private void subscribeBackground(String taskName, Mono<?> task) {
        task.subscribe(
                result -> log.debug("{} completed", taskName),
                error -> {
                    backgroundErrors.incrementAndGet();
                    log.error("{} failed: {}", taskName, error.getMessage());
                }
        );
    }

    private void subscribeBackground(String taskName, Flux<?> task) {
        task.then().subscribe(
                v -> log.debug("{} completed", taskName),
                error -> {
                    backgroundErrors.incrementAndGet();
                    log.error("{} failed: {}", taskName, error.getMessage());
                }
        );
    }

    /**
     * PERF-05: Adaptive delay tracking.
     * Adjusts prefetch delay based on observed response latency —
     * uses exponential moving average of recent prefetch durations.
     */
    private final AtomicLong adaptiveDelayMs = new AtomicLong(100);
    private static final long MIN_DELAY_MS = 20;
    private static final long MAX_DELAY_MS = 2000;

    @Value("${cache.warming.enabled:true}")
    private boolean warmingEnabled;

    @Value("${cache.warming.startup-pages:3}")
    private int startupPages;

    @Value("${cache.warming.prefetch-delay-ms:100}")
    private long prefetchDelayMs;

    // ==================== STARTUP WARMING ====================

    /**
     * Warm cache on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        if (!warmingEnabled) {
            log.info("Cache warming disabled");
            return;
        }

        log.info("Starting cache warming...");
        Instant start = Instant.now();

        // Warm in parallel but don't block startup
        Flux.merge(
                warmPublishedArticles(),
                warmAllTags(),
                warmPopularArticles()
        )
        .then()
        .doOnSuccess(v -> {
            long elapsed = Duration.between(start, Instant.now()).toMillis();
            log.info("Cache warming completed in {}ms", elapsed);
            startupWarmingComplete.set(true);
        })
        .doOnError(e -> log.warn("Cache warming failed: {}", e.getMessage()))
        .subscribe(
                null,
                error -> log.error("Cache warming subscription error: {}", error.getMessage())
        );
    }

    /**
     * Warm first N pages of published articles.
     */
    private Mono<Void> warmPublishedArticles() {
        return Flux.range(0, startupPages)
                .flatMap(page -> articleService.getPublishedArticles(page, 10)
                        .doOnSuccess(p -> log.debug("Warmed articles page {}", page)))
                .then()
                .doOnSuccess(v -> log.info("Warmed {} pages of articles", startupPages));
    }

    /**
     * Warm all tags.
     */
    private Mono<Void> warmAllTags() {
        return tagService.getAllTags("en")
                .collectList()
                .doOnSuccess(tags -> log.info("Warmed {} tags", tags.size()))
                .then();
    }

    /**
     * Warm most viewed articles.
     */
    private Mono<Void> warmPopularArticles() {
        return articleRepository.findTopByViewsCount(10)
                .flatMap(article -> articleService.getPublishedArticleBySlug(article.getSlug())
                        .doOnSuccess(a -> log.debug("Warmed popular article: {}", a.getSlug()))
                        .onErrorResume(e -> Mono.empty()))
                .then()
                .doOnSuccess(v -> log.info("Warmed popular articles"));
    }

    // ==================== SCHEDULED WARMING ====================

    /**
     * Periodically refresh cache for popular content.
     */
    @Scheduled(fixedRateString = "${cache.warming.refresh-rate-ms:300000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void refreshPopularContent() {
        if (!warmingEnabled || !startupWarmingComplete.get()) {
            return;
        }

        log.debug("Refreshing popular content cache");
        
        // F-164: Use .block() instead of fire-and-forget .subscribe() —
        // @Scheduled runs on its own thread pool, so blocking is safe and
        // ensures completion before next scheduled run
        try {
            articleRepository.findTopByViewsCount(20)
                    .flatMap(article ->
                        cacheService.invalidateArticle(article.getSlug())
                                .then(articleService.getPublishedArticleBySlug(article.getSlug()))
                                .onErrorResume(e -> {
                                    log.warn("Failed to refresh cache for article: {}", article.getSlug(), e);
                                    return Mono.empty();
                                })
                    )
                    .then()
                    .block();
        } catch (Exception e) {
            backgroundErrors.incrementAndGet();
            log.error("refreshPopularContent failed: {}", e.getMessage());
        }
    }

    // ==================== PREFETCH ON ACCESS ====================

    /**
     * Prefetch related content when an article is accessed.
     * Call this after serving an article response.
     * 
     * @param articleSlug The accessed article's slug
     * @param articleTags Tags of the accessed article
     */
    public void prefetchRelatedContent(String articleSlug, Set<String> articleTags) {
        if (!warmingEnabled) {
            return;
        }

        // Prevent duplicate prefetch operations
        if (!warmingInProgress.add(articleSlug)) {
            return;
        }

        // PERF-05: Adaptive delay — use current adaptive value instead of fixed delay
        long currentDelay = adaptiveDelayMs.get();

        // Prefetch with adaptive delay and measure execution time for feedback
        long startTime = System.nanoTime();
        subscribeBackground("prefetchRelatedContent:" + articleSlug,
                Mono.delay(Duration.ofMillis(currentDelay))
                        .then(prefetchRelatedArticles(articleSlug, articleTags))
                        .doOnSuccess(v -> updateAdaptiveDelay(System.nanoTime() - startTime))
                        .doFinally(s -> warmingInProgress.remove(articleSlug))
        );
    }

    /**
     * PERF-05: Update adaptive delay based on prefetch duration.
     * Uses exponential moving average (EMA) with alpha=0.3 to smooth out spikes.
     * If prefetch takes longer, delay increases; if fast, delay decreases.
     */
    private void updateAdaptiveDelay(long durationNanos) {
        long durationMs = durationNanos / 1_000_000;
        // Target delay = ~10% of prefetch duration, clamped to bounds
        long targetDelay = Math.clamp(durationMs / 10, MIN_DELAY_MS, MAX_DELAY_MS);
        // EMA: new = 0.3 * target + 0.7 * current
        long current = adaptiveDelayMs.get();
        long updated = (long) (0.3 * targetDelay + 0.7 * current);
        adaptiveDelayMs.set(Math.clamp(updated, MIN_DELAY_MS, MAX_DELAY_MS));
        log.trace("Adaptive prefetch delay: {}ms (measured {}ms)", updated, durationMs);
    }

    private Mono<Void> prefetchRelatedArticles(String currentSlug, Set<String> tags) {
        if (tags.isEmpty()) {
            return Mono.empty();
        }

        // Prefetch articles with the same primary tag
        String primaryTag = tags.iterator().next();
        
        return articleRepository.findByTagSlugAndStatus(primaryTag, ArticleStatus.PUBLISHED.name(), 5, 0)
                .filter(article -> !article.getSlug().equals(currentSlug))
                .take(3)
                .flatMap(article -> articleService.getPublishedArticleBySlug(article.getSlug())
                        .doOnSuccess(a -> log.trace("Prefetched related article: {}", a.getSlug()))
                        .onErrorResume(e -> Mono.empty()))
                .then();
    }

    // ==================== MANUAL WARMING ====================

    /**
     * Manually warm specific content.
     */
    public Mono<Integer> warmArticlesByTag(String tagSlug) {
        return articleRepository.findByTagSlugAndStatus(tagSlug, ArticleStatus.PUBLISHED.name(), 20, 0)
                .flatMap(article -> articleService.getPublishedArticleBySlug(article.getSlug())
                        .onErrorResume(e -> Mono.empty()))
                .count()
                .map(Long::intValue)
                .doOnSuccess(count -> log.info("Warmed {} articles for tag: {}", count, tagSlug));
    }

    /**
     * Clear all caches and re-warm.
     */
    public Mono<Void> clearAndRewarm() {
        log.info("Clearing and re-warming caches...");
        
        return cacheService.invalidateAllCaches()
                .then(Mono.fromRunnable(this::warmOnStartup))
                .then();
    }

    // ==================== STATUS ====================

    /**
     * Check if startup warming is complete.
     */
    public boolean isStartupWarmingComplete() {
        return startupWarmingComplete.get();
    }

    /**
     * Get current warming status.
     */
    public WarmingStatus getStatus() {
        return new WarmingStatus(
                warmingEnabled,
                startupWarmingComplete.get(),
                warmingInProgress.size(),
                backgroundErrors.get()
        );
    }

    public record WarmingStatus(
            boolean enabled,
            boolean startupComplete,
            int prefetchesInProgress,
            long backgroundErrorCount
    ) {}
}
