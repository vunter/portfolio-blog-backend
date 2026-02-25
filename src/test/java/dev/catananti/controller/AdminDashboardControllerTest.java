package dev.catananti.controller;

import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.repository.TagRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.entity.Article;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminDashboardControllerTest {

    @Mock private ArticleRepository articleRepository;
    @Mock private CommentRepository commentRepository;
    @Mock private UserRepository userRepository;
    @Mock private SubscriberRepository subscriberRepository;
    @Mock private TagRepository tagRepository;

    @InjectMocks
    private AdminDashboardController controller;

    private <T> Mono<T> withAdminAuth(Mono<T> mono) {
        lenient().when(userRepository.findByEmail("admin@test.com"))
                .thenReturn(Mono.just(dev.catananti.entity.User.builder()
                        .id(1L).email("admin@test.com").name("Admin").role("ADMIN").build()));
        var auth = new UsernamePasswordAuthenticationToken("admin@test.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        return mono.contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                Mono.just(new SecurityContextImpl(auth))));
    }

    @Nested
    @DisplayName("GET /api/v1/admin/dashboard/stats")
    class DashboardStats {

        @Test
        @DisplayName("Should return complete dashboard statistics")
        void shouldReturnDashboardStats() {
            when(articleRepository.count()).thenReturn(Mono.just(42L));
            when(articleRepository.countByStatus("PUBLISHED")).thenReturn(Mono.just(30L));
            when(articleRepository.countByStatus("DRAFT")).thenReturn(Mono.just(12L));
            when(commentRepository.count()).thenReturn(Mono.just(156L));
            when(commentRepository.countByStatus("PENDING")).thenReturn(Mono.just(12L));
            when(userRepository.count()).thenReturn(Mono.just(25L));
            when(subscriberRepository.countConfirmed()).thenReturn(Mono.just(89L));
            when(articleRepository.sumViewsCount()).thenReturn(Mono.just(1000L));
            when(tagRepository.count()).thenReturn(Mono.just(10L));

            StepVerifier.create(withAdminAuth(controller.getDashboardStats()))
                    .assertNext(stats -> {
                        assertThat(stats.get("totalArticles")).isEqualTo(42L);
                        assertThat(stats.get("publishedArticles")).isEqualTo(30L);
                        assertThat(stats.get("draftArticles")).isEqualTo(12L);
                        assertThat(stats.get("totalComments")).isEqualTo(156L);
                        assertThat(stats.get("pendingComments")).isEqualTo(12L);
                        assertThat(stats.get("totalUsers")).isEqualTo(25L);
                        assertThat(stats.get("newsletterSubscribers")).isEqualTo(89L);
                        assertThat(stats.get("totalViews")).isEqualTo(1000L);
                        assertThat(stats.get("totalTags")).isEqualTo(10L);
                        assertThat(stats.get("timestamp")).isNotNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle empty blog with zero counts")
        void shouldHandleEmptyBlog() {
            when(articleRepository.count()).thenReturn(Mono.just(0L));
            when(articleRepository.countByStatus("PUBLISHED")).thenReturn(Mono.just(0L));
            when(articleRepository.countByStatus("DRAFT")).thenReturn(Mono.just(0L));
            when(commentRepository.count()).thenReturn(Mono.just(0L));
            when(commentRepository.countByStatus("PENDING")).thenReturn(Mono.just(0L));
            when(userRepository.count()).thenReturn(Mono.just(1L)); // At least admin
            when(subscriberRepository.countConfirmed()).thenReturn(Mono.just(0L));
            when(articleRepository.sumViewsCount()).thenReturn(Mono.just(0L));
            when(tagRepository.count()).thenReturn(Mono.just(0L));

            StepVerifier.create(withAdminAuth(controller.getDashboardStats()))
                    .assertNext(stats -> {
                        assertThat(stats.get("totalArticles")).isEqualTo(0L);
                        assertThat(stats.get("totalUsers")).isEqualTo(1L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/dashboard/activity")
    class RecentActivity {

        @Test
        @DisplayName("Should return recent article activity sorted by date")
        void shouldReturnRecentActivity() {
            Article published = Article.builder()
                    .id(1L).title("Published Article").status("PUBLISHED")
                    .createdAt(LocalDateTime.now().minusDays(5))
                    .updatedAt(LocalDateTime.now().minusDays(1))
                    .build();

            Article draft = Article.builder()
                    .id(2L).title("Draft Article").status("DRAFT")
                    .createdAt(LocalDateTime.now().minusDays(2))
                    .build();

            when(articleRepository.findRecentlyUpdated(anyInt())).thenReturn(Flux.just(published, draft));

            StepVerifier.create(withAdminAuth(controller.getRecentActivity()))
                    .assertNext(activities -> {
                        assertThat(activities).isNotEmpty();
                        assertThat(activities.size()).isLessThanOrEqualTo(10);
                        
                        var first = activities.getFirst();
                        assertThat(first.get("type")).isEqualTo("article");
                        assertThat(first.get("title")).isNotNull();
                        assertThat(first.get("createdAt")).isNotNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty activity for no articles")
        void shouldReturnEmptyActivity() {
            when(articleRepository.findRecentlyUpdated(anyInt())).thenReturn(Flux.empty());

            StepVerifier.create(withAdminAuth(controller.getRecentActivity()))
                    .assertNext(activities -> assertThat(activities).isEmpty())
                    .verifyComplete();
        }
    }
}
