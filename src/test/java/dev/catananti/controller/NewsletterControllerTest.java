package dev.catananti.controller;

import dev.catananti.dto.SubscribeRequest;
import dev.catananti.service.NewsletterService;
import dev.catananti.service.RecaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NewsletterController using Mockito.
 */
@ExtendWith(MockitoExtension.class)
class NewsletterControllerTest {

    @Mock
    private NewsletterService newsletterService;

    @Mock
    private RecaptchaService recaptchaService;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private NewsletterController newsletterController;

    @BeforeEach
    void setUp() {
        lenient().when(recaptchaService.verify(any(), any())).thenReturn(Mono.empty());
        // MessageSource returns the default message (3rd arg = key itself)
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    @Nested
    @DisplayName("POST /api/v1/newsletter/subscribe")
    class Subscribe {

        @Test
        @DisplayName("Should return success on valid subscription")
        void shouldReturnSuccess_WhenSubscriptionValid() {
            // Given
            SubscribeRequest request = SubscribeRequest.builder()
                    .email("user@example.com")
                    .name("Test User")
                    .build();

            when(newsletterService.subscribe(any(SubscribeRequest.class)))
                    .thenReturn(Mono.just(Map.of("message", "Please check your email to confirm subscription")));

            // When & Then
            StepVerifier.create(newsletterController.subscribe(request))
                    .assertNext(response -> {
                        assertThat(response).containsKey("message");
                        assertThat(response.get("message")).isNotEmpty();
                    })
                    .verifyComplete();

            verify(newsletterService).subscribe(any(SubscribeRequest.class));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/newsletter/confirm")
    class Confirm {

        @Test
        @DisplayName("Should confirm subscription with valid token")
        void shouldConfirm_WhenTokenValid() {
            // Given
            when(newsletterService.confirmSubscription("valid-token"))
                    .thenReturn(Mono.just(Map.of("message", "Subscription confirmed")));

            // When & Then
            StepVerifier.create(newsletterController.confirmSubscription("valid-token"))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("Subscription confirmed");
                    })
                    .verifyComplete();

            verify(newsletterService).confirmSubscription("valid-token");
        }
    }

    @Nested
    @DisplayName("POST /api/v1/newsletter/unsubscribe")
    class Unsubscribe {

        @Test
        @DisplayName("Should return generic success to prevent email enumeration")
        void shouldReturnGenericSuccess() {
            // Given
            when(newsletterService.unsubscribe("user@example.com"))
                    .thenReturn(Mono.just(Map.of("message", "success.generic_unsubscribe")));

            // When & Then
            StepVerifier.create(newsletterController.unsubscribe("user@example.com"))
                    .assertNext(response -> {
                        assertThat(response).containsKey("message");
                        assertThat(response.get("message")).isEqualTo("success.generic_unsubscribe");
                    })
                    .verifyComplete();

            verify(newsletterService).unsubscribe("user@example.com");
        }

        @Test
        @DisplayName("Should still return success when email not found")
        void shouldReturnSuccess_WhenEmailNotFound() {
            // Given
            when(newsletterService.unsubscribe("nonexistent@example.com"))
                    .thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(newsletterController.unsubscribe("nonexistent@example.com"))
                    .assertNext(response -> {
                        assertThat(response).containsKey("message");
                        assertThat(response.get("message")).isEqualTo("success.generic_unsubscribe");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/newsletter/one-click-unsubscribe (RFC 8058)")
    class OneClickUnsubscribe {

        private org.springframework.test.web.reactive.server.WebTestClient webTestClient;

        @BeforeEach
        void setUpClient() {
            NewsletterController controller =
                    new NewsletterController(newsletterService, recaptchaService, messageSource);
            webTestClient = org.springframework.test.web.reactive.server.WebTestClient
                    .bindToController(controller)
                    .controllerAdvice(new dev.catananti.exception.GlobalExceptionHandler(messageSource))
                    .configureClient().build();
        }

        @Test
        @DisplayName("Should unsubscribe on the provider's one-click POST (form body ignored)")
        void shouldUnsubscribe_OnOneClickPost() {
            when(newsletterService.unsubscribeByToken("valid-unsub-token"))
                    .thenReturn(Mono.just(Map.of("message", "success.newsletter_unsubscribed")));

            webTestClient.post()
                    .uri("/api/v1/newsletter/one-click-unsubscribe?token=valid-unsub-token")
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("List-Unsubscribe=One-Click")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.message").isEqualTo("success.newsletter_unsubscribed");

            verify(newsletterService).unsubscribeByToken("valid-unsub-token");
        }

        @Test
        @DisplayName("Should return 404 for an unknown token")
        void shouldReturn404_WhenTokenUnknown() {
            when(newsletterService.unsubscribeByToken("unknown-unsub-token"))
                    .thenReturn(Mono.error(new dev.catananti.exception.ResourceNotFoundException(
                            "Subscription", "token", "unknown-unsub-token")));

            webTestClient.post()
                    .uri("/api/v1/newsletter/one-click-unsubscribe?token=unknown-unsub-token")
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("List-Unsubscribe=One-Click")
                    .exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        @DisplayName("Should stay 200 on an idempotent repeat (already unsubscribed)")
        void shouldReturn200_OnIdempotentRepeat() {
            // unsubscribeByToken answers with the same success message when the
            // subscriber is already UNSUBSCRIBED — the endpoint must stay 200.
            when(newsletterService.unsubscribeByToken("valid-unsub-token"))
                    .thenReturn(Mono.just(Map.of("message", "success.newsletter_unsubscribed")));

            for (int i = 0; i < 2; i++) {
                webTestClient.post()
                        .uri("/api/v1/newsletter/one-click-unsubscribe?token=valid-unsub-token")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .bodyValue("List-Unsubscribe=One-Click")
                        .exchange()
                        .expectStatus().isOk();
            }

            verify(newsletterService, times(2)).unsubscribeByToken("valid-unsub-token");
        }
    }

    @Nested
    @DisplayName("GET /api/v1/newsletter/unsubscribe")
    class UnsubscribeByToken {

        @Test
        @DisplayName("Should unsubscribe with valid token")
        void shouldUnsubscribe_WhenTokenValid() {
            // Given
            when(newsletterService.unsubscribeByToken("unsub-token"))
                    .thenReturn(Mono.just(Map.of("message", "Successfully unsubscribed")));

            // When & Then
            StepVerifier.create(newsletterController.unsubscribeByToken("unsub-token"))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("Successfully unsubscribed");
                    })
                    .verifyComplete();

            verify(newsletterService).unsubscribeByToken("unsub-token");
        }
    }
}
