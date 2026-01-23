package dev.catananti.service;

import dev.catananti.config.ResilienceConfig;
import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.User;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.TagRepository;
import dev.catananti.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArticleServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ArticleTranslationService articleTranslationService;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private BlogMetrics blogMetrics;

    @Mock
    private ResilienceConfig resilience;

    @InjectMocks
    private ArticleService articleService;

    private Article testArticle;
    private Long articleId;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        articleId = 1234567890123456789L;
        testArticle = Article.builder()
                .id(articleId)
                .slug("test-article")
                .title("Test Article")
                .subtitle("Test Subtitle")
                .content("This is test content for the article.")
                .excerpt("Test excerpt")
                .status("PUBLISHED")
                .viewsCount(10)
                .likesCount(5)
                .readingTimeMinutes(3)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .publishedAt(LocalDateTime.now())
                .build();

        // Mock resilience config
        lenient().when(resilience.getDatabaseTimeout()).thenReturn(java.time.Duration.ofSeconds(10));

        // Mock CommentRepository
        lenient().when(commentRepository.countApprovedByArticleId(anyLong())).thenReturn(Mono.just(0L));

        // Mock R2dbcEntityTemplate -> DatabaseClient chain for batch operations
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<Object> rowsFetchSpec = mock(RowsFetchSpec.class);
        lenient().when(r2dbcTemplate.getDatabaseClient()).thenReturn(databaseClient);
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
        lenient().when(rowsFetchSpec.all()).thenReturn(Flux.empty());
    }

    @Test
    @DisplayName("Should return published articles paginated")
    void getPublishedArticles_ShouldReturnPagedArticles() {
        // Given
        when(articleRepository.findByStatusOrderByPublishedAtDesc(eq("PUBLISHED"), anyInt(), anyInt()))
                .thenReturn(Flux.just(testArticle));
        when(articleRepository.countByStatus("PUBLISHED"))
                .thenReturn(Mono.just(1L));

        // When
        Mono<PageResponse<ArticleResponse>> result = articleService.getPublishedArticles(0, 10);

        // Then
        StepVerifier.create(result)
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(1);
                    assertThat(page.getTotalElements()).isEqualTo(1);
                    assertThat(page.getPage()).isEqualTo(0);
                    assertThat(page.getSize()).isEqualTo(10);
                    assertThat(page.getContent().getFirst().getSlug()).isEqualTo("test-article");
                })
                .verifyComplete();

        verify(articleRepository).findByStatusOrderByPublishedAtDesc("PUBLISHED", 10, 0);
        verify(articleRepository).countByStatus("PUBLISHED");
    }

    @Test
    @DisplayName("Should return article by slug")
    void getPublishedArticleBySlug_ShouldReturnArticle() {
        // Given
        when(articleRepository.findBySlugAndStatus("test-article", "PUBLISHED"))
                .thenReturn(Mono.just(testArticle));
        when(tagRepository.findByArticleId(articleId))
                .thenReturn(Flux.empty());

        // When
        Mono<ArticleResponse> result = articleService.getPublishedArticleBySlug("test-article");

        // Then
        StepVerifier.create(result)
                .assertNext(article -> {
                    assertThat(article.getSlug()).isEqualTo("test-article");
                    assertThat(article.getTitle()).isEqualTo("Test Article");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when article not found")
    void getPublishedArticleBySlug_ShouldThrowWhenNotFound() {
        // Given
        when(articleRepository.findBySlugAndStatus("non-existent", "PUBLISHED"))
                .thenReturn(Mono.empty());

        // When
        Mono<ArticleResponse> result = articleService.getPublishedArticleBySlug("non-existent");

        // Then
        StepVerifier.create(result)
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should increment views count")
    void incrementViews_ShouldIncrementViewsCount() {
        // Given
        when(articleRepository.incrementViewsBySlug("test-article"))
                .thenReturn(Mono.empty());

        // When
        Mono<Void> result = articleService.incrementViews("test-article");

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        verify(articleRepository).incrementViewsBySlug("test-article");
    }

    @Test
    @DisplayName("Should increment likes count")
    void likeArticle_ShouldIncrementLikesCount() {
        // Given
        when(articleRepository.incrementLikesBySlug("test-article"))
                .thenReturn(Mono.empty());

        // When
        Mono<Void> result = articleService.likeArticle("test-article");

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        verify(articleRepository).incrementLikesBySlug("test-article");
    }

    // ==================== ADDED TESTS ====================

    @Test
    @DisplayName("Should return published articles sorted by views")
    void getPublishedArticles_ShouldSortByViews() {
        when(articleRepository.findByStatusOrderByViewsCountDesc(eq("PUBLISHED"), anyInt(), anyInt()))
                .thenReturn(Flux.just(testArticle));
        when(articleRepository.countByStatus("PUBLISHED"))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(articleService.getPublishedArticles(0, 10, null, "viewCount"))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(1);
                })
                .verifyComplete();

        verify(articleRepository).findByStatusOrderByViewsCountDesc("PUBLISHED", 10, 0);
    }

    @Test
    @DisplayName("Should return published articles with date range filter")
    void getPublishedArticles_ShouldFilterByDateRange() {
        when(articleRepository.findByStatusAndDateRangeOrderByPublishedAtDesc(
                eq("PUBLISHED"), any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(Flux.just(testArticle));
        when(articleRepository.countByStatusAndDateRange(
                eq("PUBLISHED"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(articleService.getPublishedArticles(0, 10, null, null,
                java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31)))
                .assertNext(page -> assertThat(page.getContent()).hasSize(1))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return published articles with locale - en passes through")
    void getPublishedArticles_ShouldPassThroughEnLocale() {
        when(articleRepository.findByStatusOrderByPublishedAtDesc(eq("PUBLISHED"), anyInt(), anyInt()))
                .thenReturn(Flux.just(testArticle));
        when(articleRepository.countByStatus("PUBLISHED"))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(articleService.getPublishedArticles(0, 10, "en"))
                .assertNext(page -> assertThat(page.getContent()).hasSize(1))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return published articles with non-en locale")
    void getPublishedArticles_ShouldApplyTranslation() {
        when(articleRepository.findByStatusOrderByPublishedAtDesc(eq("PUBLISHED"), anyInt(), anyInt()))
                .thenReturn(Flux.just(testArticle));
        when(articleRepository.countByStatus("PUBLISHED"))
                .thenReturn(Mono.just(1L));
        when(articleTranslationService.applyTranslation(any(Article.class), eq("pt")))
                .thenReturn(Mono.just(testArticle));

        StepVerifier.create(articleService.getPublishedArticles(0, 10, "pt"))
                .assertNext(page -> assertThat(page.getContent()).hasSize(1))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get article by slug with locale")
    void getPublishedArticleBySlug_WithLocale_ShouldApplyTranslation() {
        when(articleRepository.findBySlugAndStatus("test-article", "PUBLISHED"))
                .thenReturn(Mono.just(testArticle));
        when(tagRepository.findByArticleId(articleId))
                .thenReturn(Flux.empty());
        when(articleTranslationService.applyTranslation(any(Article.class), eq("de")))
                .thenReturn(Mono.just(testArticle));

        StepVerifier.create(articleService.getPublishedArticleBySlug("test-article", "de"))
                .assertNext(article -> assertThat(article.getSlug()).isEqualTo("test-article"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get like count for article")
    void getLikeCount_ShouldReturnCount() {
        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));

        StepVerifier.create(articleService.getLikeCount("test-article"))
                .assertNext(count -> assertThat(count).isEqualTo(5))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return 0 like count when article not found")
    void getLikeCount_ShouldReturnZero_WhenNotFound() {
        when(articleRepository.findBySlug("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(articleService.getLikeCount("ghost"))
                .assertNext(count -> assertThat(count).isEqualTo(0))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return 0 like count when likesCount is null")
    void getLikeCount_ShouldReturnZero_WhenNull() {
        Article noLikes = Article.builder().id(2L).slug("no-likes").likesCount(null).build();
        when(articleRepository.findBySlug("no-likes")).thenReturn(Mono.just(noLikes));

        StepVerifier.create(articleService.getLikeCount("no-likes"))
                .assertNext(count -> assertThat(count).isEqualTo(0))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search articles by query")
    void searchArticles_ShouldReturnResults() {
        when(articleRepository.searchByStatusAndQuery("PUBLISHED", "test", 10, 0))
                .thenReturn(Flux.just(testArticle));
        when(articleRepository.countSearchByStatusAndQuery("PUBLISHED", "test"))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(articleService.searchArticles("test", 0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(1);
                    assertThat(page.getTotalElements()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search articles with locale")
    void searchArticles_WithLocale_ShouldApplyTranslation() {
        when(articleRepository.searchByStatusAndQuery("PUBLISHED", "test", 10, 0))
                .thenReturn(Flux.just(testArticle));
        when(articleRepository.countSearchByStatusAndQuery("PUBLISHED", "test"))
                .thenReturn(Mono.just(1L));
        when(articleTranslationService.applyTranslation(any(Article.class), eq("fr")))
                .thenReturn(Mono.just(testArticle));

        StepVerifier.create(articleService.searchArticles("test", 0, 10, "fr"))
                .assertNext(page -> assertThat(page.getContent()).hasSize(1))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get articles by tag")
    void getArticlesByTag_ShouldReturnResults() {
        when(articleRepository.findByTagSlugAndStatus("java", "PUBLISHED", 10, 0))
                .thenReturn(Flux.just(testArticle));
        when(articleRepository.countByTagSlugAndStatus("java", "PUBLISHED"))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(articleService.getArticlesByTag("java", 0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get articles by tag with locale")
    void getArticlesByTag_WithLocale_ShouldApplyTranslation() {
        when(articleRepository.findByTagSlugAndStatus("java", "PUBLISHED", 10, 0))
                .thenReturn(Flux.just(testArticle));
        when(articleRepository.countByTagSlugAndStatus("java", "PUBLISHED"))
                .thenReturn(Mono.just(1L));
        when(articleTranslationService.applyTranslation(any(Article.class), eq("es")))
                .thenReturn(Mono.just(testArticle));

        StepVerifier.create(articleService.getArticlesByTag("java", 0, 10, "es"))
                .assertNext(page -> assertThat(page.getContent()).hasSize(1))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get related articles by slug")
    void getRelatedArticles_ShouldReturnResults() {
        Article related = Article.builder()
                .id(2L).slug("related-article").title("Related")
                .status("PUBLISHED").viewsCount(0).likesCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .publishedAt(LocalDateTime.now())
                .build();

        when(articleRepository.findBySlugAndStatus("test-article", "PUBLISHED"))
                .thenReturn(Mono.just(testArticle));
        when(articleRepository.findRelatedArticles(articleId, 3))
                .thenReturn(Flux.just(related));
        when(articleRepository.findRecentPublishedExcluding(articleId, 3))
                .thenReturn(Flux.empty());
        when(tagRepository.findByArticleId(2L))
                .thenReturn(Flux.empty());
        when(commentRepository.countApprovedByArticleId(2L))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(articleService.getRelatedArticles("test-article", 3).collectList())
                .assertNext(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.getFirst().getSlug()).isEqualTo("related-article");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw when getting related articles for non-existent slug")
    void getRelatedArticles_ShouldThrow_WhenNotFound() {
        when(articleRepository.findBySlugAndStatus("ghost", "PUBLISHED"))
                .thenReturn(Mono.empty());

        StepVerifier.create(articleService.getRelatedArticles("ghost", 3).collectList())
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return all published articles for feed")
    void findAllPublishedForFeed_ShouldReturnArticles() {
        when(articleRepository.findAllPublishedOrderByPublishedAtDesc())
                .thenReturn(Flux.just(testArticle));

        StepVerifier.create(articleService.findAllPublishedForFeed().collectList())
                .assertNext(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.getFirst().getSlug()).isEqualTo("test-article");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should enrich article with metadata")
    void enrichArticleWithMetadata_ShouldSetFields() {
        User author = User.builder().id(100L).name("Author Name").build();
        testArticle.setAuthorId(100L);

        when(tagRepository.findByArticleId(articleId)).thenReturn(Flux.empty());
        when(userRepository.findById(100L)).thenReturn(Mono.just(author));
        when(commentRepository.countApprovedByArticleId(articleId)).thenReturn(Mono.just(5L));

        StepVerifier.create(articleService.enrichArticleWithMetadata(testArticle))
                .assertNext(article -> {
                    assertThat(article.getAuthorName()).isEqualTo("Author Name");
                    assertThat(article.getCommentCount()).isEqualTo(5);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should enrich article with Unknown author when authorId is null")
    void enrichArticleWithMetadata_ShouldSetUnknownAuthor() {
        testArticle.setAuthorId(null);

        when(tagRepository.findByArticleId(articleId)).thenReturn(Flux.empty());
        when(commentRepository.countApprovedByArticleId(articleId)).thenReturn(Mono.just(0L));

        StepVerifier.create(articleService.enrichArticleWithMetadata(testArticle))
                .assertNext(article -> assertThat(article.getAuthorName()).isEqualTo("Unknown"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should enrich empty list of articles")
    void enrichArticlesWithMetadata_EmptyList() {
        StepVerifier.create(articleService.enrichArticlesWithMetadata(new java.util.ArrayList<>()))
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("Should map article to response correctly")
    void mapToResponse_ShouldMapAllFields() {
        testArticle.setTags(java.util.Set.of());
        testArticle.setAuthorName("Test Author");
        testArticle.setCommentCount(3);

        ArticleResponse response = articleService.mapToResponse(testArticle);

        assertThat(response.getSlug()).isEqualTo("test-article");
        assertThat(response.getTitle()).isEqualTo("Test Article");
        assertThat(response.getAuthor().getName()).isEqualTo("Test Author");
        assertThat(response.getCommentCount()).isEqualTo(3);
        assertThat(response.getViewCount()).isEqualTo(10);
        assertThat(response.getLikeCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should return empty page for published articles with no results")
    void getPublishedArticles_ShouldReturnEmptyPage() {
        when(articleRepository.findByStatusOrderByPublishedAtDesc(eq("PUBLISHED"), anyInt(), anyInt()))
                .thenReturn(Flux.empty());
        when(articleRepository.countByStatus("PUBLISHED"))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(articleService.getPublishedArticles(0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).isEmpty();
                    assertThat(page.getTotalElements()).isEqualTo(0);
                })
                .verifyComplete();
    }

}
