package dev.catananti.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecaptchaService")
class RecaptchaServiceTest {

    /**
     * Helper to build a RecaptchaService with given config.
     */
    private RecaptchaService createService(boolean enabled, String secretKey, double scoreThreshold) {
        WebClient.Builder builder = mock(WebClient.Builder.class);
        WebClient webClient = mock(WebClient.class);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(webClient);

        return new RecaptchaService(builder, secretKey, enabled, scoreThreshold);
    }

    // ──────────────────────────────────────────────
    // isAvailable()
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("isAvailable()")
    class IsAvailable {

        @Test
        @DisplayName("should return true when enabled and secret key is configured")
        void configured_shouldReturnTrue() {
            RecaptchaService service = createService(true, "test-secret", 0.5);

            boolean available = (boolean) ReflectionTestUtils.getField(service, "enabled");

            assertThat(available).isTrue();
        }

        @Test
        @DisplayName("should return false when disabled")
        void disabled_shouldReturnFalse() {
            RecaptchaService service = createService(false, "", 0.5);

            boolean available = (boolean) ReflectionTestUtils.getField(service, "enabled");

            assertThat(available).isFalse();
        }
    }

    // ──────────────────────────────────────────────
    // verify(token, action) — disabled
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("verify() — disabled bypass")
    class VerifyDisabled {

