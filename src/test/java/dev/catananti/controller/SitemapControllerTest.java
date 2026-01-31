package dev.catananti.controller;

import dev.catananti.entity.Article;
import dev.catananti.entity.Tag;
import dev.catananti.service.ArticleService;
import dev.catananti.service.CacheService;
import dev.catananti.service.TagService;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SitemapControllerTest {

    @Mock private ArticleService articleService;
    @Mock private TagService tagService;
    @Mock private CacheService cacheService;

    @InjectMocks
    private SitemapController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "siteUrl", "https://catananti.dev");
    }

    @Nested
    @DisplayName("GET /sitemap.xml")
    class GetSitemap {

        @Test
        @DisplayName("Should return cached sitemap if available")
        void shouldReturnCachedSitemap() {
            String cached = "<urlset>cached</urlset>";
            when(cacheService.get("sitemap:xml", String.class)).thenReturn(Mono.just(cached));
            // Eagerly evaluated Java expressions in switchIfEmpty argument
            when(articleService.findAllPublishedForFeed()).thenReturn(Flux.empty());
            when(tagService.findAllTagEntities()).thenReturn(Flux.empty());

            StepVerifier.create(controller.getSitemap())
                    .assertNext(result -> assertThat(result).isEqualTo("<urlset>cached</urlset>"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should generate sitemap when cache is empty")
        void shouldGenerateSitemapWhenCacheEmpty() {
            Article article = Article.builder()
                    .id(1L)
                    .slug("test-article")
                    .publishedAt(LocalDateTime.of(2026, 1, 15, 10, 0))
                    .updatedAt(LocalDateTime.of(2026, 1, 20, 12, 0))
                    .build();

            Tag tag = Tag.builder()
                    .id(1L)
                    .slug("java")
                    .build();

            when(cacheService.get("sitemap:xml", String.class)).thenReturn(Mono.empty());
            when(articleService.findAllPublishedForFeed()).thenReturn(Flux.just(article));
            when(tagService.findAllTagEntities()).thenReturn(Flux.just(tag));
            when(cacheService.set(eq("sitemap:xml"), anyString(), any())).thenReturn(Mono.just(true));

            StepVerifier.create(controller.getSitemap())
                    .assertNext(result -> {
                        assertThat(result).contains("<?xml version=\"1.0\"");
                        assertThat(result).contains("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
                        assertThat(result).contains("https://catananti.dev/</loc>");
                        assertThat(result).contains("https://catananti.dev/blog</loc>");
                        assertThat(result).contains("https://catananti.dev/about</loc>");
                        assertThat(result).contains("/blog/test-article</loc>");
                        assertThat(result).contains("<lastmod>2026-01-20</lastmod>");
                        assertThat(result).contains("/tags/java</loc>");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should include static pages (homepage, blog, about)")
        void shouldIncludeStaticPages() {
            when(cacheService.get("sitemap:xml", String.class)).thenReturn(Mono.empty());
            when(articleService.findAllPublishedForFeed()).thenReturn(Flux.empty());
            when(tagService.findAllTagEntities()).thenReturn(Flux.empty());
            when(cacheService.set(eq("sitemap:xml"), anyString(), any())).thenReturn(Mono.just(true));

            StepVerifier.create(controller.getSitemap())
                    .assertNext(result -> {
                        assertThat(result).contains("<priority>1.0</priority>"); // homepage
                        assertThat(result).contains("<priority>0.9</priority>"); // blog
                        assertThat(result).contains("<priority>0.7</priority>"); // about
                        assertThat(result).contains("<changefreq>daily</changefreq>");
                        assertThat(result).contains("<changefreq>monthly</changefreq>");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should use publishedAt as lastmod when updatedAt is null")
        void shouldUsePublishedAtWhenUpdatedAtIsNull() {
            Article article = Article.builder()
                    .id(1L)
                    .slug("old-article")
                    .publishedAt(LocalDateTime.of(2025, 6, 1, 8, 0))
                    .updatedAt(null)
                    .build();

            when(cacheService.get("sitemap:xml", String.class)).thenReturn(Mono.empty());
            when(articleService.findAllPublishedForFeed()).thenReturn(Flux.just(article));
            when(tagService.findAllTagEntities()).thenReturn(Flux.empty());
            when(cacheService.set(eq("sitemap:xml"), anyString(), any())).thenReturn(Mono.just(true));

            StepVerifier.create(controller.getSitemap())
                    .assertNext(result -> assertThat(result).contains("<lastmod>2025-06-01</lastmod>"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle multiple articles and tags")
        void shouldHandleMultipleArticlesAndTags() {
            Article a1 = Article.builder().id(1L).slug("article-1")
                    .publishedAt(LocalDateTime.of(2026, 1, 10, 9, 0)).build();
            Article a2 = Article.builder().id(2L).slug("article-2")
                    .publishedAt(LocalDateTime.of(2026, 1, 15, 10, 0)).build();
            Tag t1 = Tag.builder().id(1L).slug("java").build();
            Tag t2 = Tag.builder().id(2L).slug("spring").build();

            when(cacheService.get("sitemap:xml", String.class)).thenReturn(Mono.empty());
            when(articleService.findAllPublishedForFeed()).thenReturn(Flux.just(a1, a2));
            when(tagService.findAllTagEntities()).thenReturn(Flux.just(t1, t2));
            when(cacheService.set(eq("sitemap:xml"), anyString(), any())).thenReturn(Mono.just(true));

            StepVerifier.create(controller.getSitemap())
                    .assertNext(result -> {
                        assertThat(result).contains("/blog/article-1");
                        assertThat(result).contains("/blog/article-2");
                        assertThat(result).contains("/tags/java");
                        assertThat(result).contains("/tags/spring");
                    })
                    .verifyComplete();
        }
    }
}
