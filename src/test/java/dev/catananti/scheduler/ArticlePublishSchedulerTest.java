package dev.catananti.scheduler;

import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.entity.Subscriber;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.service.CacheService;
import dev.catananti.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArticlePublishScheduler")
class ArticlePublishSchedulerTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private CacheService cacheService;

    @Mock
    private SubscriberRepository subscriberRepository;

    @Mock
    private EmailService emailService;

    private ArticlePublishScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ArticlePublishScheduler(articleRepository, cacheService, subscriberRepository, emailService);
    }

    @Nested
    @DisplayName("publishScheduledArticles")
    class PublishScheduledArticles {

        @Test
        @DisplayName("should find and publish scheduled articles")
        void shouldFindAndPublishScheduledArticles() throws InterruptedException {
            Article article = Article.builder()
                    .id(1L)
                    .slug("test-article")
                    .title("Test Article")
                    .excerpt("Test excerpt")
                    .status("SCHEDULED")
                    .scheduledAt(LocalDateTime.now().minusMinutes(5))
                    .build();

            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.just(article));
            when(articleRepository.save(any(Article.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(subscriberRepository.findAllConfirmed())
                    .thenReturn(Flux.empty());
            when(cacheService.invalidateAllArticles())
                    .thenReturn(Mono.empty());

            scheduler.publishScheduledArticles();

            // Allow async subscribe to complete
            Thread.sleep(200);

            verify(articleRepository).findScheduledArticlesToPublish(any(LocalDateTime.class));

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            Article saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(ArticleStatus.PUBLISHED.name());
            assertThat(saved.getPublishedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should notify subscribers for published articles")
        void shouldNotifySubscribers() throws InterruptedException {
            Article article = Article.builder()
                    .id(1L)
                    .slug("notify-test")
                    .title("Notify Article")
                    .excerpt("Notify excerpt")
                    .status("SCHEDULED")
                    .scheduledAt(LocalDateTime.now().minusMinutes(1))
                    .build();

            Subscriber subscriber = Subscriber.builder()
                    .id(10L)
                    .email("test@example.com")
                    .name("Test User")
                    .unsubscribeToken("token-123")
                    .build();

            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.just(article));
            when(articleRepository.save(any(Article.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(subscriberRepository.findAllConfirmed())
                    .thenReturn(Flux.just(subscriber));
            when(emailService.sendNewArticleNotification(
                    anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn(Mono.empty());
            when(cacheService.invalidateAllArticles())
                    .thenReturn(Mono.empty());

            scheduler.publishScheduledArticles();

            Thread.sleep(200);

            verify(emailService).sendNewArticleNotification(
                    eq("test@example.com"),
                    eq("Test User"),
                    eq("Notify Article"),
                    eq("notify-test"),
                    eq("Notify excerpt"),
                    eq("token-123")
            );
        }

        @Test
        @DisplayName("should invalidate cache after publishing")
        void shouldInvalidateCache() throws InterruptedException {
            Article article = Article.builder()
                    .id(1L)
                    .slug("cache-test")
                    .title("Cache Article")
                    .excerpt("excerpt")
                    .status("SCHEDULED")
                    .scheduledAt(LocalDateTime.now().minusMinutes(1))
                    .build();

            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.just(article));
            when(articleRepository.save(any(Article.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(subscriberRepository.findAllConfirmed())
                    .thenReturn(Flux.empty());
            when(cacheService.invalidateAllArticles())
                    .thenReturn(Mono.empty());

            scheduler.publishScheduledArticles();

            Thread.sleep(200);

            verify(cacheService).invalidateAllArticles();
        }

        @Test
        @DisplayName("should handle errors without crashing")
        void shouldHandleErrors() throws InterruptedException {
            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.error(new RuntimeException("DB connection lost")));
            // .then() eagerly evaluates its argument during assembly, so stub is needed
            when(cacheService.invalidateAllArticles())
                    .thenReturn(Mono.empty());

            // Should not throw
            scheduler.publishScheduledArticles();

            Thread.sleep(200);

            verify(articleRepository).findScheduledArticlesToPublish(any(LocalDateTime.class));
            verify(articleRepository, never()).save(any());
        }

        @Test
        @DisplayName("should do nothing when no scheduled articles found")
        void shouldDoNothingWhenNoArticles() throws InterruptedException {
            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.empty());
            when(cacheService.invalidateAllArticles())
                    .thenReturn(Mono.empty());

            scheduler.publishScheduledArticles();

            Thread.sleep(200);

            verify(articleRepository).findScheduledArticlesToPublish(any(LocalDateTime.class));
            verify(articleRepository, never()).save(any());
            verify(emailService, never()).sendNewArticleNotification(
                    anyString(), anyString(), anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("should handle email notification failure gracefully")
        void shouldHandleEmailFailureGracefully() throws InterruptedException {
            Article article = Article.builder()
                    .id(1L)
                    .slug("email-fail-test")
                    .title("Email Fail Article")
                    .excerpt("excerpt")
                    .status("SCHEDULED")
                    .scheduledAt(LocalDateTime.now().minusMinutes(1))
                    .build();

            Subscriber subscriber = Subscriber.builder()
                    .id(10L)
                    .email("fail@example.com")
                    .name("Failing User")
                    .unsubscribeToken("token-fail")
                    .build();

            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.just(article));
            when(articleRepository.save(any(Article.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(subscriberRepository.findAllConfirmed())
                    .thenReturn(Flux.just(subscriber));
            when(emailService.sendNewArticleNotification(
                    anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("SMTP error")));
            when(cacheService.invalidateAllArticles())
                    .thenReturn(Mono.empty());

            // Should not throw even when email fails
            scheduler.publishScheduledArticles();

            Thread.sleep(200);

            // Article was still saved
            verify(articleRepository).save(any(Article.class));
            verify(cacheService).invalidateAllArticles();
        }
    }
}
