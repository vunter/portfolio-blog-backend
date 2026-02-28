package dev.catananti.service;

import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleVersion;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.ArticleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArticleVersionServiceTest {

    @Mock
    private ArticleVersionRepository versionRepository;

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private IdService idService;

    @InjectMocks
    private ArticleVersionService versionService;

    private Article testArticle;
    private ArticleVersion testVersion;
    private Long articleId;

    @BeforeEach
    void setUp() {
        articleId = 1234567890123456789L;
        testArticle = Article.builder()
                .id(articleId)
                .slug("test-article")
                .title("Test Article")
                .subtitle("Test Subtitle")
                .content("Test Content")
                .excerpt("Test Excerpt")
                .status("PUBLISHED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        testVersion = ArticleVersion.builder()
                .id(987654321098765432L)
                .articleId(articleId)
                .versionNumber(1)
                .title("Test Article")
                .content("Test Content")
                .createdAt(LocalDateTime.now())
                .build();

        // F-185: createVersion now calls pruneOldVersions which needs countByArticleId
        lenient().when(versionRepository.countByArticleId(any())).thenReturn(Mono.just(1L));
    }

    @Test
    @DisplayName("Should create new version")
    void shouldCreateNewVersion() {
        when(versionRepository.findMaxVersionNumber(articleId)).thenReturn(Mono.just(0));
        when(idService.nextId()).thenReturn(555555555555555L);
        when(versionRepository.save(any(ArticleVersion.class))).thenAnswer(invocation -> {
            ArticleVersion version = invocation.getArgument(0);
            return Mono.just(version);
        });

        StepVerifier.create(versionService.createVersion(testArticle, "Initial version", 111L, "Admin"))
                .assertNext(version -> {
                    assertThat(version.getVersionNumber()).isEqualTo(1);
                    assertThat(version.getTitle()).isEqualTo("Test Article");
                    assertThat(version.getContent()).isEqualTo("Test Content");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should increment version number")
    void shouldIncrementVersionNumber() {
        when(versionRepository.findMaxVersionNumber(articleId)).thenReturn(Mono.just(5));
        when(idService.nextId()).thenReturn(666666666666666L);
        when(versionRepository.save(any(ArticleVersion.class))).thenAnswer(invocation -> {
            ArticleVersion version = invocation.getArgument(0);
            return Mono.just(version);
        });

        StepVerifier.create(versionService.createVersion(testArticle, "New version", 222L, "Admin"))
                .assertNext(version -> {
                    assertThat(version.getVersionNumber()).isEqualTo(6);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get version history")
    void shouldGetVersionHistory() {
        when(versionRepository.findByArticleIdOrderByVersionNumberDesc(articleId))
                .thenReturn(Flux.just(testVersion));

        StepVerifier.create(versionService.getVersionHistory(articleId).collectList())
                .assertNext(versions -> {
                    assertThat(versions).hasSize(1);
                    assertThat(versions.get(0).getVersionNumber()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get specific version")
    void shouldGetSpecificVersion() {
        when(versionRepository.findByArticleIdAndVersionNumber(articleId, 1))
                .thenReturn(Mono.just(testVersion));

        StepVerifier.create(versionService.getVersion(articleId, 1))
                .assertNext(version -> {
                    assertThat(version.getVersionNumber()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw error when version not found")
    void shouldThrowErrorWhenVersionNotFound() {
        when(versionRepository.findByArticleIdAndVersionNumber(articleId, 99))
                .thenReturn(Mono.empty());

        StepVerifier.create(versionService.getVersion(articleId, 99))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should get version count")
    void shouldGetVersionCount() {
        when(versionRepository.countByArticleId(articleId)).thenReturn(Mono.just(5L));

        StepVerifier.create(versionService.getVersionCount(articleId))
                .assertNext(count -> {
                    assertThat(count).isEqualTo(5L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should compare two versions")
    void shouldCompareTwoVersions() {
        ArticleVersion version1 = ArticleVersion.builder()
                .id(111111111111111L)
                .articleId(articleId)
                .versionNumber(1)
                .title("Title v1")
                .subtitle("Subtitle v1")
                .content("Content v1 - short")
                .excerpt("Excerpt v1")
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();

        ArticleVersion version2 = ArticleVersion.builder()
                .id(222222222222222L)
                .articleId(articleId)
                .versionNumber(2)
                .title("Title v2")
                .subtitle("Subtitle v1")
                .content("Content v2 - much longer content")
                .excerpt("Excerpt v2")
                .createdAt(LocalDateTime.now())
                .build();

        when(versionRepository.findByArticleIdAndVersionNumber(articleId, 1))
                .thenReturn(Mono.just(version1));
        when(versionRepository.findByArticleIdAndVersionNumber(articleId, 2))
                .thenReturn(Mono.just(version2));

        StepVerifier.create(versionService.compareVersions(articleId, 1, 2))
                .assertNext(diff -> {
                    assertThat(diff.titleChanged()).isTrue();
                    assertThat(diff.subtitleChanged()).isFalse();
                    assertThat(diff.contentChanged()).isTrue();
                    assertThat(diff.excerptChanged()).isTrue();
                })
                .verifyComplete();
    }

    @Nested
    @DisplayName("getLatestVersion")
    class GetLatestVersion {

        @Test
        @DisplayName("Should get latest version")
        void shouldGetLatestVersion() {
            when(versionRepository.findLatestByArticleId(articleId))
                    .thenReturn(Mono.just(testVersion));

            StepVerifier.create(versionService.getLatestVersion(articleId))
                    .assertNext(version -> {
                        assertThat(version.getVersionNumber()).isEqualTo(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw error when no versions found")
        void shouldThrowWhenNoVersions() {
            when(versionRepository.findLatestByArticleId(articleId))
                    .thenReturn(Mono.empty());

            StepVerifier.create(versionService.getLatestVersion(articleId))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("restoreVersion")
    class RestoreVersion {

        @Test
        @DisplayName("Should restore article to previous version")
        void shouldRestoreVersion() {
            ArticleVersion versionToRestore = ArticleVersion.builder()
                    .id(333333333333333L)
                    .articleId(articleId)
                    .versionNumber(1)
                    .title("Old Title")
                    .subtitle("Old Subtitle")
                    .content("Old Content")
                    .excerpt("Old Excerpt")
                    .coverImageUrl("old-cover.jpg")
                    .createdAt(LocalDateTime.now().minusDays(1))
                    .build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(versionRepository.findByArticleIdAndVersionNumber(articleId, 1))
                    .thenReturn(Mono.just(versionToRestore));
            when(versionRepository.findMaxVersionNumber(articleId)).thenReturn(Mono.just(1));
            when(idService.nextId()).thenReturn(444444444444444L);
            when(versionRepository.save(any(ArticleVersion.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleRepository.save(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(versionService.restoreVersion(articleId, 1, 100L, "Admin"))
                    .assertNext(article -> {
                        assertThat(article.getTitle()).isEqualTo("Old Title");
                        assertThat(article.getSubtitle()).isEqualTo("Old Subtitle");
                        assertThat(article.getContent()).isEqualTo("Old Content");
                        assertThat(article.getExcerpt()).isEqualTo("Old Excerpt");
                        assertThat(article.getCoverImageUrl()).isEqualTo("old-cover.jpg");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should error when article not found for restore")
        void shouldErrorWhenArticleNotFound() {
            when(articleRepository.findById(articleId)).thenReturn(Mono.empty());

            StepVerifier.create(versionService.restoreVersion(articleId, 1, 100L, "Admin"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should error when version not found for restore")
        void shouldErrorWhenVersionNotFound() {
            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(versionRepository.findByArticleIdAndVersionNumber(articleId, 99))
                    .thenReturn(Mono.empty());

            StepVerifier.create(versionService.restoreVersion(articleId, 99, 100L, "Admin"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("deleteVersionHistory")
    class DeleteVersionHistory {

        @Test
        @DisplayName("Should delete all versions for an article")
        void shouldDeleteAllVersions() {
            when(versionRepository.deleteByArticleId(articleId)).thenReturn(Mono.empty());

            StepVerifier.create(versionService.deleteVersionHistory(articleId))
                    .verifyComplete();

            verify(versionRepository).deleteByArticleId(articleId);
        }
    }

    @Nested
    @DisplayName("compareVersions - error cases")
    class CompareVersionsErrors {

        @Test
        @DisplayName("Should error when from version not found")
        void shouldErrorWhenFromVersionNotFound() {
            when(versionRepository.findByArticleIdAndVersionNumber(articleId, 1))
                    .thenReturn(Mono.empty());
            when(versionRepository.findByArticleIdAndVersionNumber(articleId, 2))
                    .thenReturn(Mono.just(testVersion));

            StepVerifier.create(versionService.compareVersions(articleId, 1, 2))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should error when to version not found")
        void shouldErrorWhenToVersionNotFound() {
            when(versionRepository.findByArticleIdAndVersionNumber(articleId, 1))
                    .thenReturn(Mono.just(testVersion));
            when(versionRepository.findByArticleIdAndVersionNumber(articleId, 2))
                    .thenReturn(Mono.empty());

            StepVerifier.create(versionService.compareVersions(articleId, 1, 2))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should handle null content in versions comparison")
        void shouldHandleNullContentInComparison() {
            ArticleVersion v1 = ArticleVersion.builder()
                    .id(111L).articleId(articleId).versionNumber(1)
                    .title("Title").content(null).createdAt(LocalDateTime.now().minusDays(1))
                    .build();
            ArticleVersion v2 = ArticleVersion.builder()
                    .id(222L).articleId(articleId).versionNumber(2)
                    .title("Title").content("Some content").createdAt(LocalDateTime.now())
                    .build();

            when(versionRepository.findByArticleIdAndVersionNumber(articleId, 1))
                    .thenReturn(Mono.just(v1));
            when(versionRepository.findByArticleIdAndVersionNumber(articleId, 2))
                    .thenReturn(Mono.just(v2));

            StepVerifier.create(versionService.compareVersions(articleId, 1, 2))
                    .assertNext(diff -> {
                        assertThat(diff.contentChanged()).isTrue();
                        assertThat(diff.contentLengthDiff()).isEqualTo(12);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle both versions with null content")
        void shouldHandleBothNullContent() {
            ArticleVersion v1 = ArticleVersion.builder()
                    .id(111L).articleId(articleId).versionNumber(1)
                    .title("Title").content(null).createdAt(LocalDateTime.now().minusDays(1))
                    .build();
            ArticleVersion v2 = ArticleVersion.builder()
                    .id(222L).articleId(articleId).versionNumber(2)
                    .title("Title").content(null).createdAt(LocalDateTime.now())
                    .build();

            when(versionRepository.findByArticleIdAndVersionNumber(articleId, 1))
                    .thenReturn(Mono.just(v1));
            when(versionRepository.findByArticleIdAndVersionNumber(articleId, 2))
                    .thenReturn(Mono.just(v2));

            StepVerifier.create(versionService.compareVersions(articleId, 1, 2))
                    .assertNext(diff -> {
                        assertThat(diff.contentChanged()).isFalse();
                        assertThat(diff.contentLengthDiff()).isZero();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("createVersion - default version number")
    class CreateVersionDefaults {

        @Test
        @DisplayName("Should default to version 1 when no previous versions exist")
        void shouldDefaultToVersionOne() {
            when(versionRepository.findMaxVersionNumber(articleId)).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(777777777777777L);
            when(versionRepository.save(any(ArticleVersion.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(versionService.createVersion(testArticle, "First", 111L, "Admin"))
                    .assertNext(version -> {
                        assertThat(version.getVersionNumber()).isEqualTo(1);
                        assertThat(version.getChangeSummary()).isEqualTo("First");
                        assertThat(version.getChangedByName()).isEqualTo("Admin");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getVersionHistory - empty")
    class GetVersionHistoryEmpty {

        @Test
        @DisplayName("Should return empty list when no versions")
        void shouldReturnEmpty() {
            when(versionRepository.findByArticleIdOrderByVersionNumberDesc(articleId))
                    .thenReturn(Flux.empty());

            StepVerifier.create(versionService.getVersionHistory(articleId).collectList())
                    .assertNext(list -> assertThat(list).isEmpty())
                    .verifyComplete();
        }
    }
}
