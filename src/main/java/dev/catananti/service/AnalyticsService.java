package dev.catananti.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.catananti.dto.AnalyticsEventRequest;
import dev.catananti.dto.AnalyticsSummary;
import dev.catananti.entity.AnalyticsEvent;
import dev.catananti.repository.AnalyticsRepository;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.util.IpAddressExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
// TODO F-142: Add pagination to getEvents/analytics queries to avoid unbounded result sets
public class AnalyticsService {

    // F-141: Allowed event types to prevent arbitrary data injection
    private static final java.util.Set<String> ALLOWED_EVENT_TYPES = java.util.Set.of(
            "VIEW", "LIKE", "SHARE", "CLICK", "SCROLL_DEPTH", "DOWNLOAD"
    );

    private final AnalyticsRepository analyticsRepository;
    private final ArticleRepository articleRepository;
    private final ObjectMapper objectMapper;
    private final IdService idService;
    private final DatabaseClient databaseClient;

    public Mono<Void> trackEvent(AnalyticsEventRequest request, ServerHttpRequest httpRequest) {
        // F-141: Validate eventType against allowed set
        String eventType = request.getEventType().toUpperCase();
        if (!ALLOWED_EVENT_TYPES.contains(eventType)) {
            log.warn("Invalid event type rejected: {}", eventType);
            return Mono.error(new IllegalArgumentException("Invalid event type: " + request.getEventType()));
        }

        // Validate articleId exists if provided
        Mono<Boolean> articleValidation = request.getArticleId() != null
                ? articleRepository.existsById(request.getArticleId())
                        .flatMap(exists -> exists ? Mono.just(true) : Mono.empty())
                : Mono.just(true);

        return articleValidation
                .flatMap(valid -> {
                    // SEC-08: Anonymize IP for GDPR/LGPD compliance
                    String userIp = IpAddressExtractor.anonymizeIp(
                            IpAddressExtractor.extractClientIp(httpRequest));
                    String userAgent = httpRequest.getHeaders().getFirst("User-Agent");
                    
                    String metadataJson = null;
                    if (request.getMetadata() != null) {
                        try {
                            metadataJson = objectMapper.writeValueAsString(request.getMetadata());
                        } catch (JsonProcessingException e) {
                            log.warn("Failed to serialize metadata: {}", e.getMessage());
                        }
                    }

                    AnalyticsEvent event = AnalyticsEvent.builder()
                            .id(idService.nextId())
                            .articleId(request.getArticleId())
                            .eventType(eventType)
                            .userIp(userIp)
                            .userAgent(userAgent)
                            .referrer(request.getReferrer())
                            .metadata(metadataJson)
                            .createdAt(LocalDateTime.now())
                            .build();

                    return analyticsRepository.save(event)
                            .doOnSuccess(e -> log.debug("Analytics event tracked: {} for article {}", 
                                    e.getEventType(), e.getArticleId()))
                            .then();
                })
                .switchIfEmpty(Mono.empty()); // Silently ignore invalid articleIds
    }

    public Mono<Void> trackArticleView(String slug, ServerHttpRequest httpRequest) {
        return articleRepository.findBySlug(slug)
                .flatMap(article -> {
                    AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                            .articleId(article.getId())
                            .eventType("VIEW")
                            .referrer(httpRequest.getHeaders().getFirst("Referer"))
                            .build();
                    return trackEvent(request, httpRequest);
                });
    }

    public Mono<AnalyticsSummary> getAnalyticsSummary(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        // BUG-RT6: Fall back to article table counts when analytics_events has no data
        Mono<Long> totalViewsMono = analyticsRepository.countByEventTypeSince("VIEW", since)
                .flatMap(count -> count > 0 ? Mono.just(count) : articleRepository.sumViewsCount());
        Mono<Long> totalLikesMono = analyticsRepository.countByEventTypeSince("LIKE", since)
                .flatMap(count -> count > 0 ? Mono.just(count) : articleRepository.sumLikesCount());

        return Mono.zip(
                totalViewsMono,
                totalLikesMono,
                analyticsRepository.countByEventTypeSince("SHARE", since),
                getDailyViews(since),
                getTopArticles(since, 10),
                getTopReferrers(since, 10)
        ).map(tuple -> AnalyticsSummary.builder()
                .totalViews(tuple.getT1())
                .totalLikes(tuple.getT2())
                .totalShares(tuple.getT3())
                .dailyViews(tuple.getT4())
                .topArticles(tuple.getT5())
                .topReferrers(tuple.getT6())
                .build());
    }

