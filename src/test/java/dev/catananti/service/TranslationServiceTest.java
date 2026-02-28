package dev.catananti.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TranslationService.
 * Since TranslationService has constructor injection with @Value parameters (not @Mock-injectable),
 * we instantiate directly via constructor.
 */
class TranslationServiceTest {

    /**
     * Create a TranslationService with a given API key and usePro setting.
     */
    private TranslationService createService(String apiKey) {
        return new TranslationService(
                org.springframework.web.reactive.function.client.WebClient.builder(),
                apiKey,
                false
        );
    }

    // ============================
    // isAvailable
    // ============================
    @Nested
    @DisplayName("isAvailable")
    class IsAvailable {

        @Test
        @DisplayName("should return true when API key is configured")
        void configured_returnsTrue() {
            TranslationService service = createService("fake-api-key-12345");
            assertThat(service.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("should return false when API key is null")
        void nullKey_returnsFalse() {
            TranslationService service = createService(null);
            assertThat(service.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("should return false when API key is blank")
        void blankKey_returnsFalse() {
            TranslationService service = createService("");
            assertThat(service.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("should return false when API key is whitespace")
        void whitespaceKey_returnsFalse() {
            TranslationService service = createService("   ");
            assertThat(service.isAvailable()).isFalse();
        }
    }

    // ============================
    // translate (single text)
    // ============================
    @Nested
    @DisplayName("translate")
    class Translate {

        @Test
        @DisplayName("should pass through null text as empty string")
        void nullText_returnsEmpty() {
            TranslationService service = createService("key");
            StepVerifier.create(service.translate(null, "EN"))
                    .expectNext("")
                    .verifyComplete();
        }

        @Test
        @DisplayName("should pass through blank text as-is")
        void blankText_returnsSame() {
            TranslationService service = createService("key");
            StepVerifier.create(service.translate("   ", "EN"))
                    .expectNext("   ")
                    .verifyComplete();
        }

        @Test
        @DisplayName("should pass through empty text as-is")
        void emptyText_returnsSame() {
            TranslationService service = createService("key");
            StepVerifier.create(service.translate("", "EN"))
                    .expectNext("")
                    .verifyComplete();
        }
    }

    // ============================
    // translateBatch
    // ============================
    @Nested
    @DisplayName("translateBatch")
    class TranslateBatch {

        @Test
        @DisplayName("should return empty list for null input")
        void nullTexts_returnsEmptyList() {
            TranslationService service = createService("key");
            StepVerifier.create(service.translateBatch(null, "EN"))
                    .expectNext(List.of())
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty list for empty input")
        void emptyTexts_returnsEmptyList() {
            TranslationService service = createService("key");
            StepVerifier.create(service.translateBatch(List.of(), "EN"))
                    .expectNext(List.of())
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return original list when all items are blank")
        void allBlankTexts_returnsOriginals() {
            TranslationService service = createService("key");
            List<String> blanks = List.of("", "   ", "");
            StepVerifier.create(service.translateBatch(blanks, "EN"))
                    .assertNext(result -> {
                        assertThat(result).hasSize(3);
                        assertThat(result.get(0)).isEmpty();
                        assertThat(result.get(1)).isEqualTo("   ");
                        assertThat(result.get(2)).isEmpty();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return error when API key not configured")
        void unconfigured_returnsError() {
            TranslationService service = createService("");
            StepVerifier.create(service.translateBatch(List.of("Hello"), "EN"))
                    .expectErrorMatches(e -> e instanceof IllegalStateException
                            && e.getMessage().contains("DeepL API key not configured"))
                    .verify();
        }
    }

    // ============================
    // normalizeLanguage (private — tested via reflection)
    // ============================
    @Nested
    @DisplayName("normalizeLanguage")
    class NormalizeLanguage {

        private String invokeNormalizeLanguage(String lang) throws Exception {
            TranslationService service = createService("key");
            Method method = TranslationService.class.getDeclaredMethod("normalizeLanguage", String.class);
            method.setAccessible(true);
            return (String) method.invoke(service, lang);
        }

        @Test
        @DisplayName("should normalize EN to EN")
        void en_returnsEN() throws Exception {
            assertThat(invokeNormalizeLanguage("EN")).isEqualTo("EN");
        }

        @Test
        @DisplayName("should normalize en to EN")
        void lowerEn_returnsEN() throws Exception {
            assertThat(invokeNormalizeLanguage("en")).isEqualTo("EN");
        }

        @Test
        @DisplayName("should normalize EN-US to EN")
        void enUs_returnsEN() throws Exception {
            assertThat(invokeNormalizeLanguage("EN-US")).isEqualTo("EN");
        }

        @Test
        @DisplayName("should normalize EN-GB to EN")
        void enGb_returnsEN() throws Exception {
            assertThat(invokeNormalizeLanguage("EN-GB")).isEqualTo("EN");
        }

        @Test
        @DisplayName("should normalize PT-BR to PT-BR")
        void ptBr_returnsPTBR() throws Exception {
            assertThat(invokeNormalizeLanguage("PT-BR")).isEqualTo("PT-BR");
        }

        @Test
        @DisplayName("should normalize PT to PT-BR")
        void pt_returnsPTBR() throws Exception {
            assertThat(invokeNormalizeLanguage("PT")).isEqualTo("PT-BR");
        }

        @Test
        @DisplayName("should normalize PT-PT to PT-PT")
        void ptPt_returnsPTPT() throws Exception {
            assertThat(invokeNormalizeLanguage("PT-PT")).isEqualTo("PT-PT");
        }

        @Test
        @DisplayName("should default to EN for null input")
        void nullInput_returnsEN() throws Exception {
            assertThat(invokeNormalizeLanguage(null)).isEqualTo("EN");
        }

        @Test
        @DisplayName("should uppercase unknown language codes")
        void unknownLang_returnsUppercased() throws Exception {
            assertThat(invokeNormalizeLanguage("ja")).isEqualTo("JA");
        }

        @Test
        @DisplayName("should trim whitespace from language code")
        void whitespace_isTrimmed() throws Exception {
            assertThat(invokeNormalizeLanguage("  de  ")).isEqualTo("DE");
        }
    }

    // ==================== ADDED TESTS ====================

    @Nested
    @DisplayName("translate error propagation")
    class TranslateErrorPropagation {

        @Test
        @DisplayName("should propagate error when translating non-blank text without API key")
        void noApiKey_singleText_shouldError() {
            TranslationService service = createService("");
            StepVerifier.create(service.translate("Hello World", "DE"))
                    .expectErrorMatches(e -> e instanceof IllegalStateException
                            && e.getMessage().contains("DeepL API key not configured"))
                    .verify();
        }

        @Test
        @DisplayName("should propagate error for null API key with non-blank text")
        void nullApiKey_singleText_shouldError() {
            TranslationService service = createService(null);
            StepVerifier.create(service.translate("Hello", "FR"))
                    .expectErrorMatches(e -> e instanceof IllegalStateException
                            && e.getMessage().contains("DeepL API key not configured"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("translateBatch edge cases")
    class TranslateBatchEdgeCases {

        @Test
        @DisplayName("should return originals when list contains only nulls")
        void nullEntries_returnsOriginals() {
            TranslationService service = createService("key");
            List<String> nullList = new ArrayList<>();
            nullList.add(null);
            nullList.add(null);
            StepVerifier.create(service.translateBatch(nullList, "DE"))
                    .assertNext(result -> {
                        assertThat(result).hasSize(2);
                        assertThat(result.get(0)).isNull();
                        assertThat(result.get(1)).isNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return mixed list when some entries are blank and some null")
        void mixedBlankAndNull_returnsOriginals() {
            TranslationService service = createService("key");
            List<String> mixed = new ArrayList<>();
            mixed.add("");
            mixed.add(null);
            mixed.add("   ");
            StepVerifier.create(service.translateBatch(mixed, "EN"))
                    .assertNext(result -> {
                        assertThat(result).hasSize(3);
                        assertThat(result.get(0)).isEmpty();
                        assertThat(result.get(1)).isNull();
                        assertThat(result.get(2)).isEqualTo("   ");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("reassemble (private)")
    class Reassemble {

        @SuppressWarnings("unchecked")
        private List<String> invokeReassemble(List<String> originals, List<Integer> indices, List<String> translated) throws Exception {
            TranslationService service = createService("key");
            Method method = TranslationService.class.getDeclaredMethod("reassemble", List.class, List.class, List.class);
            method.setAccessible(true);
            return (List<String>) method.invoke(service, originals, indices, translated);
        }

        @Test
        @DisplayName("should place translated texts at correct indices")
        void shouldReassembleCorrectly() throws Exception {
            List<String> originals = new ArrayList<>(List.of("", "Hello", "", "World"));
            List<Integer> indices = List.of(1, 3);
            List<String> translated = List.of("Hola", "Mundo");

            List<String> result = invokeReassemble(originals, indices, translated);
            assertThat(result).containsExactly("", "Hola", "", "Mundo");
        }

        @Test
        @DisplayName("should handle empty translated list gracefully")
        void shouldHandleEmptyTranslated() throws Exception {
            List<String> originals = new ArrayList<>(List.of("a", "b"));
            List<Integer> indices = List.of(0, 1);
            List<String> translated = List.of();

            List<String> result = invokeReassemble(originals, indices, translated);
            assertThat(result).containsExactly("a", "b");
        }

        @Test
        @DisplayName("should handle partial translated list")
        void shouldHandlePartialTranslated() throws Exception {
            List<String> originals = new ArrayList<>(List.of("a", "b", "c"));
            List<Integer> indices = List.of(0, 1, 2);
            List<String> translated = List.of("X");

            List<String> result = invokeReassemble(originals, indices, translated);
            assertThat(result).containsExactly("X", "b", "c");
        }
    }

    // urlEncode method was removed — form encoding handled by BodyInserters.fromFormData

    @Nested
    @DisplayName("isAvailable with Pro setting")
    class IsAvailableProSetting {

        @Test
        @DisplayName("should create service with pro URL setting")
        void proSetting_shouldStillCheckApiKey() {
            TranslationService service = new TranslationService(
                    org.springframework.web.reactive.function.client.WebClient.builder(),
                    "pro-key",
                    true
            );
            assertThat(service.isAvailable()).isTrue();
        }
    }

    // ==================== callDeepL via mocked WebClient ====================

    @Nested
    @DisplayName("callDeepL via translateBatch (mocked WebClient)")
    class CallDeepLViaTranslateBatch {

        @SuppressWarnings({"unchecked", "rawtypes"})
        private TranslationService createServiceWithMockedWebClient(Mono<?> bodyToMonoResponse) {
            WebClient webClient = Mockito.mock(WebClient.class);
            WebClient.RequestBodyUriSpec uriSpec = Mockito.mock(WebClient.RequestBodyUriSpec.class);
            WebClient.RequestBodySpec bodySpec = Mockito.mock(WebClient.RequestBodySpec.class);
            WebClient.RequestHeadersSpec headersSpec = Mockito.mock(WebClient.RequestHeadersSpec.class);
            WebClient.ResponseSpec responseSpec = Mockito.mock(WebClient.ResponseSpec.class);

            Mockito.when(webClient.post()).thenReturn(uriSpec);
            Mockito.when(uriSpec.header(ArgumentMatchers.anyString(), ArgumentMatchers.<String>any())).thenReturn(bodySpec);
            Mockito.when(bodySpec.contentType(ArgumentMatchers.any())).thenReturn(bodySpec);
            Mockito.when(bodySpec.body(ArgumentMatchers.any())).thenReturn(headersSpec);
            Mockito.when(headersSpec.retrieve()).thenReturn(responseSpec);
            Mockito.doReturn(bodyToMonoResponse).when(responseSpec).bodyToMono(ArgumentMatchers.<Class>any());

            WebClient.Builder builder = Mockito.mock(WebClient.Builder.class);
            Mockito.when(builder.baseUrl(ArgumentMatchers.anyString())).thenReturn(builder);
            Mockito.when(builder.build()).thenReturn(webClient);

            return new TranslationService(builder, "test-api-key", false);
        }

        @Test
        @DisplayName("should translate single batch successfully")
        void shouldTranslateSingleBatch() {
            TranslationService.DeepLResponse response = new TranslationService.DeepLResponse();
            TranslationService.DeepLTranslation t1 = new TranslationService.DeepLTranslation();
            t1.setText("Hola");
            response.setTranslations(List.of(t1));

            TranslationService service = createServiceWithMockedWebClient(Mono.just(response));

            StepVerifier.create(service.translateBatch(List.of("Hello"), "ES"))
                    .assertNext(result -> {
                        assertThat(result).hasSize(1);
                        assertThat(result.get(0)).isEqualTo("Hola");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should handle null translations in response")
        void shouldHandleNullTranslations() {
            TranslationService.DeepLResponse response = new TranslationService.DeepLResponse();
            response.setTranslations(null);

            TranslationService service = createServiceWithMockedWebClient(Mono.just(response));

            StepVerifier.create(service.translateBatch(List.of("Hello"), "ES"))
                    .assertNext(result -> {
                        assertThat(result).hasSize(1);
                        assertThat(result.get(0)).isEqualTo("Hello");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should translate mixed content with blanks")
        void shouldTranslateMixedContent() {
            TranslationService.DeepLResponse response = new TranslationService.DeepLResponse();
            TranslationService.DeepLTranslation t1 = new TranslationService.DeepLTranslation();
            t1.setText("Hola");
            TranslationService.DeepLTranslation t2 = new TranslationService.DeepLTranslation();
            t2.setText("Mundo");
            response.setTranslations(List.of(t1, t2));

            TranslationService service = createServiceWithMockedWebClient(Mono.just(response));

            List<String> input = new ArrayList<>();
            input.add("Hello");
            input.add("");
            input.add("World");

            StepVerifier.create(service.translateBatch(input, "ES"))
                    .assertNext(result -> {
                        assertThat(result).hasSize(3);
                        assertThat(result.get(0)).isEqualTo("Hola");
                        assertThat(result.get(1)).isEmpty();
                        assertThat(result.get(2)).isEqualTo("Mundo");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should translate single text via translate() to callDeepL")
        void shouldTranslateSingleTextViaDeepL() {
            TranslationService.DeepLResponse response = new TranslationService.DeepLResponse();
            TranslationService.DeepLTranslation t1 = new TranslationService.DeepLTranslation();
            t1.setText("Bonjour");
            response.setTranslations(List.of(t1));

            TranslationService service = createServiceWithMockedWebClient(Mono.just(response));

            StepVerifier.create(service.translate("Hello", "FR"))
                    .expectNext("Bonjour")
                    .verifyComplete();
        }

        @Test
        @DisplayName("should handle DeepL error gracefully")
        void shouldHandleDeepLError() {
            TranslationService service = createServiceWithMockedWebClient(
                    Mono.error(new RuntimeException("Connection refused")));

            StepVerifier.create(service.translateBatch(List.of("Hello"), "ES"))
                    .expectError(RuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("should translate multiple batches when exceeding MAX_BATCH_SIZE")
        void shouldTranslateMultipleBatches() {
            TranslationService.DeepLResponse batchResponse = new TranslationService.DeepLResponse();
            List<TranslationService.DeepLTranslation> batchTranslations = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                TranslationService.DeepLTranslation t = new TranslationService.DeepLTranslation();
                t.setText("t" + i);
                batchTranslations.add(t);
            }
            batchResponse.setTranslations(batchTranslations);

            TranslationService service = createServiceWithMockedWebClient(Mono.just(batchResponse));

            // Create 51 texts to trigger multi-batch
            List<String> texts = new ArrayList<>(IntStream.range(0, 51)
                    .mapToObj(i -> "text" + i)
                    .toList());

            StepVerifier.create(service.translateBatch(texts, "DE"))
                    .assertNext(result -> {
                        assertThat(result).hasSize(51);
                        assertThat(result.get(0)).isEqualTo("t0");
                    })
                    .verifyComplete();
        }
    }
}
