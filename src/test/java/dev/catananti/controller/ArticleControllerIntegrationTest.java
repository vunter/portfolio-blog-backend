package dev.catananti.controller;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.ArticleService;
import dev.catananti.service.InteractionDeduplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ArticleController.
 * Tests controller logic by calling methods directly with StepVerifier.
 */
@ExtendWith(MockitoExtension.class)
class ArticleControllerIntegrationTest {

    private final AtomicLong idGenerator = new AtomicLong(1000000000000000000L);

    @Mock
    private ArticleService articleService;

    @Mock
    private InteractionDeduplicationService deduplicationService;

    private ArticleController articleController;

    @BeforeEach
    void setUp() {
        articleController = new ArticleController(articleService, Optional.of(deduplicationService));
    }

    private ArticleResponse createTestArticle(String slug, String title) {
        return ArticleResponse.builder()
                .id(String.valueOf(idGenerator.incrementAndGet()))
                .slug(slug)
                .title(title)
                .content("Test content for " + title)
                .excerpt("Excerpt for " + title)
                .status("PUBLISHED")
                .viewCount(100)
                .likeCount(50)
                .tags(Set.of())
                .createdAt(LocalDateTime.now())
                .publishedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("getPublishedArticles should return paginated articles")
    void getPublishedArticles_ShouldReturnPaginatedResults() {
        ArticleResponse article1 = createTestArticle("article-1", "First Article");
        ArticleResponse article2 = createTestArticle("article-2", "Second Article");

        PageResponse<ArticleResponse> pageResponse = PageResponse.<ArticleResponse>builder()
                .content(List.of(article1, article2))
                .page(0)
                .size(10)
                .totalElements(2)
                .totalPages(1)
                .first(true)
                .last(true)
                .build();

        when(articleService.getPublishedArticles(eq(0), eq(10), any(), any(), any(), any()))
                .thenReturn(Mono.just(pageResponse));

        StepVerifier.create(articleController.getPublishedArticles(0, 10, null, null, null, null))
                .assertNext(result -> {
                    assertThat(result.getContent()).hasSize(2);
                    assertThat(result.getContent().get(0).getSlug()).isEqualTo("article-1");
                    assertThat(result.getContent().get(1).getSlug()).isEqualTo("article-2");
                    assertThat(result.getTotalElements()).isEqualTo(2);
                    assertThat(result.getTotalPages()).isEqualTo(1);
                    assertThat(result.isLast()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getArticleBySlug should return article details")
    void getArticleBySlug_ShouldReturnArticleDetails() {
        ArticleResponse article = createTestArticle("test-slug", "Test Article Title");

        when(articleService.getPublishedArticleBySlug("test-slug", null))
                .thenReturn(Mono.just(article));

        StepVerifier.create(articleController.getArticleBySlug("test-slug", null))
                .assertNext(result -> {
                    assertThat(result.getSlug()).isEqualTo("test-slug");
                    assertThat(result.getTitle()).isEqualTo("Test Article Title");
                    assertThat(result.getStatus()).isEqualTo("PUBLISHED");
                    assertThat(result.getViewCount()).isEqualTo(100);
                    assertThat(result.getLikeCount()).isEqualTo(50);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getPublishedArticles with pagination should return correct page")
    void getPublishedArticles_WithPagination_ShouldReturnCorrectPage() {
        ArticleResponse article = createTestArticle("page-2-article", "Second Page Article");

        PageResponse<ArticleResponse> pageResponse = PageResponse.<ArticleResponse>builder()
                .content(List.of(article))
                .page(1)
                .size(5)
                .totalElements(6)
                .totalPages(2)
                .first(false)
                .last(true)
                .build();

        when(articleService.getPublishedArticles(eq(1), eq(5), any(), any(), any(), any()))
                .thenReturn(Mono.just(pageResponse));

        StepVerifier.create(articleController.getPublishedArticles(1, 5, null, null, null, null))
                .assertNext(result -> {
                    assertThat(result.getPage()).isEqualTo(1);
                    assertThat(result.getSize()).isEqualTo(5);
                    assertThat(result.getTotalPages()).isEqualTo(2);
                    assertThat(result.isLast()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getPublishedArticles should use default pagination values")
    void getPublishedArticles_WithoutParams_ShouldUseDefaults() {
        PageResponse<ArticleResponse> pageResponse = PageResponse.<ArticleResponse>builder()
                .content(List.of())
                .page(0)
                .size(10)
                .totalElements(0)
                .totalPages(0)
                .first(true)
                .last(true)
                .build();

        when(articleService.getPublishedArticles(eq(0), eq(10), any(), any(), any(), any()))
                .thenReturn(Mono.just(pageResponse));

        StepVerifier.create(articleController.getPublishedArticles(0, 10, null, null, null, null))
                .assertNext(result -> {
                    assertThat(result.getPage()).isEqualTo(0);
                    assertThat(result.getSize()).isEqualTo(10);
                })
                .verifyComplete();
    }
}