    // BUG-12: Replaced Object[] queries with DatabaseClient row mapping
    private Mono<List<AnalyticsSummary.DailyStat>> getDailyViews(LocalDateTime since) {
        return databaseClient.sql("""
                SELECT CAST(created_at AS DATE) AS stat_date, COUNT(*) AS cnt
                FROM analytics_events
                WHERE event_type = :eventType AND created_at >= :since
                GROUP BY CAST(created_at AS DATE)
                ORDER BY stat_date
                """)
                .bind("eventType", "VIEW")
                .bind("since", since)
                .map((row, meta) -> AnalyticsSummary.DailyStat.builder()
                        .date(row.get("stat_date", LocalDate.class))
                        .count(row.get("cnt", Long.class))
                        .build())
                .all()
                .collectList()
                .defaultIfEmpty(new ArrayList<>());
    }

    private Mono<List<AnalyticsSummary.TopArticle>> getTopArticles(LocalDateTime since, int limit) {
        return databaseClient.sql("""
                SELECT article_id, COUNT(*) AS cnt
                FROM analytics_events
                WHERE event_type = 'VIEW' AND created_at >= :since
                GROUP BY article_id
                ORDER BY cnt DESC
                LIMIT :limit
                """)
                .bind("since", since)
                .bind("limit", limit)
                .map((row, meta) -> Map.entry(
                        row.get("article_id", Long.class),
                        row.get("cnt", Long.class)))
                .all()
                .collectList()
                .flatMap(entries -> {
                    if (entries.isEmpty()) return Mono.just(new ArrayList<AnalyticsSummary.TopArticle>());
                    List<Long> articleIds = entries.stream().map(Map.Entry::getKey).toList();
                    Map<Long, Long> viewsMap = entries.stream()
                            .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
                    return articleRepository.findAllById(articleIds)
                            .collectMap(article -> article.getId(), article -> article)
                            .map(articleMap -> entries.stream()
                                    .map(entry -> {
                                        var article = articleMap.get(entry.getKey());
                                        return AnalyticsSummary.TopArticle.builder()
                                                .articleId(entry.getKey().toString())
                                                .title(article != null ? article.getTitle() : "Unknown")
                                                .slug(article != null ? article.getSlug() : "unknown")
                                                .views(entry.getValue())
                                                .build();
                                    })
                                    .toList());
                })
                .defaultIfEmpty(new ArrayList<>())
                .flatMap(list -> list.isEmpty() ? getTopArticlesFromViewCounts(limit) : Mono.just(list));
    }

    // BUG-RT6: Fallback to article view counts when analytics_events table has no data
    private Mono<List<AnalyticsSummary.TopArticle>> getTopArticlesFromViewCounts(int limit) {
        return databaseClient.sql("""
                SELECT id, title, slug, views_count
                FROM articles
                WHERE views_count > 0
                ORDER BY views_count DESC
                LIMIT :limit
                """)
                .bind("limit", limit)
                .map((row, meta) -> AnalyticsSummary.TopArticle.builder()
                        .articleId(row.get("id", Long.class).toString())
                        .title(row.get("title", String.class))
                        .slug(row.get("slug", String.class))
                        .views(row.get("views_count", Long.class))
                        .build())
                .all()
                .collectList()
                .defaultIfEmpty(new ArrayList<>());
    }

    private Mono<List<AnalyticsSummary.TopReferrer>> getTopReferrers(LocalDateTime since, int limit) {
        return databaseClient.sql("""
                SELECT referrer, COUNT(*) AS cnt
                FROM analytics_events
                WHERE referrer IS NOT NULL AND created_at >= :since
                GROUP BY referrer
                ORDER BY cnt DESC
                LIMIT :limit
                """)
                .bind("since", since)
                .bind("limit", limit)
                .map((row, meta) -> AnalyticsSummary.TopReferrer.builder()
                        .referrer(row.get("referrer", String.class))
                        .count(row.get("cnt", Long.class))
                        .build())
                .all()
                .collectList()
                .defaultIfEmpty(new ArrayList<>());
    }

    public Mono<Long> getArticleViewCount(Long articleId) {
        return analyticsRepository.countByArticleIdAndEventType(articleId, "VIEW");
    }

    /**
     * Author-scoped analytics summary for DEV/EDITOR users.
     * Only includes data from articles owned by the given author.
     */
    public Mono<AnalyticsSummary> getAnalyticsSummaryByAuthor(int days, Long authorId) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        Mono<Long> totalViewsMono = analyticsRepository.countByAuthorIdAndEventTypeSince(authorId, "VIEW", since)
                .flatMap(count -> count > 0 ? Mono.just(count) : articleRepository.sumViewsCountByAuthorId(authorId));
        Mono<Long> totalLikesMono = analyticsRepository.countByAuthorIdAndEventTypeSince(authorId, "LIKE", since)
                .flatMap(count -> count > 0 ? Mono.just(count) : Mono.just(0L));

