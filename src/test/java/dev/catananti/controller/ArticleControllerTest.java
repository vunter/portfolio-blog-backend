package dev.catananti.controller;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.service.ArticleService;
import dev.catananti.service.InteractionDeduplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ArticleController using Mockito.
 */
@ExtendWith(MockitoExtension.class)
class ArticleControllerTest {

    @Mock
    private ArticleService articleService;
    
    @Mock
    private InteractionDeduplicationService deduplicationService;
    
    @Mock
    private ServerHttpRequest mockRequest;

    // F-065: Manual construction needed since deduplicationService is Optional<>
    private ArticleController articleController;

    @BeforeEach
    void setUp() {
        articleController = new ArticleController(articleService, Optional.of(deduplicationService));
    }

    @Test
    @DisplayName("Should return published articles")
    void getPublishedArticles_ShouldReturnPagedArticles() {
        // Given
        ArticleResponse article = ArticleResponse.builder()
                .id("1234567890123456789")
                .slug("test-article")
                .title("Test Article")
                .content("Content")
                .status("PUBLISHED")
                .viewCount(10)
                .likeCount(5)
                .tags(Set.of())
                .createdAt(LocalDateTime.now())
                .build();

        PageResponse<ArticleResponse> pageResponse = PageResponse.<ArticleResponse>builder()
                .content(List.of(article))
                .page(0)
                .size(10)
                .totalElements(1)
                .totalPages(1)
                .first(true)
                .last(true)
                .build();

        when(articleService.getPublishedArticles(0, 10, null, null, null, null))
                .thenReturn(Mono.just(pageResponse));

        // When & Then
        StepVerifier.create(articleController.getPublishedArticles(0, 10, null, null, null, null))
                .assertNext(response -> {
                    assertThat(response.getContent()).hasSize(1);
                    assertThat(response.getContent().getFirst().getSlug()).isEqualTo("test-article");
                    assertThat(response.getTotalElements()).isEqualTo(1);
                })
                .verifyComplete();

        verify(articleService).getPublishedArticles(0, 10, null, null, null, null);
    }

    @Test
    @DisplayName("Should return article by slug")
    void getArticleBySlug_ShouldReturnArticle() {
        // Given
        ArticleResponse article = ArticleResponse.builder()
                .id("987654321098765432")
                .slug("test-article")
                .title("Test Article")
                .content("Content")
                .status("PUBLISHED")
                .tags(Set.of())
                .createdAt(LocalDateTime.now())
                .build();

        when(articleService.getPublishedArticleBySlug("test-article", null))
                .thenReturn(Mono.just(article));

        // When & Then
        StepVerifier.create(articleController.getArticleBySlug("test-article", null))
                .assertNext(response -> {
                    assertThat(response.getSlug()).isEqualTo("test-article");
                    assertThat(response.getTitle()).isEqualTo("Test Article");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error when article not found")
    void getArticleBySlug_ShouldReturnError_WhenNotFound() {
        // Given
        when(articleService.getPublishedArticleBySlug("non-existent", null))
                .thenReturn(Mono.error(new ResourceNotFoundException("Article", "slug", "non-existent")));

        // When & Then
        StepVerifier.create(articleController.getArticleBySlug("non-existent", null))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should increment views")
    void incrementViews_ShouldComplete() {
        // Given
        when(deduplicationService.recordViewIfNew(eq("test-article"), any())).thenReturn(Mono.just(true));
        when(articleService.incrementViews("test-article"))
                .thenReturn(Mono.empty());

        // When & Then
        StepVerifier.create(articleController.incrementViews("test-article", mockRequest))
                .verifyComplete();

        verify(articleService).incrementViews("test-article");
    }

    @Test
    @DisplayName("Should like article and return like count")
    void likeArticle_ShouldComplete() {
        // Given
        when(deduplicationService.recordLikeIfNew(eq("test-article"), any())).thenReturn(Mono.just(true));
        when(articleService.likeArticle("test-article"))
                .thenReturn(Mono.empty());
        when(articleService.getLikeCount("test-article"))
                .thenReturn(Mono.just(6));

        // When & Then
        StepVerifier.create(articleController.likeArticle("test-article", mockRequest))
                .assertNext(result -> assertThat(result.get("likeCount")).isEqualTo(6))
                .verifyComplete();

        verify(articleService).likeArticle("test-article");
        verify(articleService).getLikeCount("test-article");
    }

    // searchArticles was removed from ArticleController — search now lives in SearchController
}
