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
    @DisplayName("getAnalyticsSummary - fallback paths")
    class GetAnalyticsSummaryFallback {

        @Test
        @DisplayName("Should fallback to article table when VIEW count is zero")
        @SuppressWarnings("unchecked")
        void shouldFallbackForViewCount() {
            when(analyticsRepository.countByEventTypeSince(eq("VIEW"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(articleRepository.sumViewsCount()).thenReturn(Mono.just(500L));
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
                        assertThat(summary.getTotalViews()).isEqualTo(500L);
                        assertThat(summary.getTotalLikes()).isEqualTo(10L);
                        assertThat(summary.getTotalShares()).isEqualTo(5L);
                    })
                    .verifyComplete();

            verify(articleRepository).sumViewsCount();
        }

        @Test
        @DisplayName("Should fallback to article table when LIKE count is zero")
        @SuppressWarnings("unchecked")
        void shouldFallbackForLikeCount() {
            when(analyticsRepository.countByEventTypeSince(eq("VIEW"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(100L));
            when(analyticsRepository.countByEventTypeSince(eq("LIKE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(articleRepository.sumLikesCount()).thenReturn(Mono.just(200L));
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
                        assertThat(summary.getTotalLikes()).isEqualTo(200L);
                        assertThat(summary.getTotalShares()).isEqualTo(3L);
                    })
                    .verifyComplete();

            verify(articleRepository).sumLikesCount();
        }

        @Test
        @DisplayName("Should fallback both views and likes when both are zero")
        @SuppressWarnings("unchecked")
        void shouldFallbackBothViewsAndLikes() {
            when(analyticsRepository.countByEventTypeSince(eq("VIEW"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(articleRepository.sumViewsCount()).thenReturn(Mono.just(1000L));
            when(analyticsRepository.countByEventTypeSince(eq("LIKE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(articleRepository.sumLikesCount()).thenReturn(Mono.just(300L));
            when(analyticsRepository.countByEventTypeSince(eq("SHARE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));

            when(databaseClient.sql(anyString())).thenReturn(executeSpec);
            when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
            when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
            when(rowsFetchSpec.all()).thenReturn(Flux.empty());
            when(rowsFetchSpec.one()).thenReturn(Mono.empty());

            StepVerifier.create(analyticsService.getAnalyticsSummary(7))
                    .assertNext(summary -> {
                        assertThat(summary.getTotalViews()).isEqualTo(1000L);
                        assertThat(summary.getTotalLikes()).isEqualTo(300L);
                        assertThat(summary.getTotalShares()).isZero();
                    })
                    .verifyComplete();
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
