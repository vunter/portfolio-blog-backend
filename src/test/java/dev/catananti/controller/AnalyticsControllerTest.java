package dev.catananti.controller;

import dev.catananti.dto.AnalyticsEventRequest;
import dev.catananti.service.AnalyticsProofOfWorkService;
import dev.catananti.service.AnalyticsService;
import dev.catananti.service.AnalyticsTokenService;
import dev.catananti.service.RecaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyticsController")
class AnalyticsControllerTest {

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private AnalyticsProofOfWorkService powService;

    @Mock
    private AnalyticsTokenService tokenService;

    @Mock
    private RecaptchaService recaptchaService;

    private AnalyticsController controller;

    @BeforeEach
    void setUp() {
        controller = new AnalyticsController(analyticsService, powService, tokenService, recaptchaService);
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/analytics/event
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/v1/analytics/event")
    class TrackEvent {

        @Test
        @DisplayName("should track analytics event when all security checks pass")
        void shouldTrackEvent() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .articleId(1001L)
                    .eventType("VIEW")
                    .referrer("https://google.com")
                    .metadata(Map.of("source", "organic"))
                    .challengeId("550e8400-e29b-41d4-a716-446655440000")
                    .solution("93721")
                    .recaptchaToken("recaptcha-token-value")
                    .build();

            ServerHttpRequest httpRequest = mock(ServerHttpRequest.class);
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Analytics-Consent", "granted");
            headers.set("X-Analytics-Token", "valid-token-uuid");
            when(httpRequest.getHeaders()).thenReturn(headers);

            // Chain order: PoW -> reCAPTCHA -> Token -> Process
            when(powService.verifySolution(anyString(), anyString())).thenReturn(Mono.empty());
            when(recaptchaService.verify(anyString(), anyString())).thenReturn(Mono.empty());
            when(tokenService.validate(anyString())).thenReturn(Mono.empty());
            when(analyticsService.trackEvent(any(), any())).thenReturn(Mono.empty());

            StepVerifier.create(controller.trackEvent(request, httpRequest))
                    .verifyComplete();

            verify(powService).verifySolution(request.getChallengeId(), request.getSolution());
            verify(tokenService).validate("valid-token-uuid");
            verify(analyticsService).trackEvent(request, httpRequest);
        }

        @Test
        @DisplayName("should return empty without processing when consent header is missing")
        void withoutConsent_shouldReturnEmpty() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .articleId(1001L)
                    .eventType("VIEW")
                    .challengeId("550e8400-e29b-41d4-a716-446655440000")
                    .solution("93721")
                    .build();

            ServerHttpRequest httpRequest = mock(ServerHttpRequest.class);
            HttpHeaders headers = new HttpHeaders();
            // No X-Analytics-Consent header
            when(httpRequest.getHeaders()).thenReturn(headers);

            StepVerifier.create(controller.trackEvent(request, httpRequest))
                    .verifyComplete();

            verifyNoInteractions(powService);
            verifyNoInteractions(tokenService);
            verifyNoInteractions(recaptchaService);
            verifyNoInteractions(analyticsService);
        }

        @Test
        @DisplayName("should skip reCAPTCHA verification when recaptchaToken is null")
        void shouldSkipRecaptchaWhenTokenNull() {
            AnalyticsEventRequest request = AnalyticsEventRequest.builder()
                    .articleId(1001L)
                    .eventType("VIEW")
                    .challengeId("550e8400-e29b-41d4-a716-446655440000")
                    .solution("93721")
                    .recaptchaToken(null)
                    .build();

            ServerHttpRequest httpRequest = mock(ServerHttpRequest.class);
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Analytics-Consent", "granted");
            headers.set("X-Analytics-Token", "valid-token-uuid");
            when(httpRequest.getHeaders()).thenReturn(headers);

            when(powService.verifySolution(anyString(), anyString())).thenReturn(Mono.empty());
            when(tokenService.validate(anyString())).thenReturn(Mono.empty());
            when(analyticsService.trackEvent(any(), any())).thenReturn(Mono.empty());

            StepVerifier.create(controller.trackEvent(request, httpRequest))
                    .verifyComplete();

            verifyNoInteractions(recaptchaService);
        }
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/analytics/view/{slug}
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/v1/analytics/view/{slug}")
    class TrackView {

        @Test
        @DisplayName("should track article view when consent is granted")
        void withConsent_shouldTrackView() {
            ServerHttpRequest httpRequest = mock(ServerHttpRequest.class);
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Analytics-Consent", "granted");
            when(httpRequest.getHeaders()).thenReturn(headers);

            when(analyticsService.trackArticleView("spring-boot-guide", httpRequest))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.trackView("spring-boot-guide", httpRequest))
                    .verifyComplete();

            verify(analyticsService).trackArticleView("spring-boot-guide", httpRequest);
        }

        @Test
        @DisplayName("should return empty without tracking when consent header is missing")
        void withoutConsent_shouldReturnEmpty() {
            ServerHttpRequest httpRequest = mock(ServerHttpRequest.class);
            HttpHeaders headers = new HttpHeaders();
            // No X-Analytics-Consent header
            when(httpRequest.getHeaders()).thenReturn(headers);

            StepVerifier.create(controller.trackView("spring-boot-guide", httpRequest))
                    .verifyComplete();

            verifyNoInteractions(analyticsService);
        }

        @Test
        @DisplayName("should return empty when consent header is not 'granted'")
        void withDeniedConsent_shouldReturnEmpty() {
            ServerHttpRequest httpRequest = mock(ServerHttpRequest.class);
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Analytics-Consent", "denied");
            when(httpRequest.getHeaders()).thenReturn(headers);

            StepVerifier.create(controller.trackView("spring-boot-guide", httpRequest))
                    .verifyComplete();

            verifyNoInteractions(analyticsService);
        }
    }
}
