package dev.catananti.controller;

import dev.catananti.repository.ArticleRepository;
import dev.catananti.service.ExportImportService;
import dev.catananti.service.ExportImportService.ImportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminExportControllerTest {

    @Mock
    private ExportImportService exportImportService;

    @Mock
    private ArticleRepository articleRepository;

    @InjectMocks
    private AdminExportController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "maxExportArticles", 10000);
        ReflectionTestUtils.setField(controller, "maxImportSize", 2097152);

        lenient().when(articleRepository.countAll()).thenReturn(Mono.just(25L));
    }

    // AUD19C-5: root GET /admin/export and GET /admin/export/stats tests removed
    // with the orphan endpoints. The export-limit check is now covered via /json.

    @Nested
    @DisplayName("GET /api/v1/admin/export/json")
    class ExportAsJsonFile {

        @Test
        @DisplayName("Should reject exports above the configured article limit")
        void shouldRejectExportWhenOverLimit() {
            when(articleRepository.countAll()).thenReturn(Mono.just(10001L));

            StepVerifier.create(controller.exportAsJsonFile("Admin"))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(ResponseStatusException.class);
                        ResponseStatusException ex = (ResponseStatusException) error;
                        assertThat(ex.getStatusCode().value()).isEqualTo(400);
                        assertThat(ex.getReason()).isEqualTo("error.export_limit_exceeded");
                    })
                    .verify();

            verifyNoInteractions(exportImportService);
        }

        @Test
        @DisplayName("Should export blog as JSON file with content-disposition header")
        void shouldExportAsJsonFile() {
            String json = "{\"version\":\"1.0\",\"articles\":[]}";

            when(exportImportService.exportToJson("Admin"))
                    .thenReturn(Mono.just(json));

            StepVerifier.create(controller.exportAsJsonFile("Admin"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).contains("\"version\"");
                        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                                .contains("blog-export-")
                                .contains(".json");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/export/markdown")
    class ExportAsMarkdown {

        @Test
        @DisplayName("Should export blog as Markdown")
        void shouldExportAsMarkdown() {
            Map<String, String> markdownExport = Map.of(
                    "spring-boot-guide.md", "---\ntitle: Spring Boot Guide\n---\nContent here",
                    "reactive-programming.md", "---\ntitle: Reactive Programming\n---\nMore content"
            );

            when(exportImportService.exportToMarkdown())
                    .thenReturn(Mono.just(markdownExport));

            StepVerifier.create(controller.exportAsMarkdown())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).containsKeys("spring-boot-guide.md", "reactive-programming.md");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/export/import")
    class ImportBlog {

        @Test
        @DisplayName("Should import blog data successfully")
        void shouldImportBlogData() {
            String jsonData = "{\"version\":\"1.0\",\"articles\":[{\"title\":\"Test\"}]}";
            ImportResult result = new ImportResult(1, 1, 2, 0);

            when(exportImportService.importFromJson(jsonData, false))
                    .thenReturn(Mono.just(result));

            StepVerifier.create(controller.importBlog(jsonData, false))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                        assertThat(response.getBody()).containsEntry("message", "Import completed");
                        assertThat(response.getBody()).containsEntry("articlesImported", 1);
                        assertThat(response.getBody()).containsEntry("tagsImported", 2);
                        assertThat(response.getBody()).containsEntry("errors", 0);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject payload exceeding max size")
        void shouldRejectOversizedPayload() {
            // Create a string larger than 2 MB
            String oversized = "x".repeat(2 * 1024 * 1024 + 1);

            StepVerifier.create(controller.importBlog(oversized, false))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(response.getBody()).containsKey("message");
                    })
                    .verifyComplete();

            verifyNoInteractions(exportImportService);
        }
    }

}
