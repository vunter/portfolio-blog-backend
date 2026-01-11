package dev.catananti.metrics;

import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.SubscriberRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class BlogMetrics {

    private final MeterRegistry meterRegistry;
    private final ArticleRepository articleRepository;
    private final CommentRepository commentRepository;
    private final SubscriberRepository subscriberRepository;

    private final AtomicLong totalArticles = new AtomicLong(0);
    private final AtomicLong publishedArticles = new AtomicLong(0);
    private final AtomicLong draftArticles = new AtomicLong(0);
    private final AtomicLong totalComments = new AtomicLong(0);
    private final AtomicLong pendingComments = new AtomicLong(0);
    private final AtomicLong activeSubscribers = new AtomicLong(0);

    // F-055: Cached counter references to avoid registry lookup on every call
    private Counter commentCreatedCounter;
    private Counter subscriptionNewCounter;
    private Counter subscriptionCancelledCounter;

    @PostConstruct
    public void init() {
        // Article metrics
        Gauge.builder("blog.articles.total", totalArticles, AtomicLong::get)
                .description("Total number of articles")
                .tag("type", "all")
                .register(meterRegistry);

        Gauge.builder("blog.articles.published", publishedArticles, AtomicLong::get)
                .description("Number of published articles")
                .tag("status", "published")
                .register(meterRegistry);

        Gauge.builder("blog.articles.draft", draftArticles, AtomicLong::get)
                .description("Number of draft articles")
                .tag("status", "draft")
                .register(meterRegistry);

        // Comment metrics
        Gauge.builder("blog.comments.total", totalComments, AtomicLong::get)
                .description("Total number of comments")
                .register(meterRegistry);

        Gauge.builder("blog.comments.pending", pendingComments, AtomicLong::get)
                .description("Number of pending comments")
                .tag("status", "pending")
                .register(meterRegistry);

        // Subscriber metrics
        Gauge.builder("blog.subscribers.active", activeSubscribers, AtomicLong::get)
                .description("Number of active newsletter subscribers")
                .register(meterRegistry);

        // F-055: Pre-register counters to avoid registry lookup per call
        commentCreatedCounter = meterRegistry.counter("blog.comments.created");
        subscriptionNewCounter = meterRegistry.counter("blog.subscriptions.new");
        subscriptionCancelledCounter = meterRegistry.counter("blog.subscriptions.cancelled");
    }

    @Scheduled(fixedRateString = "${scheduling.metrics-update-ms:60000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void updateMetrics() {
        // Combine all metric queries into a single subscribe to avoid 6 independent subscriptions
        reactor.core.publisher.Mono.zip(
                articleRepository.countAll().onErrorReturn(0L),
                articleRepository.countByStatus("PUBLISHED").onErrorReturn(0L),
                articleRepository.countByStatus("DRAFT").onErrorReturn(0L),
                commentRepository.count().onErrorReturn(0L),
                commentRepository.countByStatus("PENDING").onErrorReturn(0L),
                subscriberRepository.countConfirmed().onErrorReturn(0L)
        ).subscribe(
                tuple -> {
                    totalArticles.set(tuple.getT1());
                    publishedArticles.set(tuple.getT2());
                    draftArticles.set(tuple.getT3());
                    totalComments.set(tuple.getT4());
                    pendingComments.set(tuple.getT5());
                    activeSubscribers.set(tuple.getT6());
                },
                error -> log.warn("Failed to update metrics: {}", error.getMessage())
        );
    }

    // Counter for specific events - call from services
    public void incrementArticleViews(String slug) {
        // F-054: Added slug tag for per-article view tracking
        meterRegistry.counter("blog.article.views.total", "slug", slug).increment();
    }

    public void incrementArticleLikes(String slug) {
        // F-054: Added slug tag for per-article like tracking
        meterRegistry.counter("blog.article.likes.total", "slug", slug).increment();
    }

    public void incrementCommentCreated() {
        // F-055: Use cached counter reference
        commentCreatedCounter.increment();
    }

    public void incrementSubscription() {
        // F-055: Use cached counter reference
        subscriptionNewCounter.increment();
    }

    public void incrementUnsubscription() {
        // F-055: Use cached counter reference
        subscriptionCancelledCounter.increment();
    }
}
