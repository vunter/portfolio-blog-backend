package dev.catananti.controller;

import dev.catananti.service.CacheService;
import dev.catananti.service.CacheService.CacheStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminCacheControllerTest {

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private AdminCacheController controller;

    @Nested
    @DisplayName("GET /api/v1/admin/cache/stats")
    class GetCacheStats {

        @Test
        @DisplayName("Should return cache statistics")
        void shouldReturnCacheStats() {
            CacheStats stats = new CacheStats(50, 10, 30, 5, 2);

            when(cacheService.getCacheStats()).thenReturn(Mono.just(stats));

            StepVerifier.create(controller.getCacheStats())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().articlesCount()).isEqualTo(50);
                        assertThat(response.getBody().tagsCount()).isEqualTo(10);
                        assertThat(response.getBody().commentsCount()).isEqualTo(30);
                        assertThat(response.getBody().searchCount()).isEqualTo(5);
                        assertThat(response.getBody().feedCount()).isEqualTo(2);
                        assertThat(response.getBody().total()).isEqualTo(97);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/cache/articles")
    class InvalidateAllArticles {

        @Test
        @DisplayName("Should invalidate all article caches")
        void shouldInvalidateAllArticles() {
            when(cacheService.invalidateAllArticles()).thenReturn(Mono.just(50L));

            StepVerifier.create(controller.invalidateAllArticles())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).containsEntry("message", "Article cache invalidated");
                        assertThat(body).containsEntry("entriesRemoved", 50L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/cache/articles/{slug}")
    class InvalidateArticleBySlug {

        @Test
        @DisplayName("Should invalidate article cache by slug")
        void shouldInvalidateArticleBySlug() {
            when(cacheService.invalidateArticle("spring-boot-guide")).thenReturn(Mono.just(3L));

            StepVerifier.create(controller.invalidateArticle("spring-boot-guide"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).containsEntry("slug", "spring-boot-guide");
                        assertThat(body).containsEntry("entriesRemoved", 3L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/cache/tags")
    class InvalidateAllTags {

        @Test
        @DisplayName("Should invalidate all tag caches")
        void shouldInvalidateAllTags() {
            when(cacheService.invalidateAllTags()).thenReturn(Mono.just(10L));

            StepVerifier.create(controller.invalidateAllTags())
                    .assertNext(response -> {
                        Map<String, Object> body = response.getBody();
                        assertThat(body).containsEntry("message", "Tag cache invalidated");
                        assertThat(body).containsEntry("entriesRemoved", 10L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/cache/comments")
    class InvalidateAllComments {

        @Test
        @DisplayName("Should invalidate all comment caches")
        void shouldInvalidateAllComments() {
            when(cacheService.invalidateAllComments()).thenReturn(Mono.just(30L));

            StepVerifier.create(controller.invalidateAllComments())
                    .assertNext(response -> {
                        Map<String, Object> body = response.getBody();
                        assertThat(body).containsEntry("message", "Comment cache invalidated");
                        assertThat(body).containsEntry("entriesRemoved", 30L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/cache/search")
    class InvalidateSearchCache {

        @Test
        @DisplayName("Should invalidate search cache")
        void shouldInvalidateSearchCache() {
            when(cacheService.invalidateSearchCache()).thenReturn(Mono.just(5L));

            StepVerifier.create(controller.invalidateSearchCache())
                    .assertNext(response -> {
                        Map<String, Object> body = response.getBody();
                        assertThat(body).containsEntry("message", "Search cache invalidated");
                        assertThat(body).containsEntry("entriesRemoved", 5L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/cache/feeds")
    class InvalidateFeedCache {

        @Test
        @DisplayName("Should invalidate feed cache")
        void shouldInvalidateFeedCache() {
            when(cacheService.invalidateFeedCache()).thenReturn(Mono.just(2L));

            StepVerifier.create(controller.invalidateFeedCache())
                    .assertNext(response -> {
                        Map<String, Object> body = response.getBody();
                        assertThat(body).containsEntry("message", "Feed cache invalidated");
                        assertThat(body).containsEntry("entriesRemoved", 2L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/cache/all")
    class InvalidateAllCaches {

        @Test
        @DisplayName("Should invalidate all caches")
        void shouldInvalidateAllCaches() {
            when(cacheService.invalidateAllCaches()).thenReturn(Mono.just(97L));

            StepVerifier.create(controller.invalidateAllCaches())
                    .assertNext(response -> {
                        Map<String, Object> body = response.getBody();
                        assertThat(body).containsEntry("message", "All caches invalidated");
                        assertThat(body).containsEntry("entriesRemoved", 97L);
                    })
                    .verifyComplete();
        }
    }
}
