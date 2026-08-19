package dev.catananti.scheduler;

import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.service.ArticleAdminService;
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
    private ArticleAdminService articleAdminService;

    @Mock
    private SchedulerLock schedulerLock;

    private ArticlePublishScheduler scheduler;

    @BeforeEach
    void setUp() {
        // SchedulerLock.executeWithLock(name, ttl, task) is mocked as a
        // pass-through so the scheduler's reactive pipeline still runs.
        lenient().when(schedulerLock.executeWithLock(anyString(), any(Duration.class), any(Mono.class)))
                .thenAnswer(inv -> inv.getArgument(2));
        scheduler = new ArticlePublishScheduler(articleRepository, articleAdminService, schedulerLock);
    }

    private Article scheduledArticle(String slug) {
        return Article.builder()
                .id(1L)
                .slug(slug)
                .title("Test Article")
                .excerpt("Test excerpt")
                .status(ArticleStatus.SCHEDULED)
                .scheduledAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }

    @Nested
    @DisplayName("publishScheduledArticles")
    class PublishScheduledArticles {

        @Test
        @DisplayName("should claim scheduled articles and apply the shared publish side effects")
        void shouldPublishAndApplySharedSideEffects() {
            Article article = scheduledArticle("test-article");

            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.just(article));
            when(articleRepository.markPublishedIfScheduled(anyLong(), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1));
            // AUD19C-2: SSE + cache invalidation + notified_at-gated e-mail fan-out are
            // delegated to the shared ArticleAdminService method — the scheduler no
            // longer owns a private subscriber-notification duplicate.
            when(articleAdminService.applyPublishSideEffects(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            scheduler.publishScheduledArticles().block();

            verify(articleRepository).findScheduledArticlesToPublish(any(LocalDateTime.class));

            // Publication is an atomic compare-and-swap keyed by id, not a full save().
            ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
            verify(articleRepository).markPublishedIfScheduled(idCaptor.capture(), any(LocalDateTime.class));
            assertThat(idCaptor.getValue()).isEqualTo(1L);

            verify(articleAdminService).applyPublishSideEffects(article);

            // The in-memory copy handed downstream reflects the persisted PUBLISHED state.
            assertThat(article.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            assertThat(article.getPublishedAt()).isNotNull();
            assertThat(article.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should NOT apply side effects when another publisher already claimed the article (CAS)")
        void shouldNotApplySideEffectsWhenClaimLost() {
            // Models the PATCH-publish → scheduler overlap: a manual publish (or another
            // replica) flipped the status first, so the scheduler's status CAS gets 0 rows
            // and the shared side effects — including the subscriber e-mail — never run
            // a second time.
            Article article = scheduledArticle("race-test");

            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.just(article));
            when(articleRepository.markPublishedIfScheduled(anyLong(), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0));

            scheduler.publishScheduledArticles().block();

            verify(articleRepository).markPublishedIfScheduled(eq(1L), any(LocalDateTime.class));
            verify(articleAdminService, never()).applyPublishSideEffects(any(Article.class));
        }

        @Test
        @DisplayName("should handle errors without crashing")
        void shouldHandleErrors() {
            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.error(new RuntimeException("DB connection lost")));

            // Should not throw
            scheduler.publishScheduledArticles().block();

            verify(articleRepository).findScheduledArticlesToPublish(any(LocalDateTime.class));
            verify(articleRepository, never()).markPublishedIfScheduled(anyLong(), any());
            verify(articleAdminService, never()).applyPublishSideEffects(any(Article.class));
        }

        @Test
        @DisplayName("should do nothing when no scheduled articles found")
        void shouldDoNothingWhenNoArticles() {
            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.empty());

            scheduler.publishScheduledArticles().block();

            verify(articleRepository).findScheduledArticlesToPublish(any(LocalDateTime.class));
            verify(articleRepository, never()).markPublishedIfScheduled(anyLong(), any());
            verify(articleAdminService, never()).applyPublishSideEffects(any(Article.class));
        }

        @Test
        @DisplayName("should survive a side-effect failure without crashing the run")
        void shouldSurviveSideEffectFailure() {
            Article article = scheduledArticle("email-fail-test");

            when(articleRepository.findScheduledArticlesToPublish(any(LocalDateTime.class)))
                    .thenReturn(Flux.just(article));
            when(articleRepository.markPublishedIfScheduled(anyLong(), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1));
            when(articleAdminService.applyPublishSideEffects(any(Article.class)))
                    .thenReturn(Mono.error(new RuntimeException("SMTP error")));

            // Should not throw — the article was still published (atomic claim).
            scheduler.publishScheduledArticles().block();

            verify(articleRepository).markPublishedIfScheduled(anyLong(), any(LocalDateTime.class));
        }
    }
}
