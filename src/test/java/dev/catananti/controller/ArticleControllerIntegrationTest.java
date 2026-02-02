package dev.catananti.controller;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.ArticleService;
import dev.catananti.service.InteractionDeduplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Integration tests for ArticleController using WebTestClient.
 * Tests the full request/response cycle including JSON serialization.
 */
@ExtendWith(MockitoExtension.class)
class ArticleControllerIntegrationTest {

    private WebTestClient webTestClient;
    
    private final AtomicLong idGenerator = new AtomicLong(1000000000000000000L);

    @Mock
    private ArticleService articleService;
    
    @Mock
    private InteractionDeduplicationService deduplicationService;

    @InjectMocks
    private ArticleController articleController;
    
    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(articleController)
                .configureClient()
                .build();
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
    @DisplayName("GET /api/v1/articles should return paginated articles")
    void getPublishedArticles_ShouldReturnPaginatedResults() {
        // Given
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

        when(articleService.getPublishedArticles(anyInt(), anyInt(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(pageResponse));

        // When & Then
        webTestClient.get()
                .uri("/api/v1/articles?page=0&size=10")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.content").isArray()
                .jsonPath("$.content.length()").isEqualTo(2)
                .jsonPath("$.content[0].slug").isEqualTo("article-1")
                .jsonPath("$.content[1].slug").isEqualTo("article-2")
                .jsonPath("$.totalElements").isEqualTo(2)
                .jsonPath("$.totalPages").isEqualTo(1)
                .jsonPath("$.last").isEqualTo(true);
    }

    @Test
    @DisplayName("GET /api/v1/articles/{slug} should return article details")
    void getArticleBySlug_ShouldReturnArticleDetails() {
        // Given
        ArticleResponse article = createTestArticle("test-slug", "Test Article Title");

        when(articleService.getPublishedArticleBySlug("test-slug", null))
                .thenReturn(Mono.just(article));

        // When & Then
        webTestClient.get()
                .uri("/api/v1/articles/test-slug")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.slug").isEqualTo("test-slug")
                .jsonPath("$.title").isEqualTo("Test Article Title")
                .jsonPath("$.status").isEqualTo("PUBLISHED")
                .jsonPath("$.viewCount").isEqualTo(100)
                .jsonPath("$.likeCount").isEqualTo(50);
    }

    // searchArticles was removed from ArticleController — search now lives in SearchController

    @Test
    @DisplayName("GET /api/v1/articles with pagination should work correctly")
    void getPublishedArticles_WithPagination_ShouldReturnCorrectPage() {
        // Given
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

        when(articleService.getPublishedArticles(1, 5, null, null, null, null))
                .thenReturn(Mono.just(pageResponse));

        // When & Then
        webTestClient.get()
                .uri("/api/v1/articles?page=1&size=5")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page").isEqualTo(1)
                .jsonPath("$.size").isEqualTo(5)
                .jsonPath("$.totalPages").isEqualTo(2)
                .jsonPath("$.last").isEqualTo(true);
    }

    @Test
    @DisplayName("GET /api/v1/articles should use default pagination values")
    void getPublishedArticles_WithoutParams_ShouldUseDefaults() {
        // Given
        PageResponse<ArticleResponse> pageResponse = PageResponse.<ArticleResponse>builder()
                .content(List.of())
                .page(0)
                .size(10)
                .totalElements(0)
                .totalPages(0)
                .first(true)
                .last(true)
                .build();

        when(articleService.getPublishedArticles(0, 10, null, null, null, null))
                .thenReturn(Mono.just(pageResponse));

        // When & Then
        webTestClient.get()
                .uri("/api/v1/articles")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(10);
    }
}
