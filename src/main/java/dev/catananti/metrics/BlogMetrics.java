package dev.catananti.metrics;

import dev.catananti.entity.ArticleStatus;
import dev.catananti.entity.CommentStatus;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.SubscriberRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    // Q12.3: Security & operational counters
    private Counter loginSuccessCounter;
    private Counter loginFailureCounter;
    private Counter pdfGeneratedCounter;
    private Timer pdfGenerationTimer;

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
        commentCreatedCounter = meterRegistry.counter("blog.comment.events.created");
        subscriptionNewCounter = meterRegistry.counter("blog.subscriptions.new");
        subscriptionCancelledCounter = meterRegistry.counter("blog.subscriptions.cancelled");

        // Q12.3: Security & operational metrics
        loginSuccessCounter = meterRegistry.counter("blog.auth.login", "result", "success");
        loginFailureCounter = meterRegistry.counter("blog.auth.login", "result", "failure");
        pdfGeneratedCounter = meterRegistry.counter("blog.pdf.generated");
        pdfGenerationTimer = Timer.builder("blog.pdf.generation.duration")
                .description("Time to generate PDF documents")
                .register(meterRegistry);
    }

    public reactor.core.publisher.Mono<Void> updateMetrics() {
        return reactor.core.publisher.Mono.zip(
                        articleRepository.countAll().onErrorReturn(0L),
                        articleRepository.countByStatus(ArticleStatus.PUBLISHED.name()).onErrorReturn(0L),
                        articleRepository.countByStatus(ArticleStatus.DRAFT.name()).onErrorReturn(0L),
                        commentRepository.count().onErrorReturn(0L),
                        commentRepository.countByStatus(CommentStatus.PENDING.name()).onErrorReturn(0L),
                        subscriberRepository.countConfirmed().onErrorReturn(0L)
                )
                .doOnNext(tuple -> {
                    totalArticles.set(tuple.getT1());
                    publishedArticles.set(tuple.getT2());
                    draftArticles.set(tuple.getT3());
                    totalComments.set(tuple.getT4());
                    pendingComments.set(tuple.getT5());
                    activeSubscribers.set(tuple.getT6());
                })
                .doOnError(e -> log.warn("Failed to update metrics: {}", e.getMessage(), e))
                .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                .then();
    }

    @Scheduled(fixedRateString = "${scheduling.metrics-update-ms:60000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void updateMetricsScheduled() {
        updateMetrics().subscribe();
    }

    // Counter for specific events - call from services
    public void incrementArticleViews(String slug) {
        // Cardinality: no per-slug tag — one Micrometer/Datadog series per article slug is an
        // unbounded foot-gun. Per-article view counts live in the DB (articles.views_count);
        // this is the aggregate, alertable counter. The slug param is kept for call-site clarity.
        meterRegistry.counter("blog.article.views.total").increment();
    }

    public void incrementArticleLikes(String slug) {
        // Cardinality: aggregate counter only (no per-slug tag) — see incrementArticleViews.
        meterRegistry.counter("blog.article.likes.total").increment();
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

    // Q12.3: Security metrics
    public void incrementLoginSuccess() {
        loginSuccessCounter.increment();
    }

    public void incrementLoginFailure() {
        loginFailureCounter.increment();
    }

    // Q12.3: PDF generation metrics
    public void incrementPdfGenerated() {
        pdfGeneratedCounter.increment();
    }

    public Timer getPdfGenerationTimer() {
        return pdfGenerationTimer;
    }
}
