package dev.catananti.controller;

import dev.catananti.entity.Article;
import dev.catananti.service.ArticleService;
import dev.catananti.service.CacheService;
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
class RssFeedControllerTest {

    @Mock private ArticleService articleService;
    @Mock private CacheService cacheService;

    @InjectMocks
    private RssFeedController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "siteUrl", "https://catananti.dev");
        ReflectionTestUtils.setField(controller, "siteName", "Portfolio Blog");
        ReflectionTestUtils.setField(controller, "siteDescription", "Developer blog");
    }

    private Article buildArticle(String slug, String title) {
        return Article.builder()
                .id(1L)
                .slug(slug)
                .title(title)
                .excerpt("Test excerpt")
                .publishedAt(LocalDateTime.of(2026, 1, 15, 10, 0))
                .build();
    }

    @Nested
    @DisplayName("GET /rss.xml or /feed.xml")
    class GetRssFeed {

        @Test
        @DisplayName("Should return RSS feed from cache if available")
        void shouldReturnRssFeedFromCache() {
            String cachedFeed = "<rss>cached</rss>";
            when(cacheService.get("rss:feed", String.class)).thenReturn(Mono.just(cachedFeed));
            // articleService.findAllPublishedForFeed() is evaluated eagerly as a Java expression
            // even though switchIfEmpty is lazy at subscription time
            when(articleService.findAllPublishedForFeed()).thenReturn(Flux.empty());

            StepVerifier.create(controller.getRssFeed())
                    .assertNext(result -> assertThat(result.getBody()).isEqualTo("<rss>cached</rss>"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should generate RSS feed when cache is empty")
        void shouldGenerateRssFeedWhenCacheEmpty() {
            Article article = buildArticle("test-article", "Test Article");

            when(cacheService.get("rss:feed", String.class)).thenReturn(Mono.empty());
            when(articleService.findAllPublishedForFeed()).thenReturn(Flux.just(article));
            when(cacheService.set(eq("rss:feed"), anyString(), any())).thenReturn(Mono.just(true));

            StepVerifier.create(controller.getRssFeed())
                    .assertNext(result -> {
                        String body = result.getBody();
                        assertThat(body).contains("<?xml version=\"1.0\"");
                        assertThat(body).contains("<rss version=\"2.0\"");
                        assertThat(body).contains("<title>Portfolio Blog</title>");
                        assertThat(body).contains("<title>Test Article</title>");
                        assertThat(body).contains("/blog/test-article");
                        assertThat(body).contains("Test excerpt");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle empty article list")
        void shouldHandleEmptyArticleList() {
            when(cacheService.get("rss:feed", String.class)).thenReturn(Mono.empty());
            when(articleService.findAllPublishedForFeed()).thenReturn(Flux.empty());
            when(cacheService.set(eq("rss:feed"), anyString(), any())).thenReturn(Mono.just(true));

            StepVerifier.create(controller.getRssFeed())
                    .assertNext(result -> {
                        String body = result.getBody();
                        assertThat(body).contains("<rss version=\"2.0\"");
                        assertThat(body).contains("</rss>");
                        assertThat(body).doesNotContain("<item>");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should include site URL and description in feed")
        void shouldIncludeSiteUrlAndDescription() {
            when(cacheService.get("rss:feed", String.class)).thenReturn(Mono.empty());
            when(articleService.findAllPublishedForFeed()).thenReturn(Flux.empty());
            when(cacheService.set(eq("rss:feed"), anyString(), any())).thenReturn(Mono.just(true));

            StepVerifier.create(controller.getRssFeed())
                    .assertNext(result -> {
                        String body = result.getBody();
                        assertThat(body).contains("<link>https://catananti.dev</link>");
                        assertThat(body).contains("<description>Developer blog</description>");
                        assertThat(body).contains("<language>en-us</language>");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should include lastBuildDate when articles have publishedAt")
        void shouldIncludeLastBuildDate() {
            Article article = buildArticle("article", "Article");

            when(cacheService.get("rss:feed", String.class)).thenReturn(Mono.empty());
            when(articleService.findAllPublishedForFeed()).thenReturn(Flux.just(article));
            when(cacheService.set(eq("rss:feed"), anyString(), any())).thenReturn(Mono.just(true));

            StepVerifier.create(controller.getRssFeed())
                    .assertNext(result -> assertThat(result.getBody()).contains("<lastBuildDate>"))
                    .verifyComplete();
        }
    }
}
