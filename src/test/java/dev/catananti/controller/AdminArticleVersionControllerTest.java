package dev.catananti.controller;

import dev.catananti.dto.ArticleVersionResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.User;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.ArticleVersionService;
import dev.catananti.service.ArticleVersionService.VersionDiff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminArticleVersionControllerTest {

    @Mock
    private ArticleVersionService versionService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminArticleVersionController controller;

    private ArticleVersionResponse buildVersion(int versionNumber) {
        return ArticleVersionResponse.builder()
                .id("1")
                .articleId("10")
                .versionNumber(versionNumber)
                .title("Test Article v" + versionNumber)
                .content("Content v" + versionNumber)
                .changeSummary("Edit " + versionNumber)
                .changedByName("admin")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/articles/{articleId}/versions")
    class GetVersionHistory {

        @Test
        @DisplayName("Should return version history with count")
        void shouldReturnVersionHistory() {
            ArticleVersionResponse v1 = buildVersion(1);
            ArticleVersionResponse v2 = buildVersion(2);

            when(versionService.getVersionHistory(10L)).thenReturn(Flux.just(v1, v2));
            when(versionService.getVersionCount(10L)).thenReturn(Mono.just(2L));

            StepVerifier.create(controller.getVersionHistory(10L))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("articleId")).isEqualTo(10L);
                        assertThat(body.get("totalVersions")).isEqualTo(2L);
                        assertThat(body.get("versions")).isNotNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty version history")
        void shouldReturnEmptyVersionHistory() {
            when(versionService.getVersionHistory(99L)).thenReturn(Flux.empty());
            when(versionService.getVersionCount(99L)).thenReturn(Mono.just(0L));

            StepVerifier.create(controller.getVersionHistory(99L))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("totalVersions")).isEqualTo(0L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/articles/{articleId}/versions/latest")
    class GetLatestVersion {

        @Test
        @DisplayName("Should return latest version")
        void shouldReturnLatestVersion() {
            ArticleVersionResponse latest = buildVersion(3);
            when(versionService.getLatestVersion(10L)).thenReturn(Mono.just(latest));

            StepVerifier.create(controller.getLatestVersion(10L))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().getVersionNumber()).isEqualTo(3);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/articles/{articleId}/versions/{versionNumber}")
    class GetVersion {

        @Test
        @DisplayName("Should return specific version")
        void shouldReturnSpecificVersion() {
            ArticleVersionResponse v2 = buildVersion(2);
            when(versionService.getVersion(10L, 2)).thenReturn(Mono.just(v2));

            StepVerifier.create(controller.getVersion(10L, 2))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().getVersionNumber()).isEqualTo(2);
                        assertThat(response.getBody().getTitle()).isEqualTo("Test Article v2");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/articles/{articleId}/versions/{versionNumber}/restore")
    class RestoreVersion {

        @Test
        @DisplayName("Should restore version successfully")
        void shouldRestoreVersion() {
            Authentication auth = mock(Authentication.class);
            when(auth.getName()).thenReturn("admin@test.com");

            User user = new User();
            user.setId(1L);
            user.setName("Admin");
            user.setEmail("admin@test.com");

            Article restoredArticle = Article.builder()
                    .id(10L)
                    .slug("test-article")
                    .build();

            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(user));
            when(versionService.restoreVersion(10L, 2, 1L, "Admin")).thenReturn(Mono.just(restoredArticle));

            StepVerifier.create(controller.restoreVersion(10L, 2, "Manual restore", auth))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("message")).isEqualTo("Article restored to version 2");
                        assertThat(body.get("articleId")).isEqualTo(10L);
                        assertThat(body.get("restoredVersion")).isEqualTo(2);
                        assertThat(body.get("currentSlug")).isEqualTo("test-article");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/articles/{articleId}/versions/compare")
    class CompareVersions {

        @Test
        @DisplayName("Should compare two versions")
        void shouldCompareTwoVersions() {
            VersionDiff diff = new VersionDiff(
                    10L, 1, 3,
                    true, false, true, false,
                    500,
                    LocalDateTime.now().minusDays(2),
                    LocalDateTime.now()
            );

            when(versionService.compareVersions(10L, 1, 3)).thenReturn(Mono.just(diff));

            StepVerifier.create(controller.compareVersions(10L, 1, 3))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        VersionDiff body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.articleId()).isEqualTo(10L);
                        assertThat(body.fromVersion()).isEqualTo(1);
                        assertThat(body.toVersion()).isEqualTo(3);
                        assertThat(body.titleChanged()).isTrue();
                        assertThat(body.contentChanged()).isTrue();
                        assertThat(body.subtitleChanged()).isFalse();
                        assertThat(body.contentLengthDiff()).isEqualTo(500);
                    })
                    .verifyComplete();
        }
    }
}
