package dev.catananti.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.catananti.config.ResilienceConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudflareEmailRoutingService")
class CloudflareEmailRoutingServiceTest {

    private MockWebServer mockServer;
    private CloudflareEmailRoutingService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResilienceConfig resilienceConfig = new ResilienceConfig(
            10, 3, 100, 1000, 5, 30, 50, 30, 10, 5, 50, 60, 10, 3);

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String mockBaseUrl = mockServer.url("/").toString();
        // The service constructor hardcodes CF_API_BASE as the WebClient base URL,
        // so we use an ExchangeFilterFunction to redirect all requests to MockWebServer.
        ExchangeFilterFunction redirectFilter = ExchangeFilterFunction.ofRequestProcessor(request -> {
            URI original = request.url();
            URI redirected = URI.create(mockBaseUrl + original.getPath()
                    + (original.getQuery() != null ? "?" + original.getQuery() : ""));
            return reactor.core.publisher.Mono.just(ClientRequest.from(request).url(redirected).build());
        });
        WebClient.Builder builder = WebClient.builder().filter(redirectFilter);

        service = new CloudflareEmailRoutingService(
                builder, resilienceConfig, "test-token", "zone123", "catananti.dev", true);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Nested
    @DisplayName("createForwardingRule")
    class CreateForwardingRule {

        @Test
        @DisplayName("should create rule and return rule ID on success")
        void shouldCreateRuleOnSuccess() throws JsonProcessingException {
            var response = new CloudflareEmailRoutingService.CfRuleResponse(
                    true,
                    new CloudflareEmailRoutingService.CfRuleResult("rule-abc", "test", true, null),
                    null, null);
            mockServer.enqueue(new MockResponse()
                    .setBody(objectMapper.writeValueAsString(response))
                    .setHeader("Content-Type", "application/json"));

            StepVerifier.create(service.createForwardingRule("john", "john@example.com"))
                    .expectNext("rule-abc")
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return error when CF API reports failure")
        void shouldReturnErrorOnApiFailure() throws JsonProcessingException {
            var response = new CloudflareEmailRoutingService.CfRuleResponse(
                    false, null,
                    List.of(new CloudflareEmailRoutingService.CfError(1001, "Invalid zone")),
                    null);
            mockServer.enqueue(new MockResponse()
                    .setBody(objectMapper.writeValueAsString(response))
                    .setHeader("Content-Type", "application/json"));

            StepVerifier.create(service.createForwardingRule("john", "john@example.com"))
                    .expectError(RuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("should return empty when network error occurs")
        void shouldReturnEmptyOnNetworkError() throws IOException {
            mockServer.shutdown(); // Force connection failure

            StepVerifier.create(service.createForwardingRule("john", "john@example.com"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("createForwardingRule when disabled")
    class WhenDisabled {

        @Test
        @DisplayName("should return empty when CF is disabled")
        void shouldReturnEmptyWhenDisabled() {
            var disabledService = new CloudflareEmailRoutingService(
                    WebClient.builder(), resilienceConfig, "token", "zone", "catananti.dev", false);

            StepVerifier.create(disabledService.createForwardingRule("john", "john@example.com"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("deleteForwardingRule")
    class DeleteForwardingRule {

        @Test
        @DisplayName("should delete rule successfully")
        void shouldDeleteRule() throws JsonProcessingException {
            var response = new CloudflareEmailRoutingService.CfRuleResponse(
                    true, null, null, null);
            mockServer.enqueue(new MockResponse()
                    .setBody(objectMapper.writeValueAsString(response))
                    .setHeader("Content-Type", "application/json"));

            StepVerifier.create(service.deleteForwardingRule("rule-abc"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty for null or blank rule ID")
        void shouldReturnEmptyForNullRuleId() {
            StepVerifier.create(service.deleteForwardingRule(null))
                    .verifyComplete();

            StepVerifier.create(service.deleteForwardingRule(""))
                    .verifyComplete();
        }
    }
}