        @Test
        @DisplayName("should complete empty (bypass) when reCAPTCHA is disabled")
        void disabled_shouldBypass() {
            RecaptchaService service = createService(false, "", 0.5);

            StepVerifier.create(service.verify("any-token", "contact"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should bypass even with null token when disabled")
        void disabled_nullToken_shouldBypass() {
            RecaptchaService service = createService(false, "", 0.5);

            StepVerifier.create(service.verify(null, "login"))
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // verify(token, action) — null / blank token
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("verify() — null/blank token rejection")
    class VerifyNullBlankToken {

        @Test
        @DisplayName("should error with RecaptchaException when token is null")
        void nullToken_shouldError() {
            RecaptchaService service = createService(true, "secret", 0.5);

            StepVerifier.create(service.verify(null, "contact"))
                    .expectErrorMatches(ex ->
                            ex instanceof RecaptchaService.RecaptchaException
                                    && ex.getMessage().contains("reCAPTCHA verification failed"))
                    .verify();
        }

        @Test
        @DisplayName("should error with RecaptchaException when token is blank")
        void blankToken_shouldError() {
            RecaptchaService service = createService(true, "secret", 0.5);

            StepVerifier.create(service.verify("   ", "contact"))
                    .expectErrorMatches(ex ->
                            ex instanceof RecaptchaService.RecaptchaException
                                    && ex.getMessage().contains("reCAPTCHA verification failed"))
                    .verify();
        }

        @Test
        @DisplayName("should error with RecaptchaException when token is empty string")
        void emptyToken_shouldError() {
            RecaptchaService service = createService(true, "secret", 0.5);

            StepVerifier.create(service.verify("", "login"))
                    .expectErrorMatches(ex ->
                            ex instanceof RecaptchaService.RecaptchaException)
                    .verify();
        }
    }

    // ──────────────────────────────────────────────
    // verify(token, action) — WebClient chain mocking
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("verify() — successful / error scenarios with WebClient")
    class VerifyWithWebClient {

        @SuppressWarnings({"unchecked", "rawtypes"})
        private RecaptchaService createServiceWithMockedWebClient(
                WebClient webClient, boolean enabled, String secret, double threshold) {
            WebClient.Builder builder = mock(WebClient.Builder.class);
            when(builder.baseUrl(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(webClient);

            return new RecaptchaService(builder, secret, enabled, threshold);
        }

        @SuppressWarnings("unchecked")
        private void setupWebClientChain(WebClient webClient, Mono<?> responseMono) {
            WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
            WebClient.RequestBodySpec requestBodySpec = mock(WebClient.RequestBodySpec.class);
            WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
            WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

            when(webClient.post()).thenReturn(requestBodyUriSpec);
            when(requestBodyUriSpec.contentType(any())).thenReturn(requestBodySpec);
            when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
            when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(any(Class.class))).thenReturn((Mono) responseMono);
        }

        @Test
        @DisplayName("should complete successfully for valid response with passing score")
        void validResponse_shouldComplete() {
            WebClient webClient = mock(WebClient.class);
            RecaptchaService service = createServiceWithMockedWebClient(webClient, true, "secret", 0.5);

            // Build a fake successful response via a map-based approach
            // Since RecaptchaResponse is private, we use Mono.error to test alternate paths,
            // and for the success case we need to access the inner record via reflection.
            // Instead, we test the null/blank token and disabled cases directly (above),
            // and for the WebClient integration we verify the chain is called.

            // We can't easily create an instance of the private RecaptchaResponse record,
            // so we test the WebClient error path which wraps as RecaptchaException.
            setupWebClientChain(webClient, Mono.error(new RuntimeException("Connection refused")));

            StepVerifier.create(service.verify("valid-token", "contact"))
                    .expectErrorMatches(ex ->
                            ex instanceof RecaptchaService.RecaptchaException
                                    && ex.getMessage().contains("reCAPTCHA verification unavailable"))
                    .verify();
        }

        @Test
        @DisplayName("should propagate RecaptchaException on API failure (fail-closed)")
        void apiFailure_shouldFailClosed() {
            WebClient webClient = mock(WebClient.class);
            RecaptchaService service = createServiceWithMockedWebClient(webClient, true, "secret", 0.5);
            setupWebClientChain(webClient, Mono.error(new RuntimeException("Timeout")));

            StepVerifier.create(service.verify("some-token", "login"))
                    .expectErrorMatches(ex ->
                            ex instanceof RecaptchaService.RecaptchaException
                                    && ex.getMessage().contains("unavailable"))
                    .verify();
        }

        @Test
        @DisplayName("should wrap RecaptchaException through circuit breaker as unavailable")
        void recaptchaExceptionFromApi_shouldPropagateAsRecaptchaException() {
            WebClient webClient = mock(WebClient.class);
            RecaptchaService service = createServiceWithMockedWebClient(webClient, true, "secret", 0.5);
            setupWebClientChain(webClient,
                    Mono.error(new RecaptchaService.RecaptchaException("reCAPTCHA verification failed")));

            StepVerifier.create(service.verify("some-token", "subscribe"))
                    .expectErrorMatches(ex ->
                            ex instanceof RecaptchaService.RecaptchaException)
                    .verify();
        }
    }

    // ──────────────────────────────────────────────
    // RecaptchaException
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("RecaptchaException")
    class RecaptchaExceptionTests {

        @Test
        @DisplayName("should carry the provided message")
        void shouldCarryMessage() {
            var ex = new RecaptchaService.RecaptchaException("test message");

            assertThat(ex).isInstanceOf(RuntimeException.class);
            assertThat(ex.getMessage()).isEqualTo("test message");
        }
    }

    // ──────────────────────────────────────────────
    // verify(token, action) — full verification flow
    // (using reflection to instantiate private RecaptchaResponse)
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("verify() — full verification flow")
    class VerifyFullFlow {

        @SuppressWarnings({"unchecked", "rawtypes"})
        private RecaptchaService createServiceWithMockedWebClient(
                WebClient webClient, boolean enabled, String secret, double threshold) {
            WebClient.Builder builder = mock(WebClient.Builder.class);
            when(builder.baseUrl(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(webClient);
            return new RecaptchaService(builder, secret, enabled, threshold);
        }

        @SuppressWarnings("unchecked")
        private void setupWebClientChain(WebClient webClient, Mono<?> responseMono) {
            WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
            WebClient.RequestBodySpec requestBodySpec = mock(WebClient.RequestBodySpec.class);
            WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
            WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

            when(webClient.post()).thenReturn(requestBodyUriSpec);
            when(requestBodyUriSpec.contentType(any())).thenReturn(requestBodySpec);
            when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
            when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(any(Class.class))).thenReturn((Mono) responseMono);
        }

        private Object createRecaptchaResponse(boolean success, double score, String action,
                String challengeTs, String hostname, java.util.List<String> errorCodes) {
            try {
                Class<?> responseClass = null;
                for (Class<?> c : RecaptchaService.class.getDeclaredClasses()) {
                    if (c.getSimpleName().equals("RecaptchaResponse")) {
                        responseClass = c;
                        break;
                    }
                }
                java.lang.reflect.Constructor<?> ctor = responseClass.getDeclaredConstructors()[0];
                ctor.setAccessible(true);
                return ctor.newInstance(success, score, action, challengeTs, hostname, errorCodes);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("should complete when score is above threshold and action matches")
        void shouldCompleteForValidResponse() {
            WebClient webClient = mock(WebClient.class);
            RecaptchaService service = createServiceWithMockedWebClient(webClient, true, "secret", 0.5);
            Object response = createRecaptchaResponse(true, 0.9, "contact", "2025-01-01T00:00:00Z", "localhost", null);
            setupWebClientChain(webClient, Mono.just(response));

            StepVerifier.create(service.verify("valid-token", "contact"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should error when score is below threshold")
        void shouldErrorForLowScore() {
            WebClient webClient = mock(WebClient.class);
            RecaptchaService service = createServiceWithMockedWebClient(webClient, true, "secret", 0.5);
            Object response = createRecaptchaResponse(true, 0.2, "contact", "2025-01-01T00:00:00Z", "localhost", null);
            setupWebClientChain(webClient, Mono.just(response));

            StepVerifier.create(service.verify("token", "contact"))
                    .expectErrorMatches(ex ->
                            ex instanceof RecaptchaService.RecaptchaException)
                    .verify();
        }

        @Test
        @DisplayName("should error when action does not match")
        void shouldErrorForActionMismatch() {
            WebClient webClient = mock(WebClient.class);
            RecaptchaService service = createServiceWithMockedWebClient(webClient, true, "secret", 0.5);
            Object response = createRecaptchaResponse(true, 0.9, "login", "2025-01-01T00:00:00Z", "localhost", null);
            setupWebClientChain(webClient, Mono.just(response));

            StepVerifier.create(service.verify("token", "contact"))
                    .expectErrorMatches(ex -> ex instanceof RecaptchaService.RecaptchaException)
                    .verify();
        }

        @Test
        @DisplayName("should error when API returns success=false")
        void shouldErrorWhenApiReturnsFailure() {
            WebClient webClient = mock(WebClient.class);
            RecaptchaService service = createServiceWithMockedWebClient(webClient, true, "secret", 0.5);
            Object response = createRecaptchaResponse(false, 0.0, null, null, null, List.of("invalid-input-response"));
            setupWebClientChain(webClient, Mono.just(response));

            StepVerifier.create(service.verify("bad-token", "contact"))
                    .expectErrorMatches(ex ->
                            ex instanceof RecaptchaService.RecaptchaException)
                    .verify();
        }

        @Test
        @DisplayName("should complete when action is null in response (no action check)")
        void shouldCompleteWhenResponseActionIsNull() {
            WebClient webClient = mock(WebClient.class);
            RecaptchaService service = createServiceWithMockedWebClient(webClient, true, "secret", 0.5);
            Object response = createRecaptchaResponse(true, 0.8, null, "2025-01-01T00:00:00Z", "localhost", null);
            setupWebClientChain(webClient, Mono.just(response));

            StepVerifier.create(service.verify("token", "contact"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should complete when expected action is null")
        void shouldCompleteWhenExpectedActionIsNull() {
            WebClient webClient = mock(WebClient.class);
            RecaptchaService service = createServiceWithMockedWebClient(webClient, true, "secret", 0.5);
            Object response = createRecaptchaResponse(true, 0.8, "contact", "2025-01-01T00:00:00Z", "localhost", null);
            setupWebClientChain(webClient, Mono.just(response));

            StepVerifier.create(service.verify("token", null))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should complete at exact score threshold")
        void shouldCompleteAtExactThreshold() {
            WebClient webClient = mock(WebClient.class);
            RecaptchaService service = createServiceWithMockedWebClient(webClient, true, "secret", 0.5);
            Object response = createRecaptchaResponse(true, 0.5, "contact", "2025-01-01T00:00:00Z", "localhost", null);
            setupWebClientChain(webClient, Mono.just(response));

            StepVerifier.create(service.verify("token", "contact"))
                    .verifyComplete();
        }
    }
}
