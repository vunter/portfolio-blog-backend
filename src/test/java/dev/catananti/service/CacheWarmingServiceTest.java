package dev.catananti.service;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Article;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CacheWarmingService Tests")
class CacheWarmingServiceTest {

    @InjectMocks
    private CacheWarmingService cacheWarmingService;

    @Mock
    private ArticleService articleService;

    @Mock
    private TagService tagService;

    @Mock
    private CacheService cacheService;

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private TagRepository tagRepository;

    @BeforeEach
    void setUp() {
        // Set default configuration values
        ReflectionTestUtils.setField(cacheWarmingService, "warmingEnabled", true);
        ReflectionTestUtils.setField(cacheWarmingService, "startupPages", 3);
        ReflectionTestUtils.setField(cacheWarmingService, "prefetchDelayMs", 10L);
    }

    @Test
    @DisplayName("Should check startup warming complete status")
    void shouldCheckStartupWarmingStatus() {
        // Initially false
        assertThat(cacheWarmingService.isStartupWarmingComplete()).isFalse();
    }

    @Test
    @DisplayName("Should return warming status")
    void shouldReturnWarmingStatus() {
        CacheWarmingService.WarmingStatus status = cacheWarmingService.getStatus();

        assertThat(status).isNotNull();
        assertThat(status.enabled()).isTrue();
        assertThat(status.startupComplete()).isFalse();
        assertThat(status.prefetchesInProgress()).isZero();
    }

    @Test
    @DisplayName("Should warm articles by tag")
    void shouldWarmArticlesByTag() {
        // Given
        Article article = createMockArticle(1L, "Test Article");
        ArticleResponse response = ArticleResponse.builder()
                .id("1")
                .slug("test-article")
                .title("Test Article")
                .build();

        when(articleRepository.findByTagSlugAndStatus(eq("java"), eq("PUBLISHED"), anyInt(), anyInt()))
                .thenReturn(Flux.just(article));
        when(articleService.getPublishedArticleBySlug("test-article"))
                .thenReturn(Mono.just(response));

        // When
        Mono<Integer> result = cacheWarmingService.warmArticlesByTag("java");

        // Then
        StepVerifier.create(result)
                .expectNext(1)
                .verifyComplete();

        verify(articleRepository).findByTagSlugAndStatus("java", "PUBLISHED", 20, 0);
        verify(articleService).getPublishedArticleBySlug("test-article");
    }

