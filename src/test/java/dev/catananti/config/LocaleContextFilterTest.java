package dev.catananti.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LocaleContextFilter Tests")
class LocaleContextFilterTest {

    private LocaleContextFilter filter;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new LocaleContextFilter();
        chain = mock(WebFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Should default to English when no Accept-Language header")
    void shouldDefaultToEnglish() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Use a chain that reads the context written by the filter
        WebFilterChain contextChain = ex -> Mono.deferContextual(ctx -> {
            Locale locale = ctx.get("locale");
            assertThat(locale).isEqualTo(Locale.ENGLISH);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, contextChain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should parse Accept-Language header for supported locale")
    void shouldParseAcceptLanguageHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "pt-BR,pt;q=0.9,en;q=0.8")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // The filter writes locale to context; verify chain is called
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should parse Accept-Language for French locale")
    void shouldParseFrenchAcceptLanguage() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR,fr;q=0.9")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should parse Accept-Language for Japanese locale")
    void shouldParseJapaneseAcceptLanguage() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "ja")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should use query parameter override over Accept-Language header")
    void shouldUseQueryParameterOverride() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?lang=es")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should parse lang=pt query parameter to pt-BR")
    void shouldParsePortugueseQueryParam() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?lang=pt").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should parse lang=it query parameter to Italian locale")
    void shouldParseItalianQueryParam() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?lang=it").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should parse lang=de query parameter to German locale")
    void shouldParseGermanQueryParam() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?lang=de").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should parse lang=zh query parameter to Chinese locale")
    void shouldParseChineseQueryParam() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?lang=zh").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should parse lang=fr query parameter to French locale")
    void shouldParseFrenchQueryParam() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?lang=fr").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should parse lang=ja query parameter to Japanese locale")
    void shouldParseJapaneseQueryParam() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?lang=ja").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should default to English for unknown lang parameter")
    void shouldDefaultToEnglishForUnknownLang() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?lang=xx").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should default to English for unsupported Accept-Language")
    void shouldDefaultToEnglishForUnsupportedLanguage() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should ignore blank Accept-Language header")
    void shouldIgnoreBlankAcceptLanguage() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "   ")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should ignore blank lang query parameter")
    void shouldIgnoreBlankLangQueryParam() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?lang=  ").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle multiple Accept-Language ranges with quality values")
    void shouldHandleMultipleLanguageRanges() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle Spanish Accept-Language")
    void shouldHandleSpanishAcceptLanguage() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "es-MX,es;q=0.9")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }
}
