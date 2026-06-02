package dev.catananti.scheduler;

import dev.catananti.config.PaginationConfig;
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

import java.time.Duration;
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

    @Mock
    private SchedulerLock schedulerLock;

    @Mock
    private PaginationConfig paginationConfig;

    private ArticlePublishScheduler scheduler;

    @BeforeEach
    void setUp() {
        // SchedulerLock.executeWithLock(name, ttl, task) is mocked as a
        // pass-through so the scheduler's reactive pipeline still runs.
        lenient().when(schedulerLock.executeWithLock(anyString(), any(Duration.class), any(Mono.class)))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(paginationConfig.getBulkQueryMax()).thenReturn(1000);
        scheduler = new ArticlePublishScheduler(articleRepository, cacheService, subscriberRepository, emailService, schedulerLock, paginationConfig);
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
                    .status(ArticleStatus.SCHEDULED)
                    .scheduledAt(LocalDateTime.now().minusMinutes(5))
                    .build();

            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.just(article));
            when(articleRepository.markPublishedIfScheduled(anyLong(), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1));
            when(subscriberRepository.findAllConfirmed(anyInt()))
                    .thenReturn(Flux.empty());
            when(cacheService.invalidateAllArticles())
                    .thenReturn(Mono.empty());

            scheduler.publishScheduledArticles().block();

            // Allow async subscribe to complete
            Thread.sleep(200);

            verify(articleRepository).findScheduledArticlesToPublish(any(LocalDateTime.class));

            // Publication is an atomic compare-and-swap keyed by id, not a full save().
            ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
            verify(articleRepository).markPublishedIfScheduled(idCaptor.capture(), any(LocalDateTime.class));
            assertThat(idCaptor.getValue()).isEqualTo(1L);
            // The in-memory copy handed downstream reflects the persisted PUBLISHED state.
            assertThat(article.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            assertThat(article.getPublishedAt()).isNotNull();
            assertThat(article.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should notify subscribers for published articles")
        void shouldNotifySubscribers() throws InterruptedException {
            Article article = Article.builder()
                    .id(1L)
                    .slug("notify-test")
                    .title("Notify Article")
                    .excerpt("Notify excerpt")
                    .status(ArticleStatus.SCHEDULED)
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
            when(articleRepository.markPublishedIfScheduled(anyLong(), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1));
            when(subscriberRepository.findAllConfirmed(anyInt()))
                    .thenReturn(Flux.just(subscriber));
            when(emailService.sendNewArticleNotification(
                    anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn(Mono.empty());
            when(cacheService.invalidateAllArticles())
                    .thenReturn(Mono.empty());

            scheduler.publishScheduledArticles().block();

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
                    .status(ArticleStatus.SCHEDULED)
                    .scheduledAt(LocalDateTime.now().minusMinutes(1))
                    .build();

            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.just(article));
            when(articleRepository.markPublishedIfScheduled(anyLong(), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1));
            when(subscriberRepository.findAllConfirmed(anyInt()))
                    .thenReturn(Flux.empty());
            when(cacheService.invalidateAllArticles())
                    .thenReturn(Mono.empty());

            scheduler.publishScheduledArticles().block();

            Thread.sleep(200);

            verify(cacheService).invalidateAllArticles();
        }

        @Test
        @DisplayName("should handle errors without crashing")
        void shouldHandleErrors() throws InterruptedException {
            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.error(new RuntimeException("DB connection lost")));

            // Should not throw
            scheduler.publishScheduledArticles().block();

            Thread.sleep(200);

            verify(articleRepository).findScheduledArticlesToPublish(any(LocalDateTime.class));
            verify(articleRepository, never()).markPublishedIfScheduled(anyLong(), any());
        }

        @Test
        @DisplayName("should do nothing when no scheduled articles found")
        void shouldDoNothingWhenNoArticles() throws InterruptedException {
            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.empty());

            scheduler.publishScheduledArticles().block();

            Thread.sleep(200);

            verify(articleRepository).findScheduledArticlesToPublish(any(LocalDateTime.class));
            verify(articleRepository, never()).markPublishedIfScheduled(anyLong(), any());
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
                    .status(ArticleStatus.SCHEDULED)
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
            when(articleRepository.markPublishedIfScheduled(anyLong(), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1));
            when(subscriberRepository.findAllConfirmed(anyInt()))
                    .thenReturn(Flux.just(subscriber));
            when(emailService.sendNewArticleNotification(
                    anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("SMTP error")));
            when(cacheService.invalidateAllArticles())
                    .thenReturn(Mono.empty());

            // Should not throw even when email fails
            scheduler.publishScheduledArticles().block();

            Thread.sleep(200);

            // Article was still published (atomic claim) despite the e-mail failure
            verify(articleRepository).markPublishedIfScheduled(anyLong(), any(LocalDateTime.class));
            verify(cacheService).invalidateAllArticles();
        }

        @Test
        @DisplayName("should NOT notify subscribers when another instance already published the article")
        void shouldNotNotifyWhenClaimLost() throws InterruptedException {
            Article article = Article.builder()
                    .id(1L)
                    .slug("race-test")
                    .title("Race Article")
                    .excerpt("excerpt")
                    .status(ArticleStatus.SCHEDULED)
                    .scheduledAt(LocalDateTime.now().minusMinutes(1))
                    .build();

            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.just(article));
            // 0 rows updated => another replica won the compare-and-swap.
            when(articleRepository.markPublishedIfScheduled(anyLong(), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0));

            scheduler.publishScheduledArticles().block();

            Thread.sleep(200);

            verify(articleRepository).markPublishedIfScheduled(eq(1L), any(LocalDateTime.class));
            // No duplicate e-mail blast and no redundant cache invalidation.
            verify(emailService, never()).sendNewArticleNotification(
                    anyString(), anyString(), anyString(), anyString(), any(), anyString());
            verify(subscriberRepository, never()).findAllConfirmed(anyInt());
            verify(cacheService, never()).invalidateAllArticles();
        }
    }
}