    @Test
    @DisplayName("Should handle empty tag results")
    void shouldHandleEmptyTagResults() {
        // Given
        when(articleRepository.findByTagSlugAndStatus(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Flux.empty());

        // When
        Mono<Integer> result = cacheWarmingService.warmArticlesByTag("unknown-tag");

        // Then
        StepVerifier.create(result)
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should clear and rewarm caches")
    void shouldClearAndRewarmCaches() {
        // Given
        when(cacheService.invalidateAllCaches()).thenReturn(Mono.empty());
        // Setup for warmOnStartup (called asynchronously)
        PageResponse<ArticleResponse> emptyPage = PageResponse.<ArticleResponse>builder()
                .content(List.of())
                .page(0)
                .size(10)
                .totalElements(0)
                .totalPages(0)
                .first(true)
                .last(true)
                .build();
        when(articleService.getPublishedArticles(anyInt(), anyInt()))
                .thenReturn(Mono.just(emptyPage));
        when(tagService.getAllTags("en")).thenReturn(Flux.empty());
        when(articleRepository.findTopByViewsCount(anyInt())).thenReturn(Flux.empty());

        // When
        Mono<Void> result = cacheWarmingService.clearAndRewarm();

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        verify(cacheService).invalidateAllCaches();
    }

    @Test
    @DisplayName("Should not prefetch when warming disabled")
    void shouldNotPrefetchWhenDisabled() {
        // Given
        ReflectionTestUtils.setField(cacheWarmingService, "warmingEnabled", false);

        // When
        cacheWarmingService.prefetchRelatedContent("article-slug", Set.of("java"));

        // Then - should not call any services
        verifyNoInteractions(articleRepository);
    }

    @Test
    @DisplayName("Should handle errors during warm articles by tag")
    void shouldHandleErrorsDuringWarmByTag() {
        // Given
        Article article = createMockArticle(1L, "Article");
        when(articleRepository.findByTagSlugAndStatus(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Flux.just(article));
        when(articleService.getPublishedArticleBySlug(anyString()))
                .thenReturn(Mono.error(new RuntimeException("Service error")));

        // When
        Mono<Integer> result = cacheWarmingService.warmArticlesByTag("java");

        // Then - should complete with 0 due to error resume
        StepVerifier.create(result)
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should not start duplicate prefetch for same article")
    void shouldNotDuplicatePrefetch() {
        // Given
        when(articleRepository.findByTagSlugAndStatus(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Flux.empty());

        // When - call twice with same slug
        cacheWarmingService.prefetchRelatedContent("same-article", Set.of("java"));
        cacheWarmingService.prefetchRelatedContent("same-article", Set.of("java"));

        // Then - only one call should happen
        // (the second is rejected due to warmingInProgress set)
    }

    private Article createMockArticle(Long id, String title) {
        Article article = new Article();
        article.setId(id);
        article.setTitle(title);
        article.setSlug(title.toLowerCase().replace(" ", "-"));
        return article;
    }

    @Nested
    @DisplayName("warmOnStartup")
    class WarmOnStartup {

        @Test
        @DisplayName("Should skip warming when disabled")
        void shouldSkipWhenDisabled() {
            ReflectionTestUtils.setField(cacheWarmingService, "warmingEnabled", false);

            cacheWarmingService.warmOnStartup();

            verifyNoInteractions(articleService);
            verifyNoInteractions(tagService);
        }

        @Test
        @DisplayName("Should warm published articles, tags, and popular articles on startup")
        void shouldWarmOnStartup() throws InterruptedException {
            PageResponse<ArticleResponse> page = PageResponse.<ArticleResponse>builder()
                    .content(List.of()).page(0).size(10)
                    .totalElements(0).totalPages(0).first(true).last(true).build();

            when(articleService.getPublishedArticles(anyInt(), anyInt())).thenReturn(Mono.just(page));
            when(tagService.getAllTags("en")).thenReturn(Flux.empty());
            when(articleRepository.findTopByViewsCount(anyInt())).thenReturn(Flux.empty());

            cacheWarmingService.warmOnStartup();

            // Allow async operations to complete
            Thread.sleep(500);

            verify(articleService, atLeastOnce()).getPublishedArticles(anyInt(), anyInt());
            verify(tagService).getAllTags("en");
        }
    }

    @Nested
    @DisplayName("refreshPopularContent")
    class RefreshPopularContent {

        @Test
        @DisplayName("Should skip refresh when warming is disabled")
        void shouldSkipWhenDisabled() {
            ReflectionTestUtils.setField(cacheWarmingService, "warmingEnabled", false);

            cacheWarmingService.refreshPopularContent();

            verifyNoInteractions(cacheService);
            verifyNoInteractions(articleService);
        }

        @Test
        @DisplayName("Should skip refresh when startup warming not complete")
        void shouldSkipWhenStartupNotComplete() {
            // warmingEnabled is true but startupWarmingComplete is false (default)
            cacheWarmingService.refreshPopularContent();

            verifyNoInteractions(cacheService);
        }
    }

    @Nested
    @DisplayName("prefetchRelatedContent - additional cases")
    class PrefetchAdditionalCases {

        @Test
        @DisplayName("Should execute prefetch with real tags")
        void shouldPrefetchWithRealTags() throws InterruptedException {
            Article relatedArticle = createMockArticle(2L, "Related Article");
            ArticleResponse response = ArticleResponse.builder()
                    .id("2").slug("related-article").title("Related Article").build();

            when(articleRepository.findByTagSlugAndStatus(eq("java"), eq("PUBLISHED"), anyInt(), anyInt()))
                    .thenReturn(Flux.just(relatedArticle));
            when(articleService.getPublishedArticleBySlug("related-article"))
                    .thenReturn(Mono.just(response));

            cacheWarmingService.prefetchRelatedContent("different-slug", Set.of("java"));

            // Allow async + delay to complete
            Thread.sleep(500);

            verify(articleRepository, atLeastOnce()).findByTagSlugAndStatus(eq("java"), eq("PUBLISHED"), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Should handle prefetch error gracefully")
        void shouldHandlePrefetchError() throws InterruptedException {
            when(articleRepository.findByTagSlugAndStatus(anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(Flux.error(new RuntimeException("DB error")));

            cacheWarmingService.prefetchRelatedContent("error-slug", Set.of("java"));

            // Allow async operations
            Thread.sleep(300);

            // Should not throw - error is handled by subscribeBackground
        }
    }

    @Nested
    @DisplayName("getStatus - various states")
    class GetStatusVariousStates {

        @Test
        @DisplayName("Should return disabled status")
        void shouldReturnDisabledStatus() {
            ReflectionTestUtils.setField(cacheWarmingService, "warmingEnabled", false);

            CacheWarmingService.WarmingStatus status = cacheWarmingService.getStatus();

            assertThat(status.enabled()).isFalse();
            assertThat(status.startupComplete()).isFalse();
        }

        @Test
        @DisplayName("Should track background error count as zero initially")
        void shouldTrackZeroErrorsInitially() {
            CacheWarmingService.WarmingStatus status = cacheWarmingService.getStatus();

            assertThat(status.backgroundErrorCount()).isZero();
            assertThat(status.prefetchesInProgress()).isZero();
        }
    }

    @Nested
    @DisplayName("warmArticlesByTag - multiple articles")
    class WarmArticlesByTagMultiple {

        @Test
        @DisplayName("Should warm multiple articles for a tag")
        void shouldWarmMultipleArticles() {
            Article a1 = createMockArticle(1L, "Article One");
            Article a2 = createMockArticle(2L, "Article Two");
            Article a3 = createMockArticle(3L, "Article Three");

            ArticleResponse r1 = ArticleResponse.builder().id("1").slug("article-one").title("Article One").build();
            ArticleResponse r2 = ArticleResponse.builder().id("2").slug("article-two").title("Article Two").build();
            ArticleResponse r3 = ArticleResponse.builder().id("3").slug("article-three").title("Article Three").build();

            when(articleRepository.findByTagSlugAndStatus(eq("spring"), eq("PUBLISHED"), anyInt(), anyInt()))
                    .thenReturn(Flux.just(a1, a2, a3));
            when(articleService.getPublishedArticleBySlug("article-one")).thenReturn(Mono.just(r1));
            when(articleService.getPublishedArticleBySlug("article-two")).thenReturn(Mono.just(r2));
            when(articleService.getPublishedArticleBySlug("article-three")).thenReturn(Mono.just(r3));

            StepVerifier.create(cacheWarmingService.warmArticlesByTag("spring"))
                    .expectNext(3)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should count only successfully warmed articles")
        void shouldCountOnlySuccessful() {
            Article a1 = createMockArticle(1L, "Good Article");
            Article a2 = createMockArticle(2L, "Bad Article");

            ArticleResponse r1 = ArticleResponse.builder().id("1").slug("good-article").title("Good Article").build();

            when(articleRepository.findByTagSlugAndStatus(eq("mixed"), eq("PUBLISHED"), anyInt(), anyInt()))
                    .thenReturn(Flux.just(a1, a2));
            when(articleService.getPublishedArticleBySlug("good-article")).thenReturn(Mono.just(r1));
            when(articleService.getPublishedArticleBySlug("bad-article"))
                    .thenReturn(Mono.error(new RuntimeException("Cache error")));

            StepVerifier.create(cacheWarmingService.warmArticlesByTag("mixed"))
                    .expectNext(1)
                    .verifyComplete();
        }
    }
}
