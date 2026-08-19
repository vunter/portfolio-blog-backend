package dev.catananti.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUD19C-4: LocalDateTime values are UTC instants and must serialize with an explicit
 * 'Z' suffix so browser clients ({@code new Date(...)}) parse them as UTC.
 *
 * <p>The WebTestClient test doubles as the Jackson2-vs-Jackson3 arbiter: Spring Boot 4
 * defaults its WebFlux codecs to Jackson 3 (tools.jackson), but WebFluxConfig
 * explicitly overrides the server codecs with Jackson2JsonEncoder/Decoder built on
 * JacksonConfig's com.fasterxml ObjectMapper. If that override ever stopped governing
 * the wire format, the 'Z' assertion below would fail.</p>
 */
@DisplayName("JacksonConfig (AUD19C-4)")
class JacksonConfigTest {

    private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();

    record TimestampPayload(String name, LocalDateTime createdAt) {
    }

    @Test
    @DisplayName("serializes LocalDateTime as a Z-suffixed UTC instant")
    void shouldSerializeLocalDateTimeWithZSuffix() throws Exception {
        var payload = new TimestampPayload("article", LocalDateTime.of(2026, 8, 18, 10, 30, 15));

        String json = objectMapper.writeValueAsString(payload);

        assertThat(json).contains("\"createdAt\":\"2026-08-18T10:30:15Z\"");
    }

    @Test
    @DisplayName("deserializes the Z-suffixed form the frontend sends")
    void shouldDeserializeZSuffixedDates() throws Exception {
        var payload = objectMapper.readValue(
                "{\"name\":\"a\",\"createdAt\":\"2026-08-18T10:30:15Z\"}", TimestampPayload.class);

        assertThat(payload.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 18, 10, 30, 15));
    }

    @Test
    @DisplayName("still deserializes the zoneless ISO form (backwards compatibility)")
    void shouldDeserializeZonelessDates() throws Exception {
        var payload = objectMapper.readValue(
                "{\"name\":\"a\",\"createdAt\":\"2026-08-18T10:30:15\"}", TimestampPayload.class);

        assertThat(payload.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 18, 10, 30, 15));
    }

    @Test
    @DisplayName("round-trips: serialized output is parseable again")
    void shouldRoundTrip() throws Exception {
        var original = new TimestampPayload("a", LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_000_000));

        String json = objectMapper.writeValueAsString(original);
        var parsed = objectMapper.readValue(json, TimestampPayload.class);

        assertThat(json).matches(".*\"createdAt\":\"[^\"]*Z\".*");
        assertThat(parsed.createdAt()).isEqualTo(original.createdAt());
    }

    // ==================== wire-format regression (codec arbiter) ====================

    @RestController
    static class FixtureController {
        @GetMapping("/fixture/timestamp")
        Mono<TimestampPayload> timestamp() {
            return Mono.just(new TimestampPayload("wire", LocalDateTime.of(2026, 8, 18, 10, 30, 15)));
        }
    }

    @Test
    @DisplayName("HTTP wire format carries the Z suffix (WebFluxConfig codec override governs)")
    void wireFormatShouldCarryZSuffix() {
        WebTestClient client = WebTestClient
                .bindToController(new FixtureController())
                .httpMessageCodecs(new WebFluxConfig(objectMapper)::configureHttpMessageCodecs)
                .build();

        client.get().uri("/fixture/timestamp")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.createdAt").value(v ->
                        assertThat(String.valueOf(v)).matches(".*Z$"));
    }
}