        return Mono.zip(
                totalViewsMono,
                totalLikesMono,
                analyticsRepository.countByAuthorIdAndEventTypeSince(authorId, "SHARE", since),
                getDailyViewsByAuthor(since, authorId),
                getTopArticlesByAuthor(since, 10, authorId),
                getTopReferrersByAuthor(since, 10, authorId)
        ).map(tuple -> AnalyticsSummary.builder()
                .totalViews(tuple.getT1())
                .totalLikes(tuple.getT2())
                .totalShares(tuple.getT3())
                .dailyViews(tuple.getT4())
                .topArticles(tuple.getT5())
                .topReferrers(tuple.getT6())
                .build());
    }

    private Mono<List<AnalyticsSummary.DailyStat>> getDailyViewsByAuthor(LocalDateTime since, Long authorId) {
        return databaseClient.sql("""
                SELECT CAST(ae.created_at AS DATE) AS stat_date, COUNT(*) AS cnt
                FROM analytics_events ae
                JOIN articles a ON ae.article_id = a.id
                WHERE ae.event_type = :eventType AND ae.created_at >= :since AND a.author_id = :authorId
                GROUP BY CAST(ae.created_at AS DATE)
                ORDER BY stat_date
                """)
                .bind("eventType", "VIEW")
                .bind("since", since)
                .bind("authorId", authorId)
                .map((row, meta) -> AnalyticsSummary.DailyStat.builder()
                        .date(row.get("stat_date", LocalDate.class))
                        .count(row.get("cnt", Long.class))
                        .build())
                .all()
                .collectList()
                .defaultIfEmpty(new ArrayList<>());
    }

    private Mono<List<AnalyticsSummary.TopArticle>> getTopArticlesByAuthor(LocalDateTime since, int limit, Long authorId) {
        return databaseClient.sql("""
                SELECT ae.article_id, COUNT(*) AS cnt
                FROM analytics_events ae
                JOIN articles a ON ae.article_id = a.id
                WHERE ae.event_type = 'VIEW' AND ae.created_at >= :since AND a.author_id = :authorId
                GROUP BY ae.article_id
                ORDER BY cnt DESC
                LIMIT :limit
                """)
                .bind("since", since)
                .bind("authorId", authorId)
                .bind("limit", limit)
                .map((row, meta) -> Map.entry(
                        row.get("article_id", Long.class),
                        row.get("cnt", Long.class)))
                .all()
                .collectList()
                .flatMap(entries -> {
                    if (entries.isEmpty()) return getTopArticlesFromViewCountsByAuthor(limit, authorId);
                    List<Long> articleIds = entries.stream().map(Map.Entry::getKey).toList();
                    Map<Long, Long> viewsMap = entries.stream()
                            .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
                    return articleRepository.findAllById(articleIds)
                            .collectMap(article -> article.getId(), article -> article)
                            .map(articleMap -> entries.stream()
                                    .map(entry -> {
                                        var article = articleMap.get(entry.getKey());
                                        return AnalyticsSummary.TopArticle.builder()
                                                .articleId(entry.getKey().toString())
                                                .title(article != null ? article.getTitle() : "Unknown")
                                                .slug(article != null ? article.getSlug() : "unknown")
                                                .views(entry.getValue())
                                                .build();
                                    })
                                    .toList());
                })
                .defaultIfEmpty(new ArrayList<>());
    }

    private Mono<List<AnalyticsSummary.TopArticle>> getTopArticlesFromViewCountsByAuthor(int limit, Long authorId) {
        return databaseClient.sql("""
                SELECT id, title, slug, views_count
                FROM articles
                WHERE author_id = :authorId AND views_count > 0
                ORDER BY views_count DESC
                LIMIT :limit
                """)
                .bind("authorId", authorId)
                .bind("limit", limit)
                .map((row, meta) -> AnalyticsSummary.TopArticle.builder()
                        .articleId(row.get("id", Long.class).toString())
                        .title(row.get("title", String.class))
                        .slug(row.get("slug", String.class))
                        .views(row.get("views_count", Long.class))
                        .build())
                .all()
                .collectList()
                .defaultIfEmpty(new ArrayList<>());
    }

    private Mono<List<AnalyticsSummary.TopReferrer>> getTopReferrersByAuthor(LocalDateTime since, int limit, Long authorId) {
        return databaseClient.sql("""
                SELECT ae.referrer, COUNT(*) AS cnt
                FROM analytics_events ae
                JOIN articles a ON ae.article_id = a.id
                WHERE ae.referrer IS NOT NULL AND ae.created_at >= :since AND a.author_id = :authorId
                GROUP BY ae.referrer
                ORDER BY cnt DESC
                LIMIT :limit
                """)
                .bind("since", since)
                .bind("authorId", authorId)
                .bind("limit", limit)
                .map((row, meta) -> AnalyticsSummary.TopReferrer.builder()
                        .referrer(row.get("referrer", String.class))
                        .count(row.get("cnt", Long.class))
                        .build())
                .all()
                .collectList()
                .defaultIfEmpty(new ArrayList<>());
    }
}
