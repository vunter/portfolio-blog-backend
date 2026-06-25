package dev.catananti.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.catananti.dto.AnalyticsComparison;
import dev.catananti.dto.AnalyticsEventRequest;
import dev.catananti.dto.AnalyticsSummary;
import dev.catananti.dto.SearchAnalyticsResponse;
import dev.catananti.entity.AnalyticsEvent;
import dev.catananti.repository.AnalyticsRepository;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.util.DeviceParser;
import dev.catananti.util.IpAddressExtractor;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    // F-141: Allowed event types to prevent arbitrary data injection
    private static final java.util.Set<String> ALLOWED_EVENT_TYPES = java.util.Set.of(
            "VIEW", "LIKE", "SHARE", "CLICK", "SCROLL_DEPTH", "DOWNLOAD", "TIME_ON_PAGE", "PAGE_VIEW", "WEB_VITAL"
    );

    private final AnalyticsRepository analyticsRepository;
    private final ArticleRepository articleRepository;
    private final ObjectMapper objectMapper;
    private final IdService idService;
    private final DatabaseClient databaseClient;
    private final GeoIPService geoIPService;
    private final dev.catananti.scheduler.SchedulerLock schedulerLock;

    @Value("${app.analytics.retention-days:90}")
    private int retentionDays;

    @Value("${app.url:http://localhost:8080}")
    private String appUrl;

    /**
     * Extract the host from a URL, returning null on parse failure.
     */
    private String extractHost(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns true if the referrer is from the same site (self-referral).
     */
    private boolean isSelfReferral(String referrer) {
        if (referrer == null || referrer.isBlank()) return false;
        String refHost = extractHost(referrer);
        String appHost = extractHost(appUrl);
        if (refHost == null || appHost == null) return false;
        return refHost.equalsIgnoreCase(appHost);
    }

    public Mono<Void> trackEvent(AnalyticsEventRequest request, ServerHttpRequest httpRequest) {
        // F-141: Validate eventType against allowed set
        String eventType = request.getEventType().toUpperCase();
        if (!ALLOWED_EVENT_TYPES.contains(eventType)) {
            log.warn("Invalid event type rejected: {}", eventType);
            return Mono.error(new IllegalArgumentException("error.invalid_event_type"));
        }

        // Validate articleId exists if provided.
        // Returning Mono.empty for "not found" silently drops the event downstream;
        // surface it as an error so the caller knows the event was not recorded.
        Mono<Boolean> articleValidation = request.getArticleId() != null
                ? articleRepository.existsById(request.getArticleId())
                        .flatMap(exists -> exists
                                ? Mono.just(true)
                                : Mono.error(new IllegalArgumentException("error.article_not_found")))
                : Mono.just(true);

        return articleValidation
                .flatMap(valid -> {
                    // Resolve country from raw IP before anonymization
                    String rawIp = IpAddressExtractor.extractClientIp(httpRequest);

                    // SEC-08: Anonymize IP for GDPR/LGPD compliance
                    String userIp = IpAddressExtractor.anonymizeIp(rawIp);
                    String userAgent = httpRequest.getHeaders().getFirst("User-Agent");
                    
                    // Parse device info from user-agent
                    String deviceType = DeviceParser.parseDeviceType(userAgent);
                    String browserFamily = DeviceParser.parseBrowserFamily(userAgent);
                    String osFamily = DeviceParser.parseOsFamily(userAgent);

                    // F-ASYNC-03: Resolve country code reactively
                    return geoIPService.getCountryCode(rawIp)
                            .defaultIfEmpty("")
                            .flatMap(countryCode -> {
                    String resolvedCountry = countryCode.isEmpty() ? null : countryCode;

                    String metadataJson = null;
                    if (request.getMetadata() != null) {
                        try {
                            metadataJson = objectMapper.writeValueAsString(request.getMetadata());
                        } catch (JsonProcessingException e) {
                            log.warn("Failed to serialize metadata: {}", e.getMessage(), e);
                        }
                    }

                    long eventId = idService.nextId();
                    Long articleId = request.getArticleId();
                    String referrer = request.getReferrer();
                    LocalDateTime now = LocalDateTime.now();

                    var spec = databaseClient.sql("""
                            INSERT INTO analytics_events (id, article_id, event_type, user_ip, user_agent, device_type, browser_family, os_family, country_code, referrer, metadata, created_at)
                            VALUES (:id, :articleId, :eventType, :userIp, :userAgent, :deviceType, :browserFamily, :osFamily, :countryCode, :referrer, :metadata::jsonb, :createdAt)
                            """)
                            .bind("id", eventId)
                            .bind("eventType", eventType)
                            .bind("userIp", userIp != null ? userIp : "")
                            .bind("deviceType", deviceType)
                            .bind("browserFamily", browserFamily)
                            .bind("osFamily", osFamily)
                            .bind("createdAt", now);

                    // Bind nullable params
                    spec = articleId != null ? spec.bind("articleId", articleId) : spec.bindNull("articleId", Long.class);
                    spec = userAgent != null ? spec.bind("userAgent", userAgent) : spec.bindNull("userAgent", String.class);
                    spec = resolvedCountry != null ? spec.bind("countryCode", resolvedCountry) : spec.bindNull("countryCode", String.class);
                    spec = referrer != null ? spec.bind("referrer", referrer) : spec.bindNull("referrer", String.class);
                    spec = metadataJson != null ? spec.bind("metadata", metadataJson) : spec.bindNull("metadata", String.class);

                    return spec.then()
                            .doOnSuccess(v -> log.debug("Analytics event tracked: {} for article {}",
                                    eventType, articleId));
                    }); // end geoIPService.getCountryCode flatMap
                });
    }

    public Mono<Void> trackArticleView(String slug, ServerHttpRequest httpRequest) {
        return articleRepository.findBySlug(slug)
                .flatMap(article -> {
                    String rawReferrer = httpRequest.getHeaders().getFirst("Referer");
                    // Only store external referrers — internal navigation is noise
                    String referrer = (rawReferrer != null && !isSelfReferral(rawReferrer)) ? rawReferrer : null;
                    AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                            .articleId(article.getId())
                            .eventType("VIEW")
                            .referrer(referrer)
                            .build();
                    return trackEvent(request, httpRequest);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Article view ignored: slug '{}' not found", slug);
                    return Mono.empty();
                }));
    }

    public Mono<AnalyticsSummary> getAnalyticsSummary(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        // M11: return the real windowed counts (0 when empty); no all-time fallback,
        // which contradicted the daily/unique fields.
        Mono<Long> totalViewsMono = analyticsRepository.countByEventTypeSince("VIEW", since);
        Mono<Long> totalLikesMono = analyticsRepository.countByEventTypeSince("LIKE", since);
        Mono<Long> totalSharesMono = analyticsRepository.countByEventTypeSince("SHARE", since);

        return buildSummary(since, null, totalViewsMono, totalLikesMono, totalSharesMono);
    }

    // ARCH-2: shared assembly for the plain + *ByAuthor summaries. The two public methods
    // differ only in how the total view/like/share counts are sourced; everything below
    // (unique visitors, daily views, top-N breakdowns and the builder) is identical and is
    // scoped to the author when authorId is non-null.
    private Mono<AnalyticsSummary> buildSummary(LocalDateTime since, Long authorId,
                                                Mono<Long> totalViewsMono, Mono<Long> totalLikesMono,
                                                Mono<Long> totalSharesMono) {
        Mono<List<AnalyticsSummary.DeviceStat>> devicesMono = getTopDevices(since, authorId);
        Mono<List<AnalyticsSummary.BrowserStat>> browsersMono = getTopBrowsers(since, authorId);
        Mono<List<AnalyticsSummary.CountryStat>> countriesMono = getTopCountries(since, authorId);

        return Mono.zip(
                totalViewsMono,
                totalLikesMono,
                totalSharesMono,
                getUniqueVisitors(since, authorId),
                getDailyViews(since, authorId),
                getTopArticles(since, 10, authorId),
                getTopReferrers(since, 10, authorId),
                getTopSources(since, 10, authorId)
        ).flatMap(tuple -> Mono.zip(devicesMono, browsersMono, countriesMono)
                .map(extra -> AnalyticsSummary.builder()
                .totalViews(tuple.getT1())
                .totalLikes(tuple.getT2())
                .totalShares(tuple.getT3())
                .uniqueVisitors(tuple.getT4())
                .dailyViews(tuple.getT5())
                .topArticles(tuple.getT6())
                .topReferrers(tuple.getT7())
                .topSources(tuple.getT8())
                .topDevices(extra.getT1())
                .topBrowsers(extra.getT2())
                .topCountries(extra.getT3())
                .build()));
    }

    // ARCH-2: SQL fragment helpers shared by every plain/*ByAuthor query pair below.
    // When authorId is non-null they splice in the articles join + author_id predicate
    // and bind the parameter; when null they are no-ops, leaving the plain query intact.
    // All analytics_events queries alias the table as "ae" so the same column references
    // (ae.<col>) are valid with or without the join.
    private static String authorJoin(Long authorId) {
        return authorId != null ? " JOIN articles a ON ae.article_id = a.id" : "";
    }

    private static String authorPredicate(Long authorId) {
        return authorId != null ? " AND a.author_id = :authorId" : "";
    }

    private static DatabaseClient.GenericExecuteSpec bindAuthor(DatabaseClient.GenericExecuteSpec spec, Long authorId) {
        return authorId != null ? spec.bind("authorId", authorId) : spec;
    }

    // ARCH-2: shared impl for the plain + *ByAuthor variants. When authorId is non-null
    // an articles join + author_id predicate are appended; otherwise behaviour is unchanged.
    private Mono<Long> getUniqueVisitors(LocalDateTime since, Long authorId) {
        var spec = databaseClient.sql(
                "SELECT COUNT(DISTINCT ae.user_ip) AS cnt FROM analytics_events ae"
                        + authorJoin(authorId)
                        + " WHERE ae.event_type = 'VIEW' AND ae.created_at >= :since"
                        + " AND ae.device_type != 'Bot'"
                        + authorPredicate(authorId))
                .bind("since", since);
        spec = bindAuthor(spec, authorId);
        return spec
                .map((row, meta) -> row.get("cnt", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    // BUG-12: Replaced Object[] queries with DatabaseClient row mapping
    private Mono<List<AnalyticsSummary.DailyStat>> getDailyViews(LocalDateTime since, Long authorId) {
        var spec = databaseClient.sql(
                "SELECT CAST(ae.created_at AS DATE) AS stat_date, COUNT(*) AS cnt"
                        + " FROM analytics_events ae"
                        + authorJoin(authorId)
                        + " WHERE ae.event_type = :eventType AND ae.created_at >= :since"
                        + " AND ae.device_type != 'Bot'"
                        + authorPredicate(authorId)
                        + " GROUP BY CAST(ae.created_at AS DATE)"
                        + " ORDER BY stat_date"
                        + " LIMIT 366")
                .bind("eventType", "VIEW")
                .bind("since", since);
        spec = bindAuthor(spec, authorId);
        return spec
                .map((row, meta) -> AnalyticsSummary.DailyStat.builder()
                        .date(row.get("stat_date", LocalDate.class))
                        .count(row.get("cnt", Long.class))
                        .build())
                .all()
                .collectList();
    }

    private Mono<List<AnalyticsSummary.TopArticle>> getTopArticles(LocalDateTime since, int limit, Long authorId) {
        var spec = databaseClient.sql(
                "SELECT ae.article_id, COUNT(*) AS cnt"
                        + " FROM analytics_events ae"
                        + authorJoin(authorId)
                        + " WHERE ae.event_type = 'VIEW' AND ae.created_at >= :since"
                        + authorPredicate(authorId)
                        + " GROUP BY ae.article_id"
                        + " ORDER BY cnt DESC"
                        + " LIMIT :limit")
                .bind("since", since)
                .bind("limit", limit);
        spec = bindAuthor(spec, authorId);
        // M11: return the windowed (possibly empty) period result; no all-time top-10 fallback.
        return spec
                .map((row, meta) -> Map.entry(
                        row.get("article_id", Long.class),
                        row.get("cnt", Long.class)))
                .all()
                .collectList()
                .flatMap(entries -> {
                    if (entries.isEmpty()) return Mono.<List<AnalyticsSummary.TopArticle>>just(List.of());
                    List<Long> articleIds = entries.stream().map(Map.Entry::getKey).toList();
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
                });
    }

    private Mono<List<AnalyticsSummary.TopReferrer>> getTopReferrers(LocalDateTime since, int limit, Long authorId) {
        var spec = databaseClient.sql(
                "SELECT ae.referrer, COUNT(*) AS cnt"
                        + " FROM analytics_events ae"
                        + authorJoin(authorId)
                        + " WHERE ae.referrer IS NOT NULL AND ae.referrer != '' AND ae.created_at >= :since"
                        + authorPredicate(authorId)
                        + " GROUP BY ae.referrer"
                        + " ORDER BY cnt DESC"
                        + " LIMIT :limit")
                .bind("since", since)
                .bind("limit", limit);
        spec = bindAuthor(spec, authorId);
        return spec
                .map((row, meta) -> AnalyticsSummary.TopReferrer.builder()
                        .referrer(row.get("referrer", String.class))
                        .count(row.get("cnt", Long.class))
                        .build())
                .all()
                .collectList();
    }

    private Mono<List<AnalyticsSummary.TopSource>> getTopSources(LocalDateTime since, int limit, Long authorId) {
        var spec = databaseClient.sql(
                "SELECT ae.metadata->>'utm_source' AS source, ae.metadata->>'utm_medium' AS medium, COUNT(*) AS cnt"
                        + " FROM analytics_events ae"
                        + authorJoin(authorId)
                        + " WHERE ae.metadata IS NOT NULL AND ae.metadata->>'utm_source' IS NOT NULL AND ae.created_at >= :since"
                        + authorPredicate(authorId)
                        + " GROUP BY ae.metadata->>'utm_source', ae.metadata->>'utm_medium'"
                        + " ORDER BY cnt DESC"
                        + " LIMIT :limit")
                .bind("since", since)
                .bind("limit", limit);
        spec = bindAuthor(spec, authorId);
        return spec
                .map((row, meta) -> AnalyticsSummary.TopSource.builder()
                        .source(row.get("source", String.class))
                        .medium(row.get("medium", String.class))
                        .count(row.get("cnt", Long.class))
                        .build())
                .all()
                .collectList();
    }

    public Mono<Long> getArticleViewCount(Long articleId) {
        return analyticsRepository.countByArticleIdAndEventType(articleId, "VIEW");
    }

    /**
     * Compare current period metrics with the previous period of the same length.
     */
    public Mono<AnalyticsComparison> getAnalyticsComparison(int days) {
        return buildComparison(days, null);
    }

    public Mono<AnalyticsComparison> getAnalyticsComparisonByAuthor(int days, Long authorId) {
        return buildComparison(days, authorId);
    }

    // ARCH-2: shared impl for the plain + *ByAuthor comparison. When authorId is non-null
    // every count is scoped to that author; otherwise behaviour is unchanged.
    private Mono<AnalyticsComparison> buildComparison(int days, Long authorId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime currentStart = now.minusDays(days);
        LocalDateTime previousStart = now.minusDays(days * 2L);

        return Mono.zip(
                countSinceScoped("VIEW", currentStart, authorId),
                countSinceScoped("LIKE", currentStart, authorId),
                countSinceScoped("SHARE", currentStart, authorId),
                countByEventTypeBetween("VIEW", previousStart, currentStart, authorId),
                countByEventTypeBetween("LIKE", previousStart, currentStart, authorId),
                countByEventTypeBetween("SHARE", previousStart, currentStart, authorId)
        ).map(tuple -> AnalyticsComparison.builder()
                .currentViews(tuple.getT1())
                .currentLikes(tuple.getT2())
                .currentShares(tuple.getT3())
                .previousViews(tuple.getT4())
                .previousLikes(tuple.getT5())
                .previousShares(tuple.getT6())
                .build());
    }

    private Mono<Long> countSinceScoped(String eventType, LocalDateTime since, Long authorId) {
        return authorId != null
                ? analyticsRepository.countByAuthorIdAndEventTypeSince(authorId, eventType, since)
                : analyticsRepository.countByEventTypeSince(eventType, since);
    }

    private Mono<Long> countByEventTypeBetween(String eventType, LocalDateTime from, LocalDateTime to, Long authorId) {
        var spec = databaseClient.sql(
                "SELECT COUNT(*) AS cnt FROM analytics_events ae"
                        + authorJoin(authorId)
                        + " WHERE ae.event_type = :eventType AND ae.created_at >= :from AND ae.created_at < :to"
                        + authorPredicate(authorId))
                .bind("eventType", eventType)
                .bind("from", from)
                .bind("to", to);
        spec = bindAuthor(spec, authorId);
        return spec
                .map((row, meta) -> row.get("cnt", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    /**
     * Author-scoped analytics summary for DEV users.
     * Only includes data from articles owned by the given author.
     */
    public Mono<AnalyticsSummary> getAnalyticsSummaryByAuthor(int days, Long authorId) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        // M11: return the real windowed counts (0 when empty); no all-time fallback.
        Mono<Long> totalViewsMono = analyticsRepository.countByAuthorIdAndEventTypeSince(authorId, "VIEW", since);
        Mono<Long> totalLikesMono = analyticsRepository.countByAuthorIdAndEventTypeSince(authorId, "LIKE", since);
        Mono<Long> totalSharesMono = analyticsRepository.countByAuthorIdAndEventTypeSince(authorId, "SHARE", since);

        return buildSummary(since, authorId, totalViewsMono, totalLikesMono, totalSharesMono);
    }

    private Mono<List<AnalyticsSummary.DeviceStat>> getTopDevices(LocalDateTime since, Long authorId) {
        var spec = databaseClient.sql(
                "SELECT ae.device_type, COUNT(*) AS cnt"
                        + " FROM analytics_events ae"
                        + authorJoin(authorId)
                        + " WHERE ae.device_type IS NOT NULL AND ae.device_type != 'UNKNOWN' AND ae.created_at >= :since"
                        + authorPredicate(authorId)
                        + " GROUP BY ae.device_type"
                        + " ORDER BY cnt DESC")
                .bind("since", since);
        spec = bindAuthor(spec, authorId);
        return spec
                .map((row, meta) -> AnalyticsSummary.DeviceStat.builder()
                        .deviceType(row.get("device_type", String.class))
                        .count(row.get("cnt", Long.class))
                        .build())
                .all()
                .collectList();
    }

    private Mono<List<AnalyticsSummary.BrowserStat>> getTopBrowsers(LocalDateTime since, Long authorId) {
        var spec = databaseClient.sql(
                "SELECT ae.browser_family, COUNT(*) AS cnt"
                        + " FROM analytics_events ae"
                        + authorJoin(authorId)
                        + " WHERE ae.browser_family IS NOT NULL AND ae.browser_family != 'Unknown' AND ae.created_at >= :since"
                        + authorPredicate(authorId)
                        + " GROUP BY ae.browser_family"
                        + " ORDER BY cnt DESC"
                        + " LIMIT 10")
                .bind("since", since);
        spec = bindAuthor(spec, authorId);
        return spec
                .map((row, meta) -> AnalyticsSummary.BrowserStat.builder()
                        .browser(row.get("browser_family", String.class))
                        .count(row.get("cnt", Long.class))
                        .build())
                .all()
                .collectList();
    }

    private Mono<List<AnalyticsSummary.CountryStat>> getTopCountries(LocalDateTime since, Long authorId) {
        var spec = databaseClient.sql(
                "SELECT ae.country_code, COUNT(*) AS cnt"
                        + " FROM analytics_events ae"
                        + authorJoin(authorId)
                        + " WHERE ae.country_code IS NOT NULL AND ae.created_at >= :since"
                        + authorPredicate(authorId)
                        + " GROUP BY ae.country_code"
                        + " ORDER BY cnt DESC"
                        + " LIMIT 10")
                .bind("since", since);
        spec = bindAuthor(spec, authorId);
        return spec
                .map((row, meta) -> AnalyticsSummary.CountryStat.builder()
                        .countryCode(row.get("country_code", String.class))
                        .count(row.get("cnt", Long.class))
                        .build())
                .all()
                .collectList();
    }

    private static final String SEARCH_COUNT_SQL =
            "SELECT COUNT(*) FROM search_queries WHERE created_at > NOW() - MAKE_INTERVAL(days => :days)";
    private static final String SEARCH_UNIQUE_COUNT_SQL =
            "SELECT COUNT(DISTINCT query_text) FROM search_queries WHERE created_at > NOW() - MAKE_INTERVAL(days => :days)";
    private static final String TOP_SEARCH_QUERIES_SQL = """
            SELECT query_text, COUNT(*) AS cnt FROM search_queries
            WHERE created_at > NOW() - MAKE_INTERVAL(days => :days)
            GROUP BY query_text ORDER BY cnt DESC LIMIT :topLimit
            """;
    private static final String TOP_ZERO_RESULT_QUERIES_SQL = """
            SELECT query_text, COUNT(*) AS cnt FROM search_queries
            WHERE results_count = 0 AND created_at > NOW() - MAKE_INTERVAL(days => :days)
            GROUP BY query_text ORDER BY cnt DESC LIMIT :topLimit
            """;

    /**
     * Q2.4: Search analytics aggregation — moved from AdminAnalyticsController.
     * Runs four aggregate queries in parallel and returns a typed DTO.
     */
    public Mono<SearchAnalyticsResponse> getSearchAnalytics(int days, int topLimit) {
        return Mono.zip(
                countSearches(SEARCH_COUNT_SQL, days),
                countSearches(SEARCH_UNIQUE_COUNT_SQL, days),
                findTopSearchQueries(TOP_SEARCH_QUERIES_SQL, days, topLimit),
                findTopSearchQueries(TOP_ZERO_RESULT_QUERIES_SQL, days, topLimit)
        ).map(tuple -> new SearchAnalyticsResponse(
                tuple.getT1(),
                tuple.getT2(),
                tuple.getT3(),
                tuple.getT4()
        ));
    }

    private Mono<Long> countSearches(String sql, int days) {
        return databaseClient.sql(sql)
                .bind("days", days)
                .map((row, meta) -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    private Mono<List<SearchAnalyticsResponse.SearchQueryStat>> findTopSearchQueries(String sql, int days, int topLimit) {
        return databaseClient.sql(sql)
                .bind("days", days)
                .bind("topLimit", topLimit)
                .map((row, meta) -> new SearchAnalyticsResponse.SearchQueryStat(
                        row.get("query_text", String.class),
                        row.get("cnt", Long.class)))
                .all()
                .collectList()
                .defaultIfEmpty(List.of());
    }

    public reactor.core.publisher.Mono<Void> cleanupOldEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        return schedulerLock.executeWithLock("analytics-cleanup", Duration.ofMinutes(10),
                analyticsRepository.deleteByCreatedAtBefore(cutoff)
                        .timeout(Duration.ofSeconds(30))
                        .doOnSuccess(count -> log.info("Analytics cleanup: deleted {} events older than {} days", count, retentionDays))
                        .doOnError(e -> log.error("Failed to cleanup old analytics events: {}", e.getMessage(), e))
                        .onErrorComplete()
                        .then()
        );
    }

    /**
     * Cleanup analytics events older than the configured retention period.
     * Runs daily by default.
     */
    @Scheduled(fixedRateString = "${scheduling.analytics-cleanup-ms:86400000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void cleanupOldEventsScheduled() {
        cleanupOldEvents().subscribe();
    }
}
