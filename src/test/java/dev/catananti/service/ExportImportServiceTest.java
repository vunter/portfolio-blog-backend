package dev.catananti.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.catananti.dto.ArticleExportData;
import dev.catananti.dto.BlogExport;
import dev.catananti.dto.BlogExport.BlogStats;
import dev.catananti.dto.BlogExport.TagExportData;
import dev.catananti.entity.Article;
import dev.catananti.entity.LocalizedText;
import dev.catananti.entity.Tag;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.ArticleTagRepository;
import dev.catananti.repository.TagRepository;
import dev.catananti.service.HtmlSanitizerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExportImportServiceTest {

    @Mock private ArticleRepository articleRepository;
    @Mock private TagRepository tagRepository;
    @Mock private ArticleTagRepository articleTagRepository;
    @Mock private IdService idService;

    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @Spy private HtmlSanitizerService htmlSanitizerService = new HtmlSanitizerService();

    @InjectMocks
    private ExportImportService exportImportService;

    private Article testArticle;
    private Tag testTag;

    @BeforeEach
    void setUp() {
        testArticle = Article.builder()
                .id(1L)
                .slug("test-article")
                .title("Test Article")
                .subtitle("Test Subtitle")
                .content("This is test content.")
                .excerpt("Test excerpt")
                .coverImageUrl("https://example.com/image.jpg")
                .status("PUBLISHED")
                .publishedAt(LocalDateTime.of(2026, 1, 15, 10, 0))
                .readingTimeMinutes(5)
                .viewsCount(100)
                .likesCount(10)
                .seoTitle("SEO Title")
                .seoDescription("SEO Description")
                .seoKeywords("java,spring")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 1, 15, 10, 0))
                .build();

        testTag = Tag.builder()
                .id(10L)
                .name(LocalizedText.ofEnglish("Java"))
                .slug("java")
                .description(LocalizedText.ofEnglish("Java programming"))
                .color("#E76F00")
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ==========================================
    // exportAll
    // ==========================================
    @Nested
    @DisplayName("exportAll")
    class ExportAll {

        @Test
        @DisplayName("Should export all articles and tags with stats")
        void shouldExportAllData() {
            when(articleRepository.findAll()).thenReturn(Flux.just(testArticle));
            when(articleTagRepository.findTagIdsByArticleIds(any(Long[].class)))
                    .thenReturn(Flux.just(new long[]{1L, 10L}));
            when(tagRepository.findAllById(anyList())).thenReturn(Flux.just(testTag));
            when(tagRepository.findAll()).thenReturn(Flux.just(testTag));
            when(articleRepository.countAll()).thenReturn(Mono.just(1L));
            when(articleRepository.countByStatus("PUBLISHED")).thenReturn(Mono.just(1L));
            when(articleRepository.countByStatus("DRAFT")).thenReturn(Mono.just(0L));
            when(articleRepository.countScheduled()).thenReturn(Mono.just(0L));
            when(tagRepository.count()).thenReturn(Mono.just(1L));
            when(articleRepository.sumViewsCount()).thenReturn(Mono.just(100L));
            when(articleRepository.sumLikesCount()).thenReturn(Mono.just(10L));

            StepVerifier.create(exportImportService.exportAll("admin"))
                    .assertNext(export -> {
                        assertThat(export.getVersion()).isEqualTo("2.0");
                        assertThat(export.getExportedBy()).isEqualTo("admin");
                        assertThat(export.getArticles()).hasSize(1);
                        assertThat(export.getTags()).hasSize(1);
                        assertThat(export.getStats().getTotalArticles()).isEqualTo(1);
                        assertThat(export.getStats().getPublishedArticles()).isEqualTo(1);
                        assertThat(export.getStats().getTotalViews()).isEqualTo(100);
                        assertThat(export.getMetadata()).containsEntry("format", "json");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should export with empty articles and tags")
        void shouldExportEmptyData() {
            when(articleRepository.findAll()).thenReturn(Flux.empty());
            when(tagRepository.findAll()).thenReturn(Flux.empty());
            when(articleRepository.countAll()).thenReturn(Mono.just(0L));
            when(articleRepository.countByStatus("PUBLISHED")).thenReturn(Mono.just(0L));
            when(articleRepository.countByStatus("DRAFT")).thenReturn(Mono.just(0L));
            when(articleRepository.countScheduled()).thenReturn(Mono.just(0L));
            when(tagRepository.count()).thenReturn(Mono.just(0L));
            when(articleRepository.sumViewsCount()).thenReturn(Mono.just(0L));
            when(articleRepository.sumLikesCount()).thenReturn(Mono.just(0L));

            StepVerifier.create(exportImportService.exportAll("admin"))
                    .assertNext(export -> {
                        assertThat(export.getArticles()).isEmpty();
                        assertThat(export.getTags()).isEmpty();
                        assertThat(export.getStats().getTotalArticles()).isZero();
                    })
                    .verifyComplete();
        }
    }

    // ==========================================
    // exportToJson
    // ==========================================
    @Nested
    @DisplayName("exportToJson")
    class ExportToJson {

        @Test
        @DisplayName("Should export to valid JSON string")
        void shouldExportToJson() {
            when(articleRepository.findAll()).thenReturn(Flux.empty());
            when(tagRepository.findAll()).thenReturn(Flux.empty());
            when(articleRepository.countAll()).thenReturn(Mono.just(0L));
            when(articleRepository.countByStatus("PUBLISHED")).thenReturn(Mono.just(0L));
            when(articleRepository.countByStatus("DRAFT")).thenReturn(Mono.just(0L));
            when(articleRepository.countScheduled()).thenReturn(Mono.just(0L));
            when(tagRepository.count()).thenReturn(Mono.just(0L));
            when(articleRepository.sumViewsCount()).thenReturn(Mono.just(0L));
            when(articleRepository.sumLikesCount()).thenReturn(Mono.just(0L));

            StepVerifier.create(exportImportService.exportToJson("admin"))
                    .assertNext(json -> {
                        assertThat(json).contains("\"version\"");
                        assertThat(json).contains("\"exportedBy\"");
                        assertThat(json).contains("admin");
                    })
                    .verifyComplete();
        }
    }

    // ==========================================
    // exportToMarkdown
    // ==========================================
    @Nested
    @DisplayName("exportToMarkdown")
    class ExportToMarkdown {

        @Test
        @DisplayName("Should export articles to markdown map")
        void shouldExportToMarkdown() {
            when(articleRepository.findAll()).thenReturn(Flux.just(testArticle));
            when(articleTagRepository.findTagIdsByArticleIds(any(Long[].class)))
                    .thenReturn(Flux.just(new long[]{1L, 10L}));
            when(tagRepository.findAllById(anyList())).thenReturn(Flux.just(testTag));

            StepVerifier.create(exportImportService.exportToMarkdown())
                    .assertNext(map -> {
                        assertThat(map).containsKey("test-article");
                        String md = map.get("test-article");
                        assertThat(md).contains("---");
                        assertThat(md).contains("title: \"Test Article\"");
                        assertThat(md).contains("slug: test-article");
                        assertThat(md).contains("status: PUBLISHED");
                        assertThat(md).contains("This is test content.");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty map when no articles exist")
        void shouldReturnEmptyMapWhenNoArticles() {
            when(articleRepository.findAll()).thenReturn(Flux.empty());

            StepVerifier.create(exportImportService.exportToMarkdown())
                    .assertNext(map -> assertThat(map).isEmpty())
                    .verifyComplete();
        }
    }

    // ==========================================
    // importFromJson
    // ==========================================
    @Nested
    @DisplayName("importFromJson")
    class ImportFromJson {

        @Test
        @DisplayName("Should import valid JSON data")
        void shouldImportValidJson() throws Exception {
            BlogExport export = BlogExport.builder()
                    .version("2.0")
                    .exportedAt(LocalDateTime.now())
                    .exportedBy("admin")
                    .stats(BlogStats.builder().totalArticles(1).build())
                    .articles(List.of(ArticleExportData.builder()
                            .slug("imported-article")
                            .title("Imported")
                            .content("Content")
                            .status("DRAFT")
                            .tagSlugs(Set.of("java"))
                            .build()))
                    .tags(List.of(TagExportData.builder()
                            .name("Java")
                            .slug("java")
                            .color("#E76F00")
                            .build()))
                    .metadata(Map.of())
                    .build();

            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            String json = mapper.writeValueAsString(export);

            // Mock tag import
            when(tagRepository.findBySlug("java")).thenReturn(Mono.just(testTag));

            // Mock article import
            when(articleRepository.findBySlug("imported-article")).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(100L);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleTagRepository.deleteByArticleId(anyLong())).thenReturn(Mono.empty());
            when(articleTagRepository.insertArticleTag(anyLong(), anyLong())).thenReturn(Mono.empty());

            StepVerifier.create(exportImportService.importFromJson(json, false))
                    .assertNext(result -> {
                        assertThat(result.articlesImported()).isEqualTo(1);
                        assertThat(result.articlesTotal()).isEqualTo(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return error for invalid JSON")
        void shouldErrorOnInvalidJson() {
            StepVerifier.create(exportImportService.importFromJson("not valid json{}", false))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }

    // ==========================================
    // importData
    // ==========================================
    @Nested
    @DisplayName("importData")
    class ImportData {

        @Test
        @DisplayName("Should skip existing articles when overwrite is false")
        void shouldSkipExistingArticlesWhenOverwriteFalse() {
            BlogExport export = BlogExport.builder()
                    .version("2.0")
                    .articles(List.of(ArticleExportData.builder()
                            .slug("existing-article")
                            .title("Existing")
                            .content("Content")
                            .tagSlugs(Collections.emptySet())
                            .build()))
                    .tags(List.of())
                    .build();

            when(articleRepository.findBySlug("existing-article")).thenReturn(Mono.just(testArticle));

            StepVerifier.create(exportImportService.importData(export, false))
                    .assertNext(result -> {
                        assertThat(result.articlesImported()).isZero();
                    })
                    .verifyComplete();

            verify(articleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should overwrite existing articles when overwrite is true")
        void shouldOverwriteExistingArticlesWhenOverwriteTrue() {
            BlogExport export = BlogExport.builder()
                    .version("2.0")
                    .articles(List.of(ArticleExportData.builder()
                            .slug("test-article")
                            .title("Updated Title")
                            .content("Updated Content")
                            .status("PUBLISHED")
                            .tagSlugs(Set.of("java"))
                            .build()))
                    .tags(List.of())
                    .build();

            when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleTagRepository.deleteByArticleId(anyLong())).thenReturn(Mono.empty());
            when(tagRepository.findBySlug("java")).thenReturn(Mono.just(testTag));
            when(articleTagRepository.insertArticleTag(anyLong(), anyLong())).thenReturn(Mono.empty());

            StepVerifier.create(exportImportService.importData(export, true))
                    .assertNext(result -> {
                        assertThat(result.articlesImported()).isEqualTo(1);
                    })
                    .verifyComplete();

            verify(articleRepository).save(any(Article.class));
        }

        @Test
        @DisplayName("Should create new tags during import")
        void shouldCreateNewTags() {
            BlogExport export = BlogExport.builder()
                    .version("2.0")
                    .articles(List.of())
                    .tags(List.of(TagExportData.builder()
                            .name("NewTag")
                            .slug("new-tag")
                            .description("Brand new tag")
                            .color("#FF0000")
                            .build()))
                    .build();

            when(tagRepository.findBySlug("new-tag")).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(200L);
            when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(exportImportService.importData(export, false))
                    .assertNext(result -> assertThat(result.articlesTotal()).isZero())
                    .verifyComplete();

            verify(tagRepository).save(any(Tag.class));
        }

        @Test
        @DisplayName("Should overwrite existing tags when overwrite is true")
        void shouldOverwriteExistingTags() {
            BlogExport export = BlogExport.builder()
                    .version("2.0")
                    .articles(List.of())
                    .tags(List.of(TagExportData.builder()
                            .name("Updated Java")
                            .slug("java")
                            .description("Updated description")
                            .color("#FF0000")
                            .build()))
                    .build();

            when(tagRepository.findBySlug("java")).thenReturn(Mono.just(testTag));
            when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(exportImportService.importData(export, true))
                    .assertNext(result -> assertThat(result.articlesTotal()).isZero())
                    .verifyComplete();

            verify(tagRepository).save(argThat(tag -> "Updated Java".equals(tag.getName().getDefault())));
        }

        @Test
        @DisplayName("Should handle empty export data gracefully")
        void shouldHandleEmptyExport() {
            BlogExport export = BlogExport.builder()
                    .version("2.0")
                    .articles(List.of())
                    .tags(List.of())
                    .build();

            StepVerifier.create(exportImportService.importData(export, false))
                    .assertNext(result -> {
                        assertThat(result.articlesImported()).isZero();
                        assertThat(result.articlesTotal()).isZero();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should create new article with default DRAFT status when status is null")
        void shouldCreateArticleWithDefaultDraftStatus() {
            BlogExport export = BlogExport.builder()
                    .version("2.0")
                    .articles(List.of(ArticleExportData.builder()
                            .slug("new-article")
                            .title("New Article")
                            .content("New Content")
                            .status(null)
                            .tagSlugs(Collections.emptySet())
                            .build()))
                    .tags(List.of())
                    .build();

            when(articleRepository.findBySlug("new-article")).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(300L);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(exportImportService.importData(export, false))
                    .assertNext(result -> assertThat(result.articlesImported()).isEqualTo(1))
                    .verifyComplete();

            verify(articleRepository).save(argThat(a -> "DRAFT".equals(a.getStatus())));
        }
    }
}
