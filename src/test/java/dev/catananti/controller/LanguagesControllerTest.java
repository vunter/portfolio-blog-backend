package dev.catananti.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LanguagesControllerTest {

    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;

    @InjectMocks
    private LanguagesController controller;

    @Nested
    @DisplayName("GET /api/v1/languages")
    class GetSupportedLanguages {

        @Test
        @DisplayName("Should return list of supported languages")
        @SuppressWarnings("unchecked")
        void shouldReturnSupportedLanguages() {
            Map<String, Object> english = new LinkedHashMap<>();
            english.put("code", "en");
            english.put("name", "English");
            english.put("nativeName", "English");
            english.put("sortOrder", 1);

            Map<String, Object> portuguese = new LinkedHashMap<>();
            portuguese.put("code", "pt");
            portuguese.put("name", "Portuguese");
            portuguese.put("nativeName", "Portugu\u00eas");
            portuguese.put("sortOrder", 2);

            DatabaseClient databaseClient = mock(DatabaseClient.class);
            DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
            FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);

            when(r2dbcTemplate.getDatabaseClient()).thenReturn(databaseClient);
            when(databaseClient.sql(anyString())).thenReturn(executeSpec);
            when(executeSpec.map(any(java.util.function.BiFunction.class))).thenReturn(fetchSpec);
            when(fetchSpec.all()).thenReturn(Flux.just(english, portuguese));

            StepVerifier.create(controller.getSupportedLanguages())
                    .assertNext(languages -> {
                        assertThat(languages).hasSize(2);
                        assertThat(languages.get(0).get("code")).isEqualTo("en");
                        assertThat(languages.get(0).get("name")).isEqualTo("English");
                        assertThat(languages.get(1).get("code")).isEqualTo("pt");
                        assertThat(languages.get(1).get("nativeName")).isEqualTo("Portugu\u00eas");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty list when no languages configured")
        @SuppressWarnings("unchecked")
        void shouldReturnEmptyListWhenNoLanguages() {
            DatabaseClient databaseClient = mock(DatabaseClient.class);
            DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
            FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);

            when(r2dbcTemplate.getDatabaseClient()).thenReturn(databaseClient);
            when(databaseClient.sql(anyString())).thenReturn(executeSpec);
            when(executeSpec.map(any(java.util.function.BiFunction.class))).thenReturn(fetchSpec);
            when(fetchSpec.all()).thenReturn(Flux.empty());

            StepVerifier.create(controller.getSupportedLanguages())
                    .assertNext(languages -> assertThat(languages).isEmpty())
                    .verifyComplete();
        }
    }
}
