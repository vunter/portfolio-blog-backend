package dev.catananti.controller;

import dev.catananti.config.PaginationConfig;
import dev.catananti.repository.TranslationRepository;
import dev.catananti.service.IdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminTranslationControllerTest {

    @Mock
    private TranslationRepository translationRepository;

    @Mock
    private I18nController i18nController;

    @Mock
    private IdService idService;

    @Spy
    private PaginationConfig paginationConfig = new PaginationConfig();

    @InjectMocks
    private AdminTranslationController controller;

    @Nested
    @DisplayName("GET /api/v1/admin/settings/translations")
    class ListTranslations {

        @Test
        @DisplayName("Should return paginated translations without search")
        void shouldReturnPaginatedTranslations() {
            Map<String, Object> item = Map.of(
                    // AUD19C-SNOW: repository now stringifies Snowflake ids
                    "id", "1",
                    "translationKey", "home.title",
                    "locale", "en",
                    "value", "Home",
                    "namespace", "frontend",
                    "visibility", "public"
            );

            when(translationRepository.findAllPaginated("en", "frontend", null, 0, 50))
                    .thenReturn(Flux.just(item));
            when(translationRepository.countAll("en", "frontend", null))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(controller.listTranslations("en", "frontend", null, 0, 50))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat((List<?>) body.get("items")).hasSize(1);
                        assertThat(body.get("total")).isEqualTo(1L);
                        assertThat(body.get("page")).isEqualTo(0);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return paginated translations with search filter")
        void shouldReturnFilteredTranslations() {
            when(translationRepository.findAllPaginated("en", "frontend", "home", 0, 50))
                    .thenReturn(Flux.empty());
            when(translationRepository.countAll("en", "frontend", "home"))
                    .thenReturn(Mono.just(0L));

            StepVerifier.create(controller.listTranslations("en", "frontend", "home", 0, 50))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat((List<?>) body.get("items")).isEmpty();
                        assertThat(body.get("total")).isEqualTo(0L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/settings/translations/{id}")
    class UpdateTranslation {

        @Test
        @DisplayName("Should update translation value")
        void shouldUpdateTranslation() {
            when(translationRepository.updateValue(1L, "Updated"))
                    .thenReturn(Mono.just(1));
            when(i18nController.invalidateCache())
                    .thenReturn(Mono.just(ResponseEntity.ok(Map.of("status", "cache invalidated"))));

            StepVerifier.create(controller.updateTranslation(1L, Map.of("value", "Updated")))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).containsEntry("status", "updated");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when value is missing")
        void shouldReturnBadRequestWhenValueMissing() {
            StepVerifier.create(Mono.defer(() -> controller.updateTranslation(1L, Map.of())))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                            && "error.invalid_request_data".equals(ex.getMessage()))
                    .verify();
        }

        @Test
        @DisplayName("Should return not found when translation does not exist")
        void shouldReturnNotFoundWhenTranslationNotExists() {
            when(translationRepository.updateValue(999L, "Updated"))
                    .thenReturn(Mono.just(0));

            StepVerifier.create(controller.updateTranslation(999L, Map.of("value", "Updated")))
                    .assertNext(response ->
                            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/settings/translations")
    class CreateTranslation {

        @Test
        @DisplayName("Should create a new translation")
        void shouldCreateTranslation() {
            when(idService.nextId()).thenReturn(100L);
            when(translationRepository.insert(100L, "new.key", "en", "New Value", "frontend", "public"))
                    .thenReturn(Mono.just(100L));
            when(i18nController.invalidateCache())
                    .thenReturn(Mono.just(ResponseEntity.ok(Map.of("status", "cache invalidated"))));

            Map<String, String> body = Map.of(
                    "translationKey", "new.key",
                    "locale", "en",
                    "value", "New Value",
                    "namespace", "frontend",
                    "visibility", "public"
            );

            StepVerifier.create(controller.createTranslation(body))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).containsEntry("status", "created");
                        // AUD19C-SNOW: id serialized as String
                        assertThat(response.getBody()).containsEntry("id", "100");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when required fields are missing")
        void shouldReturnBadRequestWhenFieldsMissing() {
            StepVerifier.create(Mono.defer(() -> controller.createTranslation(Map.of("translationKey", "key.only"))))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                            && "error.invalid_request_data".equals(ex.getMessage()))
                    .verify();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/settings/translations/{id}")
    class DeleteTranslation {

        @Test
        @DisplayName("Should delete translation")
        void shouldDeleteTranslation() {
            when(translationRepository.deleteById(1L))
                    .thenReturn(Mono.just(1));
            when(i18nController.invalidateCache())
                    .thenReturn(Mono.just(ResponseEntity.ok(Map.of("status", "cache invalidated"))));

            StepVerifier.create(controller.deleteTranslation(1L))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).containsEntry("status", "deleted");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return not found when translation does not exist")
        void shouldReturnNotFoundWhenNotExists() {
            when(translationRepository.deleteById(999L))
                    .thenReturn(Mono.just(0));

            StepVerifier.create(controller.deleteTranslation(999L))
                    .assertNext(response ->
                            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/settings/translations/cache/invalidate")
    class InvalidateCache {

        @Test
        @DisplayName("Should invalidate cache")
        void shouldInvalidateCache() {
            when(i18nController.invalidateCache())
                    .thenReturn(Mono.just(ResponseEntity.ok(Map.of("status", "cache invalidated"))));

            StepVerifier.create(controller.invalidateCache())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).containsEntry("status", "cache invalidated");
                    })
                    .verifyComplete();
        }
    }
}
