package dev.catananti.metrics;

import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.SubscriberRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlogMetrics")
class BlogMetricsTest {

    private SimpleMeterRegistry meterRegistry;

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private SubscriberRepository subscriberRepository;

    private BlogMetrics blogMetrics;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        // BlogMetrics uses @RequiredArgsConstructor (final fields in order: meterRegistry, articleRepository, commentRepository, subscriberRepository)
        blogMetrics = createBlogMetrics(meterRegistry, articleRepository, commentRepository, subscriberRepository);
    }

    /**
     * Creates a BlogMetrics instance by setting its final fields via reflection,
     * since Lombok's @RequiredArgsConstructor generates a constructor for final fields.
     */
    private BlogMetrics createBlogMetrics(SimpleMeterRegistry registry,
                                          ArticleRepository articleRepo,
                                          CommentRepository commentRepo,
                                          SubscriberRepository subscriberRepo) throws Exception {
        // Use Lombok-generated constructor
        var constructor = BlogMetrics.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (BlogMetrics) constructor.newInstance(registry, articleRepo, commentRepo, subscriberRepo);
    }

    @Nested
    @DisplayName("init")
    class Init {

        @Test
        @DisplayName("should register article gauges with MeterRegistry")
        void shouldRegisterArticleGauges() {
            blogMetrics.init();

            assertThat(meterRegistry.find("blog.articles.total").gauge()).isNotNull();
            assertThat(meterRegistry.find("blog.articles.published").gauge()).isNotNull();
            assertThat(meterRegistry.find("blog.articles.draft").gauge()).isNotNull();
        }

        @Test
        @DisplayName("should register comment gauges with MeterRegistry")
        void shouldRegisterCommentGauges() {
            blogMetrics.init();

            assertThat(meterRegistry.find("blog.comments.total").gauge()).isNotNull();
            assertThat(meterRegistry.find("blog.comments.pending").gauge()).isNotNull();
        }

        @Test
        @DisplayName("should register subscriber gauge with MeterRegistry")
        void shouldRegisterSubscriberGauge() {
            blogMetrics.init();

            assertThat(meterRegistry.find("blog.subscribers.active").gauge()).isNotNull();
        }

        @Test
        @DisplayName("gauges should initially report zero")
        void gaugesShouldInitiallyReportZero() {
            blogMetrics.init();

            Gauge totalArticles = meterRegistry.find("blog.articles.total").gauge();
            assertThat(totalArticles).isNotNull();
            assertThat(totalArticles.value()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("updateMetrics")
    class UpdateMetrics {

        @Test
        @DisplayName("should set atomic values from repository counts")
        void shouldSetAtomicValuesFromRepositoryCounts() throws InterruptedException {
            blogMetrics.init();

            when(articleRepository.countAll()).thenReturn(Mono.just(10L));
            when(articleRepository.countByStatus("PUBLISHED")).thenReturn(Mono.just(7L));
            when(articleRepository.countByStatus("DRAFT")).thenReturn(Mono.just(3L));
            when(commentRepository.count()).thenReturn(Mono.just(50L));
            when(commentRepository.countByStatus("PENDING")).thenReturn(Mono.just(5L));
            when(subscriberRepository.countConfirmed()).thenReturn(Mono.just(100L));

            blogMetrics.updateMetrics();

            // Give the async subscribe a moment to complete
            Thread.sleep(100);

            assertThat(meterRegistry.find("blog.articles.total").gauge().value()).isEqualTo(10.0);
            assertThat(meterRegistry.find("blog.articles.published").gauge().value()).isEqualTo(7.0);
            assertThat(meterRegistry.find("blog.articles.draft").gauge().value()).isEqualTo(3.0);
            assertThat(meterRegistry.find("blog.comments.total").gauge().value()).isEqualTo(50.0);
            assertThat(meterRegistry.find("blog.comments.pending").gauge().value()).isEqualTo(5.0);
            assertThat(meterRegistry.find("blog.subscribers.active").gauge().value()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("should handle repository errors gracefully via onErrorReturn")
        void shouldHandleErrorsGracefully() throws InterruptedException {
            blogMetrics.init();

            when(articleRepository.countAll()).thenReturn(Mono.error(new RuntimeException("DB down")));
            when(articleRepository.countByStatus("PUBLISHED")).thenReturn(Mono.error(new RuntimeException("DB down")));
            when(articleRepository.countByStatus("DRAFT")).thenReturn(Mono.error(new RuntimeException("DB down")));
            when(commentRepository.count()).thenReturn(Mono.error(new RuntimeException("DB down")));
            when(commentRepository.countByStatus("PENDING")).thenReturn(Mono.error(new RuntimeException("DB down")));
            when(subscriberRepository.countConfirmed()).thenReturn(Mono.error(new RuntimeException("DB down")));

            blogMetrics.updateMetrics();

            // Give the async subscribe a moment to complete
            Thread.sleep(100);

            // All values should fall back to 0 via onErrorReturn(0L)
            assertThat(meterRegistry.find("blog.articles.total").gauge().value()).isEqualTo(0.0);
            assertThat(meterRegistry.find("blog.articles.published").gauge().value()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Counter increments")
    class CounterIncrements {

        @BeforeEach
        void initMetrics() {
            // F-055: Cached counters require init() to be called first
            blogMetrics.init();
        }

        @Test
        @DisplayName("incrementArticleViews should increment counter")
        void shouldIncrementArticleViews() {
            blogMetrics.incrementArticleViews("test-slug");

            // F-054: Counter now includes slug tag
            Counter counter = meterRegistry.find("blog.article.views.total").tag("slug", "test-slug").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("incrementArticleViews multiple times should accumulate")
        void shouldAccumulateArticleViews() {
            blogMetrics.incrementArticleViews("slug-1");
            blogMetrics.incrementArticleViews("slug-2");
            blogMetrics.incrementArticleViews("slug-3");

            // F-054: Each slug has its own counter; total across all slugs is 3
            assertThat(meterRegistry.find("blog.article.views.total").tag("slug", "slug-1").counter().count()).isEqualTo(1.0);
            assertThat(meterRegistry.find("blog.article.views.total").counters().stream()
                    .mapToDouble(Counter::count).sum()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("incrementArticleLikes should increment counter")
        void shouldIncrementArticleLikes() {
            blogMetrics.incrementArticleLikes("test-slug");

            // F-054: Counter now includes slug tag
            Counter counter = meterRegistry.find("blog.article.likes.total").tag("slug", "test-slug").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("incrementCommentCreated should increment counter")
        void shouldIncrementCommentCreated() {
            blogMetrics.incrementCommentCreated();

            Counter counter = meterRegistry.find("blog.comment.events.created").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("incrementSubscription should increment counter")
        void shouldIncrementSubscription() {
            blogMetrics.incrementSubscription();

            Counter counter = meterRegistry.find("blog.subscriptions.new").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("incrementUnsubscription should increment counter")
        void shouldIncrementUnsubscription() {
            blogMetrics.incrementUnsubscription();

            Counter counter = meterRegistry.find("blog.subscriptions.cancelled").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }
}
