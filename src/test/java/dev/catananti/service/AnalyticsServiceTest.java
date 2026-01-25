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
    @Mock private DatabaseClient.GenericExecuteSpec executeSpec;
    @SuppressWarnings("rawtypes")
    @Mock private RowsFetchSpec rowsFetchSpec;

    private AnalyticsService analyticsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        analyticsService = new AnalyticsService(analyticsRepository, articleRepository, objectMapper, idService, databaseClient);
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
            when(analyticsRepository.save(any(AnalyticsEvent.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(analyticsRepository).save(argThat(event -> {
                assertThat(event.getEventType()).isEqualTo("VIEW");
                assertThat(event.getUserIp()).isEqualTo("203.0.113.0"); // SEC-08: anonymized
                assertThat(event.getUserAgent()).isEqualTo("Mozilla/5.0");
                assertThat(event.getReferrer()).isEqualTo("https://google.com");
                return true;
            }));
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
            when(analyticsRepository.save(any(AnalyticsEvent.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(analyticsRepository).save(argThat(event -> {
                assertThat(event.getEventType()).isEqualTo("LIKE");
                assertThat(event.getArticleId()).isEqualTo(42L);
                return true;
            }));
        }

        @Test
        @DisplayName("Should ignore event with invalid articleId")
        void shouldIgnoreInvalidArticleId() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .articleId(999L)
                    .eventType("VIEW")
                    .build();

            MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/").build();

            when(articleRepository.existsById(999L)).thenReturn(Mono.just(false));

            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(analyticsRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should serialize metadata as JSON")
        void shouldSerializeMetadata() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .eventType("SCROLL_DEPTH")
                    .metadata(Map.of("depth", 75, "duration", 30))
                    .build();

            MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/").build();

            when(idService.nextId()).thenReturn(1003L);
            when(analyticsRepository.save(any(AnalyticsEvent.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(analyticsRepository).save(argThat(event -> {
                assertThat(event.getMetadata()).isNotNull();
                assertThat(event.getMetadata()).contains("depth");
                return true;
            }));
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
            when(analyticsRepository.save(any(AnalyticsEvent.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(analyticsService.trackArticleView("spring-boot-tips", httpRequest))
                    .verifyComplete();

            verify(analyticsRepository).save(argThat(event -> {
                assertThat(event.getEventType()).isEqualTo("VIEW");
                assertThat(event.getArticleId()).isEqualTo(42L);
                return true;
            }));
        }

        @Test
        @DisplayName("Should silently ignore non-existent slug")
        void shouldIgnoreNonExistentSlug() {
            MockServerHttpRequest httpRequest = MockServerHttpRequest.get("/").build();

            when(articleRepository.findBySlug("nonexistent")).thenReturn(Mono.empty());

            StepVerifier.create(analyticsService.trackArticleView("nonexistent", httpRequest))
                    .verifyComplete();

            verify(analyticsRepository, never()).save(any());
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
            when(analyticsRepository.save(any(AnalyticsEvent.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(analyticsRepository).save(argThat(event -> {
                assertThat(event.getEventType()).isEqualTo("CLICK");
                assertThat(event.getMetadata()).isNull();
                return true;
            }));
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
            when(analyticsRepository.save(any(AnalyticsEvent.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(analyticsService.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(analyticsRepository).save(argThat(event -> {
                assertThat(event.getEventType()).isEqualTo("SHARE");
                assertThat(event.getReferrer()).isNull();
                return true;
            }));
        }
    }
}
