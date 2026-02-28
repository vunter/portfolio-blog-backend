package dev.catananti.controller;

import dev.catananti.dto.BlogExport;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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

    private BlogExport blogExport;
    private BlogExport.BlogStats blogStats;

    @BeforeEach
    void setUp() {
        lenient().when(articleRepository.countAll()).thenReturn(Mono.just(25L));

        blogStats = BlogExport.BlogStats.builder()
                .totalArticles(25)
                .publishedArticles(20)
                .draftArticles(3)
                .scheduledArticles(2)
                .totalTags(10)
                .totalViews(5000)
                .totalLikes(300)
                .build();

        blogExport = BlogExport.builder()
                .version("1.0")
                .exportedAt(LocalDateTime.now())
                .exportedBy("Admin")
                .stats(blogStats)
                .articles(List.of())
                .tags(List.of())
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/export")
    class ExportBlog {

        @Test
        @DisplayName("Should export blog data")
        void shouldExportBlogData() {
            when(exportImportService.exportAll("Admin"))
                    .thenReturn(Mono.just(blogExport));

            StepVerifier.create(controller.exportBlog("Admin"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().getVersion()).isEqualTo("1.0");
                        assertThat(response.getBody().getExportedBy()).isEqualTo("Admin");
                        assertThat(response.getBody().getStats().getTotalArticles()).isEqualTo(25);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/export/json")
    class ExportAsJsonFile {

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
                                .startsWith("attachment; filename=\"blog-export-");
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

    @Nested
    @DisplayName("GET /api/v1/admin/export/stats")
    class GetExportStats {

        @Test
        @DisplayName("Should return export statistics")
        void shouldReturnExportStats() {
            when(exportImportService.exportAll("preview"))
                    .thenReturn(Mono.just(blogExport));

            StepVerifier.create(controller.getExportStats())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().getTotalArticles()).isEqualTo(25);
                        assertThat(response.getBody().getPublishedArticles()).isEqualTo(20);
                        assertThat(response.getBody().getTotalTags()).isEqualTo(10);
                    })
                    .verifyComplete();
        }
    }
}
