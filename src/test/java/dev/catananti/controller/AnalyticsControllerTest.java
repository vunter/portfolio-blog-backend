package dev.catananti.controller;

import dev.catananti.dto.AnalyticsEventRequest;
import dev.catananti.service.AnalyticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private AnalyticsController controller;

    @Nested
    @DisplayName("POST /api/v1/analytics/event")
    class TrackEvent {

        @Test
        @DisplayName("Should track analytics event")
        void shouldTrackEvent() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .articleId(1001L)
                    .eventType("VIEW")
                    .referrer("https://google.com")
                    .metadata(Map.of("source", "organic"))
                    .build();

            ServerHttpRequest httpRequest = mock(ServerHttpRequest.class);

            when(analyticsService.trackEvent(request, httpRequest))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(analyticsService).trackEvent(request, httpRequest);
        }
    }

    @Nested
    @DisplayName("POST /api/v1/analytics/view/{slug}")
    class TrackView {

        @Test
        @DisplayName("Should track article view")
        void shouldTrackArticleView() {
            ServerHttpRequest httpRequest = mock(ServerHttpRequest.class);

            when(analyticsService.trackArticleView("spring-boot-guide", httpRequest))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.trackView("spring-boot-guide", httpRequest))
                    .verifyComplete();

            verify(analyticsService).trackArticleView("spring-boot-guide", httpRequest);
        }
    }
}
