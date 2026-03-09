package dev.catananti.service;

import dev.catananti.util.IpAddressExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NewsletterTrackingService {

    private final DatabaseClient databaseClient;

    /**
     * Record an email open event. Only if subscriber has analytics_consent = true.
     */
    public Mono<Void> recordOpen(String unsubscribeToken, ServerHttpRequest request) {
        return findSubscriberWithConsent(unsubscribeToken)
                .flatMap(subscriberId -> {
                    String userAgent = request.getHeaders().getFirst("User-Agent");
                    String ip = IpAddressExtractor.anonymizeIp(
                            IpAddressExtractor.extractClientIp(request));
                    return insertEvent(subscriberId, "OPEN", null, userAgent, ip);
                });
    }

    /**
     * Record an email click event. Only if subscriber has analytics_consent = true.
     */
    public Mono<Void> recordClick(String unsubscribeToken, String url, ServerHttpRequest request) {
        return findSubscriberWithConsent(unsubscribeToken)
                .flatMap(subscriberId -> {
                    String userAgent = request.getHeaders().getFirst("User-Agent");
                    String ip = IpAddressExtractor.anonymizeIp(
                            IpAddressExtractor.extractClientIp(request));
                    return insertEvent(subscriberId, "CLICK", url, userAgent, ip);
                });
    }

    /**
     * Find subscriber ID only if they have analytics consent.
     */
    private Mono<Long> findSubscriberWithConsent(String unsubscribeToken) {
        return databaseClient.sql("""
                SELECT id FROM subscribers
                WHERE unsubscribe_token = :token
                AND status = 'CONFIRMED'
                AND analytics_consent = true
                """)
                .bind("token", unsubscribeToken)
                .map((row, meta) -> row.get("id", Long.class))
                .one();
    }

    private Mono<Void> insertEvent(Long subscriberId, String eventType, String linkUrl,
                                    String userAgent, String ipAddress) {
        var spec = databaseClient.sql("""
                INSERT INTO newsletter_events (subscriber_id, event_type, link_url, user_agent, ip_address, created_at)
                VALUES (:subscriberId, :eventType, :linkUrl, :userAgent, :ipAddress, :createdAt)
                """)
                .bind("subscriberId", subscriberId)
                .bind("eventType", eventType)
                .bind("createdAt", LocalDateTime.now());

        spec = linkUrl != null ? spec.bind("linkUrl", linkUrl) : spec.bindNull("linkUrl", String.class);
        spec = userAgent != null ? spec.bind("userAgent", userAgent) : spec.bindNull("userAgent", String.class);
        spec = ipAddress != null ? spec.bind("ipAddress", ipAddress) : spec.bindNull("ipAddress", String.class);

        return spec.then()
                .doOnSuccess(v -> log.debug("Newsletter {} event recorded for subscriber {}", eventType, subscriberId));
    }

    /**
     * Get newsletter analytics stats for admin.
     */
    public Mono<Map<String, Object>> getNewsletterAnalytics(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        Mono<Long> totalOpens = countEvents("OPEN", since);
        Mono<Long> totalClicks = countEvents("CLICK", since);
        Mono<Long> uniqueOpeners = countUniqueSubscribers("OPEN", since);
        Mono<Long> uniqueClickers = countUniqueSubscribers("CLICK", since);
        Mono<Long> totalConfirmed = databaseClient.sql(
                "SELECT COUNT(*) AS cnt FROM subscribers WHERE status = 'CONFIRMED'")
                .map((row, meta) -> row.get("cnt", Long.class))
                .one()
                .defaultIfEmpty(0L);
        Mono<List<Map<String, Object>>> topLinks = getTopClickedLinks(since, 10);

        return Mono.zip(totalOpens, totalClicks, uniqueOpeners, uniqueClickers, totalConfirmed, topLinks)
                .map(tuple -> Map.of(
                        "totalOpens", tuple.getT1(),
                        "totalClicks", tuple.getT2(),
                        "uniqueOpeners", tuple.getT3(),
                        "uniqueClickers", tuple.getT4(),
                        "totalConfirmedSubscribers", tuple.getT5(),
                        "topClickedLinks", tuple.getT6(),
                        "openRate", tuple.getT5() > 0
                                ? Math.round(tuple.getT3() * 100.0 / tuple.getT5() * 10) / 10.0
                                : 0.0,
                        "clickRate", tuple.getT5() > 0
                                ? Math.round(tuple.getT4() * 100.0 / tuple.getT5() * 10) / 10.0
                                : 0.0
                ));
    }

    private Mono<Long> countEvents(String eventType, LocalDateTime since) {
        return databaseClient.sql(
                "SELECT COUNT(*) AS cnt FROM newsletter_events WHERE event_type = :type AND created_at >= :since")
                .bind("type", eventType)
                .bind("since", since)
                .map((row, meta) -> row.get("cnt", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    private Mono<Long> countUniqueSubscribers(String eventType, LocalDateTime since) {
        return databaseClient.sql(
                "SELECT COUNT(DISTINCT subscriber_id) AS cnt FROM newsletter_events WHERE event_type = :type AND created_at >= :since")
                .bind("type", eventType)
                .bind("since", since)
                .map((row, meta) -> row.get("cnt", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    private Mono<List<Map<String, Object>>> getTopClickedLinks(LocalDateTime since, int limit) {
        return databaseClient.sql("""
                SELECT link_url, COUNT(*) AS cnt
                FROM newsletter_events
                WHERE event_type = 'CLICK' AND link_url IS NOT NULL AND created_at >= :since
                GROUP BY link_url
                ORDER BY cnt DESC
                LIMIT :limit
                """)
                .bind("since", since)
                .bind("limit", limit)
                .map((row, meta) -> Map.<String, Object>of(
                        "url", row.get("link_url", String.class),
                        "clicks", row.get("cnt", Long.class)))
                .all()
                .collectList();
    }
}
