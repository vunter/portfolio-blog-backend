package dev.catananti.controller;

import dev.catananti.service.NewsletterTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NewsletterTrackingController Tests")
class NewsletterTrackingControllerTest {

    @Mock
    private NewsletterTrackingService trackingService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        NewsletterTrackingController controller = new NewsletterTrackingController(trackingService);
        webTestClient = WebTestClient.bindToController(controller)
                .configureClient().build();
        // AUD18-JM8: the redirect allowlist is fail-closed now — configure it explicitly.
        // Must be set AFTER bindToController: its context refresh re-injects the @Value
        // default ("") over any earlier reflection-set value.
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "allowedOrigins", "https://catananti.dev");
    }

    @Nested
    @DisplayName("GET /api/v1/newsletter/track/open/{token}")
    class TrackOpen {

        @Test
        @DisplayName("Should return tracking pixel GIF on successful open tracking")
        void shouldReturnTrackingPixel() {
            when(trackingService.recordOpen(eq("valid-token"), any())).thenReturn(Mono.empty());

            webTestClient
                    .get().uri("/api/v1/newsletter/track/open/valid-token")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType(MediaType.IMAGE_GIF)
                    .expectBody(byte[].class).value(body -> {
                        assertThat(body).isNotEmpty();
                        // GIF89a magic bytes
                        assertThat(body[0]).isEqualTo((byte) 0x47); // 'G'
                        assertThat(body[1]).isEqualTo((byte) 0x49); // 'I'
                        assertThat(body[2]).isEqualTo((byte) 0x46); // 'F'
                    });

            verify(trackingService).recordOpen(eq("valid-token"), any());
        }

        @Test
        @DisplayName("Should still return tracking pixel even when tracking service errors")
        void shouldReturnPixelOnError() {
            when(trackingService.recordOpen(eq("error-token"), any()))
                    .thenReturn(Mono.error(new RuntimeException("Tracking failed")));

            webTestClient
                    .get().uri("/api/v1/newsletter/track/open/error-token")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType(MediaType.IMAGE_GIF);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/newsletter/track/click/{token}")
    class TrackClick {

        @Test
        @DisplayName("Should redirect to target URL after recording click")
        void shouldRedirectToTargetUrl() {
            when(trackingService.recordClick(eq("valid-token"), eq("https://catananti.dev/blog/post"), any()))
                    .thenReturn(Mono.empty());

            webTestClient
                    .get().uri("/api/v1/newsletter/track/click/valid-token?url=https://catananti.dev/blog/post")
                    .exchange()
                    .expectStatus().isFound()
                    .expectHeader().location("https://catananti.dev/blog/post");

            verify(trackingService).recordClick(eq("valid-token"), eq("https://catananti.dev/blog/post"), any());
        }

        @Test
        @DisplayName("Should return 400 for invalid redirect URL scheme")
        void shouldReturn400ForInvalidUrlScheme() {
            webTestClient
                    .get().uri("/api/v1/newsletter/track/click/valid-token?url=javascript:alert(1)")
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("Should return 400 for empty URL")
        void shouldReturn400ForEmptyUrl() {
            webTestClient
                    .get().uri("/api/v1/newsletter/track/click/valid-token?url=")
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("Should return 400 for a host outside the allowlist")
        void shouldReturn400ForDisallowedHost() {
            webTestClient
                    .get().uri("/api/v1/newsletter/track/click/valid-token?url=https://evil.example.com/phish")
                    .exchange()
                    .expectStatus().isBadRequest();

            verifyNoInteractions(trackingService);
        }

        @Test
        @DisplayName("AUD18-JM8: blank allowlist fails CLOSED — every redirect rejected")
        void blankAllowlist_ShouldRejectAllRedirects() {
            NewsletterTrackingController blankController = new NewsletterTrackingController(trackingService);
            WebTestClient blankClient = WebTestClient.bindToController(blankController)
                    .configureClient().build();
            org.springframework.test.util.ReflectionTestUtils.setField(
                    blankController, "allowedOrigins", "");

            blankClient
                    .get().uri("/api/v1/newsletter/track/click/valid-token?url=https://catananti.dev/blog/post")
                    .exchange()
                    .expectStatus().isBadRequest();

            verifyNoInteractions(trackingService);
        }
    }
}
