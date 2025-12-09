package dev.catananti.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Translation service using DeepL API Free.
 * <p>
 * Supports batch translation of multiple texts in a single API call for efficiency.
 * DeepL Free tier: 500,000 characters/month.
 * </p>
 * <p>
 * Supported languages: EN, PT, PT-BR, DE, FR, ES, IT, NL, PL, RU, JA, ZH, etc.
 * </p>
 * TODO F-235: Track monthly character usage to warn before hitting DeepL free tier limit
 */
@Service
@Slf4j
public class TranslationService {

    private static final String DEEPL_FREE_URL = "https://api-free.deepl.com/v2/translate";
    private static final String DEEPL_PRO_URL = "https://api.deepl.com/v2/translate";
    private static final int MAX_BATCH_SIZE = 50; // DeepL allows up to 50 texts per request

    private final WebClient webClient;
    private final String apiKey;
    private final boolean usePro;
    private final CircuitBreaker circuitBreaker;

    public TranslationService(
            WebClient.Builder webClientBuilder,
            @Value("${deepl.api-key:}") String apiKey,
            @Value("${deepl.use-pro:false}") boolean usePro) {
        this.apiKey = apiKey;
        this.usePro = usePro;
        String baseUrl = usePro ? DEEPL_PRO_URL : DEEPL_FREE_URL;
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .build();
        this.circuitBreaker = CircuitBreaker.of("deepl-translation", cbConfig);
        log.info("DeepL circuit breaker initialised (failureRate=50%, window=10, waitOpen=30s)");
    }

    /**
     * Check if the translation service is configured and available.
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Translate a single text to the target language.
     *
     * @param text       the text to translate
     * @param targetLang target language code (e.g., "EN", "PT-BR", "DE", "FR")
     * @return translated text
     */
    public Mono<String> translate(String text, String targetLang) {
        if (text == null || text.isBlank()) {
            return Mono.just(text != null ? text : "");
        }
        return translateBatch(List.of(text), targetLang)
                .map(results -> results.isEmpty() ? text : results.getFirst());
    }

    /**
     * Translate multiple texts in a single API call (batch).
     * DeepL supports up to 50 texts per request. If more are provided,
     * they will be split into multiple requests.
     *
     * @param texts      list of texts to translate
     * @param targetLang target language code (e.g., "EN", "PT-BR")
     * @return list of translated texts in the same order
     */
    public Mono<List<String>> translateBatch(List<String> texts, String targetLang) {
        if (!isAvailable()) {
            return Mono.error(new IllegalStateException(
                    "DeepL API key not configured. Set 'deepl.api-key' in application properties."));
        }

        if (texts == null || texts.isEmpty()) {
            return Mono.just(List.of());
        }

        // Track which indices have actual content vs blank
        List<Integer> contentIndices = new ArrayList<>();
        List<String> contentTexts = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t != null && !t.isBlank()) {
                contentIndices.add(i);
                contentTexts.add(t);
            }
        }

        if (contentTexts.isEmpty()) {
            return Mono.just(new ArrayList<>(texts));
        }

        // Normalize target language for DeepL
        String normalizedLang = normalizeLanguage(targetLang);

        // Split into batches if needed
        if (contentTexts.size() <= MAX_BATCH_SIZE) {
            return callDeepL(contentTexts, normalizedLang)
                    .map(translated -> reassemble(texts, contentIndices, translated));
        }

        // Multiple batches
        List<Mono<List<String>>> batches = new ArrayList<>();
        for (int i = 0; i < contentTexts.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, contentTexts.size());
            batches.add(callDeepL(contentTexts.subList(i, end), normalizedLang));
        }

        return Mono.zip(batches, results -> {
            List<String> allTranslated = new ArrayList<>();
            for (Object r : results) {
                @SuppressWarnings("unchecked")
                List<String> batch = (List<String>) r;
                allTranslated.addAll(batch);
            }
            return reassemble(texts, contentIndices, allTranslated);
        });
    }

    // TODO F-230: Use UriComponentsBuilder instead of manual URL parameter concatenation
    private Mono<List<String>> callDeepL(List<String> texts, String targetLang) {
        // Build form data: text=...&text=...&target_lang=...
        StringBuilder formBody = new StringBuilder();
        for (String text : texts) {
            if (!formBody.isEmpty()) formBody.append("&");
            formBody.append("text=").append(urlEncode(text));
        }
        formBody.append("&target_lang=").append(targetLang);

        log.debug("DeepL request: {} texts, targetLang={}", texts.size(), targetLang);

        return webClient.post()
                .header("Authorization", "DeepL-Auth-Key " + apiKey)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(formBody.toString())
                .retrieve()
                .bodyToMono(DeepLResponse.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .map(response -> {
                    if (response.getTranslations() == null) {
                        log.warn("DeepL returned null translations");
                        return new ArrayList<>(texts);
                    }
                    return response.getTranslations().stream()
                            .map(DeepLTranslation::getText)
                            .toList();
                })
                .doOnSuccess(r -> log.info("DeepL translated {} texts to {}", texts.size(), targetLang))
                .doOnError(e -> log.error("DeepL translation failed: {}", e.getMessage()));
    }

    /**
     * Reassemble the full result list, putting translated texts back at their original indices.
     */
    private List<String> reassemble(List<String> originals, List<Integer> contentIndices, List<String> translated) {
        List<String> result = new ArrayList<>(originals);
        for (int i = 0; i < contentIndices.size() && i < translated.size(); i++) {
            result.set(contentIndices.get(i), translated.get(i));
        }
        return result;
    }

    /**
     * Normalize language codes for DeepL API.
     * DeepL uses "EN" for English (or "EN-US"/"EN-GB"), "PT-BR" for Brazilian Portuguese, etc.
     */
    private String normalizeLanguage(String lang) {
        if (lang == null) return "EN";
        return switch (lang.toUpperCase().trim()) {
            case "EN", "EN-US", "EN-GB" -> "EN";
            case "PT", "PT-BR" -> "PT-BR";
            case "PT-PT" -> "PT-PT";
            default -> lang.toUpperCase().trim();
        };
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    // DeepL API response DTOs
    @Data
    static class DeepLResponse {
        private List<DeepLTranslation> translations;
    }

    @Data
    static class DeepLTranslation {
        @JsonProperty("detected_source_language")
        private String detectedSourceLanguage;
        private String text;
    }
}
