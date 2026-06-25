package dev.catananti.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.catananti.dto.AnalyticsEventRequest;
import dev.catananti.dto.AnalyticsSummary;
import dev.catananti.entity.AnalyticsEvent;
import dev.catananti.entity.Article;
import dev.catananti.repository.AnalyticsRepository;
import dev.catananti.repository.ArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock private AnalyticsRepository analyticsRepository;
    @Mock private ArticleRepository articleRepository;
    @Mock private IdService idService;
    @Mock private DatabaseClient databaseClient;
    @Mock private GeoIPService geoIPService;
    @Mock private DatabaseClient.GenericExecuteSpec executeSpec;
    @SuppressWarnings("rawtypes")
    @Mock private RowsFetchSpec rowsFetchSpec;

    private AnalyticsService analyticsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        analyticsService = new AnalyticsService(analyticsRepository, articleRepository, objectMapper, idService, databaseClient, geoIPService, new dev.catananti.scheduler.SchedulerLock(null));

        // Lenient stubs for DatabaseClient chain used by trackEvent and analytics queries
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.bindNull(anyString(), any(Class.class))).thenReturn(executeSpec);
        lenient().when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
        lenient().when(executeSpec.then()).thenReturn(Mono.empty());
        lenient().when(rowsFetchSpec.all()).thenReturn(Flux.empty());
        lenient().when(rowsFetchSpec.one()).thenReturn(Mono.empty());

        // GeoIP service returns empty Mono by default (no country resolved)
        lenient().when(geoIPService.getCountryCode(anyString())).thenReturn(Mono.empty());
    }

    @Nested
    @DisplayName("trackEvent")
    class TrackEvent {

        @Test
        @DisplayName("Should track event without articleId")
        void shouldTrackEventWithoutArticleId() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .eventType("VIEW")
                    .referrer("https://google.com")
                    .build();

            MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("X-Forwarded-For", "203.0.113.50")
                    .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 443))
                    .build();

            when(idService.nextId()).thenReturn(1001L);

            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(executeSpec).bind("eventType", "VIEW");
            verify(executeSpec).bind("userIp", "203.0.113.0"); // SEC-08: anonymized
            verify(executeSpec).bind("userAgent", "Mozilla/5.0");
            verify(executeSpec).bind("referrer", "https://google.com");
        }

        @Test
        @DisplayName("Should track event with valid articleId")
        void shouldTrackEventWithValidArticleId() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .articleId(42L)
                    .eventType("like")
                    .build();

            MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/")
                    .header("X-Forwarded-For", "10.0.0.1")
                    .build();

            when(articleRepository.existsById(42L)).thenReturn(Mono.just(true));
            when(idService.nextId()).thenReturn(1002L);

            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(executeSpec).bind("eventType", "LIKE");
            verify(executeSpec).bind("articleId", 42L);
        }

        @Test
        @DisplayName("Should reject event with invalid articleId")
        void shouldRejectInvalidArticleId() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .articleId(999L)
                    .eventType("VIEW")
                    .build();

            MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/").build();

            when(articleRepository.existsById(999L)).thenReturn(Mono.just(false));

            // trackEvent now surfaces "article not found" as an error rather than
            // silently dropping the event — see AnalyticsService.trackEvent.
            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && "error.article_not_found".equals(e.getMessage()))
                    .verify();

            verify(executeSpec, never()).then();
        }

        @Test
        @DisplayName("Should serialize metadata as JSON")
        void shouldSerializeMetadata() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .eventType("SCROLL_DEPTH")
                    .metadata(Map.of("depth", "75", "duration", "30"))
                    .build();

            MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/").build();

            when(idService.nextId()).thenReturn(1003L);

            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(executeSpec).bind(eq("metadata"), argThat(val -> val != null && val.toString().contains("depth")));
        }
    }

    @Nested
    @DisplayName("trackArticleView")
    class TrackArticleView {

        @Test
        @DisplayName("Should track view for existing article by slug")
        void shouldTrackViewBySlug() {
            Article article = Article.builder()
                    .id(42L)
                    .slug("spring-boot-tips")
                    .title("Spring Boot Tips")
                    .build();

            MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/")
                    .header("Referer", "https://google.com/search")
                    .header("X-Forwarded-For", "1.2.3.4")
                    .build();

            when(articleRepository.findBySlug("spring-boot-tips")).thenReturn(Mono.just(article));
            when(articleRepository.existsById(42L)).thenReturn(Mono.just(true));
            when(idService.nextId()).thenReturn(1004L);

            StepVerifier.create(analyticsService.trackArticleView("spring-boot-tips", httpRequest))
                    .verifyComplete();

            verify(executeSpec).bind("eventType", "VIEW");
            verify(executeSpec).bind("articleId", 42L);
        }

        @Test
        @DisplayName("Should silently ignore non-existent slug")
        void shouldIgnoreNonExistentSlug() {
            MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/").build();

            when(articleRepository.findBySlug("nonexistent")).thenReturn(Mono.empty());

            StepVerifier.create(analyticsService.trackArticleView("nonexistent", httpRequest))
                    .verifyComplete();

            verify(executeSpec, never()).then();
        }
    }

    @Nested
    @DisplayName("getAnalyticsSummary")
    class GetAnalyticsSummary {

        @Test
        @DisplayName("Should return summary with all metrics")
        @SuppressWarnings("unchecked")
        void shouldReturnSummary() {
            when(analyticsRepository.countByEventTypeSince(eq("VIEW"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(150L));
            when(analyticsRepository.countByEventTypeSince(eq("LIKE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(25L));
            when(analyticsRepository.countByEventTypeSince(eq("SHARE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(10L));

            // BUG-12: getDailyViews, getTopArticles, getTopReferrers now use DatabaseClient
            when(databaseClient.sql(anyString())).thenReturn(executeSpec);
            when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
            when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
            when(rowsFetchSpec.all()).thenReturn(Flux.empty());
            when(rowsFetchSpec.one()).thenReturn(Mono.empty());

            StepVerifier.create(analyticsService.getAnalyticsSummary(30))
                    .assertNext(summary -> {
                        assertThat(summary.getTotalViews()).isEqualTo(150L);
                        assertThat(summary.getTotalLikes()).isEqualTo(25L);
                        assertThat(summary.getTotalShares()).isEqualTo(10L);
                        assertThat(summary.getDailyViews()).isEmpty();
                        assertThat(summary.getTopArticles()).isEmpty();
                        assertThat(summary.getTopReferrers()).isEmpty();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getArticleViewCount")
    class GetArticleViewCount {

        @Test
        @DisplayName("Should return view count for article")
        void shouldReturnViewCount() {
            when(analyticsRepository.countByArticleIdAndEventType(42L, "VIEW"))
                    .thenReturn(Mono.just(250L));

            StepVerifier.create(analyticsService.getArticleViewCount(42L))
                    .assertNext(count -> assertThat(count).isEqualTo(250L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return zero for article with no views")
        void shouldReturnZeroViews() {
            when(analyticsRepository.countByArticleIdAndEventType(99L, "VIEW"))
                    .thenReturn(Mono.just(0L));

            StepVerifier.create(analyticsService.getArticleViewCount(99L))
                    .assertNext(count -> assertThat(count).isZero())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getAnalyticsSummary - windowed counts (M11: no all-time fallback)")
    class GetAnalyticsSummaryWindowed {

        @Test
        @DisplayName("Should return windowed zero when VIEW count is zero (no all-time fallback)")
        @SuppressWarnings("unchecked")
        void shouldReturnZeroForViewCount() {
            when(analyticsRepository.countByEventTypeSince(eq("VIEW"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(analyticsRepository.countByEventTypeSince(eq("LIKE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(10L));
            when(analyticsRepository.countByEventTypeSince(eq("SHARE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(5L));

            when(databaseClient.sql(anyString())).thenReturn(executeSpec);
            when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
            when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
            when(rowsFetchSpec.all()).thenReturn(Flux.empty());
            when(rowsFetchSpec.one()).thenReturn(Mono.empty());

            StepVerifier.create(analyticsService.getAnalyticsSummary(30))
                    .assertNext(summary -> {
                        assertThat(summary.getTotalViews()).isZero();
                        assertThat(summary.getTotalLikes()).isEqualTo(10L);
                        assertThat(summary.getTotalShares()).isEqualTo(5L);
                    })
                    .verifyComplete();

            verify(articleRepository, never()).sumViewsCount();
        }

        @Test
        @DisplayName("Should return windowed zero when LIKE count is zero (no all-time fallback)")
        @SuppressWarnings("unchecked")
        void shouldReturnZeroForLikeCount() {
            when(analyticsRepository.countByEventTypeSince(eq("VIEW"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(100L));
            when(analyticsRepository.countByEventTypeSince(eq("LIKE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(analyticsRepository.countByEventTypeSince(eq("SHARE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(3L));

            when(databaseClient.sql(anyString())).thenReturn(executeSpec);
            when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
            when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
            when(rowsFetchSpec.all()).thenReturn(Flux.empty());
            when(rowsFetchSpec.one()).thenReturn(Mono.empty());

            StepVerifier.create(analyticsService.getAnalyticsSummary(30))
                    .assertNext(summary -> {
                        assertThat(summary.getTotalViews()).isEqualTo(100L);
                        assertThat(summary.getTotalLikes()).isZero();
                        assertThat(summary.getTotalShares()).isEqualTo(3L);
                    })
                    .verifyComplete();

            verify(articleRepository, never()).sumLikesCount();
        }

        @Test
        @DisplayName("Should return windowed zero for views and likes when both are zero")
        @SuppressWarnings("unchecked")
        void shouldReturnZeroForViewsAndLikes() {
            when(analyticsRepository.countByEventTypeSince(eq("VIEW"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(analyticsRepository.countByEventTypeSince(eq("LIKE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(analyticsRepository.countByEventTypeSince(eq("SHARE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));

            when(databaseClient.sql(anyString())).thenReturn(executeSpec);
            when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
            when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
            when(rowsFetchSpec.all()).thenReturn(Flux.empty());
            when(rowsFetchSpec.one()).thenReturn(Mono.empty());

            StepVerifier.create(analyticsService.getAnalyticsSummary(7))
                    .assertNext(summary -> {
                        assertThat(summary.getTotalViews()).isZero();
                        assertThat(summary.getTotalLikes()).isZero();
                        assertThat(summary.getTotalShares()).isZero();
                    })
                    .verifyComplete();

            verify(articleRepository, never()).sumViewsCount();
            verify(articleRepository, never()).sumLikesCount();
        }
    }

    @Nested
    @DisplayName("getAnalyticsSummaryByAuthor")
    class GetAnalyticsSummaryByAuthor {

        private static final long AUTHOR_ID = 7L;

        @Test
        @DisplayName("Should scope summary to the author and return expected shape")
        @SuppressWarnings("unchecked")
        void shouldReturnAuthorScopedSummary() {
            when(analyticsRepository.countByAuthorIdAndEventTypeSince(eq(AUTHOR_ID), eq("VIEW"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(120L));
            when(analyticsRepository.countByAuthorIdAndEventTypeSince(eq(AUTHOR_ID), eq("LIKE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(18L));
            when(analyticsRepository.countByAuthorIdAndEventTypeSince(eq(AUTHOR_ID), eq("SHARE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(4L));

            when(databaseClient.sql(anyString())).thenReturn(executeSpec);
            when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
            when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
            when(rowsFetchSpec.all()).thenReturn(Flux.empty());
            when(rowsFetchSpec.one()).thenReturn(Mono.empty());

            StepVerifier.create(analyticsService.getAnalyticsSummaryByAuthor(30, AUTHOR_ID))
                    .assertNext(summary -> {
                        assertThat(summary.getTotalViews()).isEqualTo(120L);
                        assertThat(summary.getTotalLikes()).isEqualTo(18L);
                        assertThat(summary.getTotalShares()).isEqualTo(4L);
                        assertThat(summary.getDailyViews()).isEmpty();
                        assertThat(summary.getTopArticles()).isEmpty();
                        assertThat(summary.getTopReferrers()).isEmpty();
                        assertThat(summary.getUniqueVisitors()).isZero();
                    })
                    .verifyComplete();

            // Author-scoped repository counters are used (never the global ones)
            verify(analyticsRepository).countByAuthorIdAndEventTypeSince(eq(AUTHOR_ID), eq("VIEW"), any(LocalDateTime.class));
            verify(analyticsRepository, never()).countByEventTypeSince(anyString(), any(LocalDateTime.class));
            // The author_id predicate is bound into the underlying analytics queries
            verify(executeSpec, atLeastOnce()).bind("authorId", AUTHOR_ID);
        }

        @Test
        @DisplayName("Should return windowed zero for author when VIEW count is zero (M11: no all-time fallback)")
        @SuppressWarnings("unchecked")
        void shouldReturnZeroForAuthorViewCounts() {
            when(analyticsRepository.countByAuthorIdAndEventTypeSince(eq(AUTHOR_ID), eq("VIEW"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(analyticsRepository.countByAuthorIdAndEventTypeSince(eq(AUTHOR_ID), eq("LIKE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(analyticsRepository.countByAuthorIdAndEventTypeSince(eq(AUTHOR_ID), eq("SHARE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));

            when(databaseClient.sql(anyString())).thenReturn(executeSpec);
            when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
            when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
            when(rowsFetchSpec.all()).thenReturn(Flux.empty());
            when(rowsFetchSpec.one()).thenReturn(Mono.empty());

            StepVerifier.create(analyticsService.getAnalyticsSummaryByAuthor(7, AUTHOR_ID))
                    .assertNext(summary -> {
                        assertThat(summary.getTotalViews()).isZero();
                        assertThat(summary.getTotalLikes()).isZero();
                        assertThat(summary.getTotalShares()).isZero();
                    })
                    .verifyComplete();

            verify(articleRepository, never()).sumViewsCountByAuthorId(AUTHOR_ID);
            verify(articleRepository, never()).sumViewsCount();
            verify(articleRepository, never()).sumLikesCount();
        }
    }

    @Nested
    @DisplayName("getAnalyticsComparisonByAuthor")
    class GetAnalyticsComparisonByAuthor {

        private static final long AUTHOR_ID = 9L;

        @Test
        @DisplayName("Should scope comparison to the author and bind author_id on period queries")
        @SuppressWarnings("unchecked")
        void shouldReturnAuthorScopedComparison() {
            when(analyticsRepository.countByAuthorIdAndEventTypeSince(eq(AUTHOR_ID), eq("VIEW"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(50L));
            when(analyticsRepository.countByAuthorIdAndEventTypeSince(eq(AUTHOR_ID), eq("LIKE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(8L));
            when(analyticsRepository.countByAuthorIdAndEventTypeSince(eq(AUTHOR_ID), eq("SHARE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(2L));

            // Previous-period counts go through DatabaseClient; one() -> 0L via defaultIfEmpty
            when(databaseClient.sql(anyString())).thenReturn(executeSpec);
            when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
            when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
            when(rowsFetchSpec.one()).thenReturn(Mono.empty());

            StepVerifier.create(analyticsService.getAnalyticsComparisonByAuthor(30, AUTHOR_ID))
                    .assertNext(comparison -> {
                        assertThat(comparison.getCurrentViews()).isEqualTo(50L);
                        assertThat(comparison.getCurrentLikes()).isEqualTo(8L);
                        assertThat(comparison.getCurrentShares()).isEqualTo(2L);
                        assertThat(comparison.getPreviousViews()).isZero();
                        assertThat(comparison.getPreviousLikes()).isZero();
                        assertThat(comparison.getPreviousShares()).isZero();
                    })
                    .verifyComplete();

            // Author-scoped "since" counters used, global ones untouched
            verify(analyticsRepository, never()).countByEventTypeSince(anyString(), any(LocalDateTime.class));
            // The previous-period between-queries bind the author_id predicate
            verify(executeSpec, atLeastOnce()).bind("authorId", AUTHOR_ID);
        }
    }

    @Nested
    @DisplayName("trackEvent - edge cases")
    class TrackEventEdgeCases {

        @Test
        @DisplayName("Should track event with null metadata")
        void shouldTrackEventWithNullMetadata() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .eventType("CLICK")
                    .metadata(null)
                    .build();

            MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/")
                    .header("User-Agent", "TestBot/1.0")
                    .build();

            when(idService.nextId()).thenReturn(2001L);

            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(executeSpec).bind("eventType", "CLICK");
            verify(executeSpec).bindNull("metadata", String.class);
        }

        @Test
        @DisplayName("Should track event with null referrer header")
        void shouldTrackEventWithNullReferrer() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .eventType("SHARE")
                    .referrer(null)
                    .build();

            MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/").build();

            when(idService.nextId()).thenReturn(2002L);

            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(executeSpec).bind("eventType", "SHARE");
            verify(executeSpec).bindNull("referrer", String.class);
        }
    }
}
