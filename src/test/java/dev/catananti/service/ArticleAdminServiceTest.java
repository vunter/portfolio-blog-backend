package dev.catananti.service;

import dev.catananti.dto.ArticleRequest;
import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.Tag;
import dev.catananti.entity.User;
import dev.catananti.exception.DuplicateResourceException;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.repository.TagRepository;
import dev.catananti.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArticleAdminServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;

    @Mock
    private SubscriberRepository subscriberRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private ArticleVersionService articleVersionService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CacheService cacheService;

    @Mock
    private IdService idService;

    @Mock
    private NotificationEventService notificationEventService;

    @Mock
    private HtmlSanitizerService htmlSanitizerService;

    @Mock
    private ArticleService articleService;

    @InjectMocks
    private ArticleAdminService articleAdminService;

    private Article testArticle;
    private ArticleResponse testArticleResponse;
    private Long articleId;

    @BeforeEach
    void setUp() {
        articleId = 1234567890123456L;

        testArticle = Article.builder()
                .id(articleId)
                .slug("test-article")
                .title("Test Article")
                .subtitle("Sub")
                .content("Some content for the test article")
                .excerpt("Excerpt")
                .status("DRAFT")
                .readingTimeMinutes(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        testArticleResponse = ArticleResponse.builder()
                .id(articleId.toString())
                .slug("test-article")
                .title("Test Article")
                .status("DRAFT")
                .build();
    }

    // ==================== getAllArticles ====================

    @Nested
    @DisplayName("getAllArticles")
    class GetAllArticles {

        @Test
        @DisplayName("Should return all articles without status filter")
        void shouldReturnAllArticlesWithoutStatusFilter() {
            // Given
            when(articleRepository.findAllOrderByCreatedAtDesc(10, 0))
                    .thenReturn(Flux.just(testArticle));
            when(articleRepository.countAll()).thenReturn(Mono.just(1L));
            when(articleService.enrichArticlesWithMetadata(anyList()))
                    .thenReturn(Mono.just(List.of(testArticle)));
            when(articleService.mapToResponse(testArticle))
                    .thenReturn(testArticleResponse);

            // When & Then
            StepVerifier.create(articleAdminService.getAllArticles(0, 10, null))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                        assertThat(page.getTotalElements()).isEqualTo(1);
                        assertThat(page.getPage()).isZero();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should filter articles by status")
        void shouldFilterArticlesByStatus() {
            // Given
            when(articleRepository.findByStatusOrderByCreatedAtDesc("PUBLISHED", 10, 0))
                    .thenReturn(Flux.just(testArticle));
            when(articleRepository.countByStatus("PUBLISHED")).thenReturn(Mono.just(1L));
            when(articleService.enrichArticlesWithMetadata(anyList()))
                    .thenReturn(Mono.just(List.of(testArticle)));
            when(articleService.mapToResponse(testArticle))
                    .thenReturn(testArticleResponse);

            // When & Then
            StepVerifier.create(articleAdminService.getAllArticles(0, 10, "published"))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty page when no articles")
        void shouldReturnEmptyPage() {
            // Given
            when(articleRepository.findAllOrderByCreatedAtDesc(10, 0))
                    .thenReturn(Flux.empty());
            when(articleRepository.countAll()).thenReturn(Mono.just(0L));
            when(articleService.enrichArticlesWithMetadata(anyList()))
                    .thenReturn(Mono.just(List.of()));

            // When & Then
            StepVerifier.create(articleAdminService.getAllArticles(0, 10, null))
                    .assertNext(page -> {
                        assertThat(page.getContent()).isEmpty();
                        assertThat(page.getTotalElements()).isZero();
                    })
                    .verifyComplete();
        }
    }

    // ==================== getArticleById ====================

    @Nested
    @DisplayName("getArticleById")
    class GetArticleById {

        @Test
        @DisplayName("Should return article when found")
        void shouldReturnArticleWhenFound() {
            // Given
            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(articleService.enrichArticleWithMetadata(testArticle))
                    .thenReturn(Mono.just(testArticle));
            when(articleService.mapToResponse(testArticle))
                    .thenReturn(testArticleResponse);

            // When & Then
            StepVerifier.create(articleAdminService.getArticleById(articleId))
                    .assertNext(response -> {
                        assertThat(response.getSlug()).isEqualTo("test-article");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            // Given
            when(articleRepository.findById(999L)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(articleAdminService.getArticleById(999L))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==================== createArticle ====================

    @Nested
    @DisplayName("createArticle")
    class CreateArticle {

        private ArticleRequest draftRequest;

        @BeforeEach
        void setUpRequest() {
            draftRequest = ArticleRequest.builder()
                    .slug("new-article")
                    .title("New Article")
                    .subtitle("Subtitle")
                    .content("This is the content of the new article with enough words")
                    .excerpt("Excerpt")
                    .status("DRAFT")
                    .tagSlugs(List.of())
                    .build();
        }

        @Test
        @DisplayName("Should create draft article successfully")
        void shouldCreateDraftArticle() {
            // Given
            when(articleRepository.existsBySlug("new-article")).thenReturn(Mono.just(false));
            when(idService.nextId()).thenReturn(555L);
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(testArticle));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(testArticle));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(testArticleResponse);

            // When & Then
            StepVerifier.create(articleAdminService.createArticle(draftRequest))
                    .assertNext(response -> {
                        assertThat(response.getSlug()).isEqualTo("test-article");
                    })
                    .verifyComplete();

            verify(articleRepository).save(any(Article.class));
        }

        @Test
        @DisplayName("Should throw DuplicateResourceException for duplicate slug")
        void shouldThrowOnDuplicateSlug() {
            // Given
            when(articleRepository.existsBySlug("new-article")).thenReturn(Mono.just(true));

            // When & Then
            StepVerifier.create(articleAdminService.createArticle(draftRequest))
                    .expectError(DuplicateResourceException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should set SCHEDULED status when scheduledAt is provided")
        void shouldSetScheduledStatus() {
            // Given
            ArticleRequest scheduledRequest = ArticleRequest.builder()
                    .slug("scheduled-article")
                    .title("Scheduled")
                    .content("Content here for the scheduled article enough words now")
                    .status("DRAFT")
                    .scheduledAt(LocalDateTime.now().plusDays(1))
                    .tagSlugs(List.of())
                    .build();

            when(articleRepository.existsBySlug("scheduled-article")).thenReturn(Mono.just(false));
            when(idService.nextId()).thenReturn(556L);
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));

            Article savedArticle = Article.builder()
                    .id(556L)
                    .slug("scheduled-article")
                    .title("Scheduled")
                    .status("SCHEDULED")
                    .build();
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(savedArticle));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(savedArticle));

            ArticleResponse scheduledResponse = ArticleResponse.builder()
                    .slug("scheduled-article")
                    .status("SCHEDULED")
                    .build();
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(scheduledResponse);

            // When & Then
            StepVerifier.create(articleAdminService.createArticle(scheduledRequest))
                    .assertNext(response -> {
                        assertThat(response.getStatus()).isEqualTo("SCHEDULED");
                    })
                    .verifyComplete();
        }
    }

    // ==================== updateArticle ====================

    @Nested
    @DisplayName("updateArticle")
    class UpdateArticle {

        @Test
        @DisplayName("Should update article successfully")
        void shouldUpdateArticle() {
            // Given
            ArticleRequest updateRequest = ArticleRequest.builder()
                    .slug("test-article")
                    .title("Updated Title")
                    .content("Updated content with enough words to be valid content here")
                    .status("DRAFT")
                    .build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(testArticle));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(testArticle));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(testArticleResponse);

            // When & Then
            StepVerifier.create(articleAdminService.updateArticle(articleId, updateRequest))
                    .assertNext(response -> {
                        assertThat(response.getSlug()).isEqualTo("test-article");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when article not found")
        void shouldThrowWhenNotFound() {
            // Given
            ArticleRequest updateRequest = ArticleRequest.builder()
                    .slug("test")
                    .title("Title")
                    .content("Content")
                    .build();

            when(articleRepository.findById(999L)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(articleAdminService.updateArticle(999L, updateRequest))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==================== deleteArticle ====================

    @Nested
    @DisplayName("deleteArticle")
    class DeleteArticle {

        @Test
        @DisplayName("Should cascade delete article and related data")
        void shouldCascadeDeleteArticle() {
            // Given
            var dbClient = mock(org.springframework.r2dbc.core.DatabaseClient.class);
            var execSpec = mock(org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec.class);
            var fetchSpec = mock(org.springframework.r2dbc.core.FetchSpec.class);

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(r2dbcTemplate.getDatabaseClient()).thenReturn(dbClient);
            when(dbClient.sql(anyString())).thenReturn(execSpec);
            when(execSpec.bind(eq("articleId"), eq(articleId))).thenReturn(execSpec);
            when(execSpec.fetch()).thenReturn(fetchSpec);
            when(fetchSpec.rowsUpdated()).thenReturn(Mono.just(1L));
            when(articleRepository.deleteById(articleId)).thenReturn(Mono.empty());
            when(cacheService.delete(anyString())).thenReturn(Mono.just(true));

            // When & Then
            StepVerifier.create(articleAdminService.deleteArticle(articleId))
                    .verifyComplete();

            verify(articleRepository).deleteById(articleId);
        }
    }

    // ==================== publishArticle ====================

    @Nested
    @DisplayName("publishArticle")
    class PublishArticle {

        @Test
        @DisplayName("Should publish article and change status")
        void shouldPublishArticle() {
            // Given
            Article draftArticle = Article.builder()
                    .id(articleId)
                    .slug("test-article")
                    .title("Test Article")
                    .status("DRAFT")
                    .build();

            Article publishedArticle = Article.builder()
                    .id(articleId)
                    .slug("test-article")
                    .title("Test Article")
                    .status("PUBLISHED")
                    .publishedAt(LocalDateTime.now())
                    .build();

            ArticleResponse publishedResponse = ArticleResponse.builder()
                    .id(articleId.toString())
                    .slug("test-article")
                    .status("PUBLISHED")
                    .build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(draftArticle));
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(publishedArticle));
            when(cacheService.delete(anyString())).thenReturn(Mono.just(true));
            when(subscriberRepository.findAllConfirmed()).thenReturn(Flux.empty());
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(publishedArticle));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(publishedResponse);

            // When & Then
            StepVerifier.create(articleAdminService.publishArticle(articleId))
                    .assertNext(response -> {
                        assertThat(response.getStatus()).isEqualTo("PUBLISHED");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when article not found")
        void shouldThrowWhenNotFound() {
            when(articleRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(articleAdminService.publishArticle(999L))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==================== unpublishArticle ====================

    @Nested
    @DisplayName("unpublishArticle")
    class UnpublishArticle {

        @Test
        @DisplayName("Should unpublish article and set status to DRAFT")
        void shouldUnpublishArticle() {
            // Given
            Article publishedArticle = Article.builder()
                    .id(articleId)
                    .slug("test-article")
                    .status("PUBLISHED")
                    .build();

            ArticleResponse draftResponse = ArticleResponse.builder()
                    .id(articleId.toString())
                    .slug("test-article")
                    .status("DRAFT")
                    .build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(publishedArticle));
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(publishedArticle));
            when(cacheService.delete(anyString())).thenReturn(Mono.just(true));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(publishedArticle));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(draftResponse);

            // When & Then
            StepVerifier.create(articleAdminService.unpublishArticle(articleId))
                    .assertNext(response -> {
                        assertThat(response.getStatus()).isEqualTo("DRAFT");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when article not found")
        void shouldThrowWhenNotFound() {
            when(articleRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(articleAdminService.unpublishArticle(999L))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==================== archiveArticle ====================

    @Nested
    @DisplayName("archiveArticle")
    class ArchiveArticle {

        @Test
        @DisplayName("Should archive article and set status to ARCHIVED")
        void shouldArchiveArticle() {
            // Given
            ArticleResponse archivedResponse = ArticleResponse.builder()
                    .id(articleId.toString())
                    .slug("test-article")
                    .status("ARCHIVED")
                    .build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(testArticle));
            when(cacheService.delete(anyString())).thenReturn(Mono.just(true));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(testArticle));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(archivedResponse);

            // When & Then
            StepVerifier.create(articleAdminService.archiveArticle(articleId))
                    .assertNext(response -> {
                        assertThat(response.getStatus()).isEqualTo("ARCHIVED");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when article not found")
        void shouldThrowWhenNotFound() {
            when(articleRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(articleAdminService.archiveArticle(999L))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==================== calculateReadingTime (via createArticle) ====================

    @Nested
    @DisplayName("calculateReadingTime")
    class CalculateReadingTime {

        /**
         * calculateReadingTime is private, so we test it indirectly through createArticle.
         * We verify the readingTimeMinutes field on the saved Article.
         */

        @Test
        @DisplayName("Should calculate reading time for short content (< 200 words = 1 min)")
        void shouldReturnOneMinuteForShortContent() {
            // Given — ~10 words
            ArticleRequest request = ArticleRequest.builder()
                    .slug("short")
                    .title("Short")
                    .content("word ".repeat(10).trim())
                    .status("DRAFT")
                    .tagSlugs(List.of())
                    .build();

            when(articleRepository.existsBySlug("short")).thenReturn(Mono.just(false));
            when(idService.nextId()).thenReturn(700L);
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenAnswer(inv -> {
                        Article a = inv.getArgument(0);
                        return ArticleResponse.builder()
                                .readingTimeMinutes(a.getReadingTimeMinutes())
                                .build();
                    });

            // When & Then
            StepVerifier.create(articleAdminService.createArticle(request))
                    .assertNext(response -> {
                        assertThat(response.getReadingTimeMinutes()).isEqualTo(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should calculate reading time for medium content (~600 words = 3 min)")
        void shouldReturnThreeMinutesForMediumContent() {
            // Given — ~600 words
            ArticleRequest request = ArticleRequest.builder()
                    .slug("medium")
                    .title("Medium")
                    .content("word ".repeat(600).trim())
                    .status("DRAFT")
                    .tagSlugs(List.of())
                    .build();

            when(articleRepository.existsBySlug("medium")).thenReturn(Mono.just(false));
            when(idService.nextId()).thenReturn(701L);
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenAnswer(inv -> {
                        Article a = inv.getArgument(0);
                        return ArticleResponse.builder()
                                .readingTimeMinutes(a.getReadingTimeMinutes())
                                .build();
                    });

            // When & Then
            StepVerifier.create(articleAdminService.createArticle(request))
                    .assertNext(response -> {
                        assertThat(response.getReadingTimeMinutes()).isEqualTo(3);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should calculate reading time for long content (~2000 words = 10 min)")
        void shouldReturnTenMinutesForLongContent() {
            // Given — ~2000 words
            ArticleRequest request = ArticleRequest.builder()
                    .slug("long-read")
                    .title("Long Read")
                    .content("word ".repeat(2000).trim())
                    .status("DRAFT")
                    .tagSlugs(List.of())
                    .build();

            when(articleRepository.existsBySlug("long-read")).thenReturn(Mono.just(false));
            when(idService.nextId()).thenReturn(702L);
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenAnswer(inv -> {
                        Article a = inv.getArgument(0);
                        return ArticleResponse.builder()
                                .readingTimeMinutes(a.getReadingTimeMinutes())
                                .build();
                    });

            // When & Then
            StepVerifier.create(articleAdminService.createArticle(request))
                    .assertNext(response -> {
                        assertThat(response.getReadingTimeMinutes()).isEqualTo(10);
                    })
                    .verifyComplete();
        }
    }
}
