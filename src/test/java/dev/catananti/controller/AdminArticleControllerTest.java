package dev.catananti.controller;

import dev.catananti.dto.ArticleI18nResponse;
import dev.catananti.dto.ArticleRequest;
import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.ArticleI18n;
import dev.catananti.service.ArticleAdminService;
import dev.catananti.service.ArticleService;
import dev.catananti.service.ArticleTranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminArticleControllerTest {

    @Mock
    private ArticleAdminService articleAdminService;

    @Mock
    private ArticleService articleService;

    @Mock
    private ArticleTranslationService articleTranslationService;

    @InjectMocks
    private AdminArticleController controller;

    private ArticleResponse publishedArticle;
    private ArticleResponse draftArticle;
    private ArticleRequest articleRequest;

    @BeforeEach
    void setUp() {
        publishedArticle = ArticleResponse.builder()
                .id("1001")
                .slug("spring-boot-guide")
                .title("Spring Boot Guide")
                .status("PUBLISHED")
                .publishedAt(LocalDateTime.now().minusDays(5))
                .viewCount(150)
                .build();

        draftArticle = ArticleResponse.builder()
                .id("1002")
                .slug("reactive-programming")
                .title("Reactive Programming")
                .status("DRAFT")
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();

        articleRequest = ArticleRequest.builder()
                .slug("new-article")
                .title("New Article")
                .content("This is the article content for testing purposes.")
                .status("DRAFT")
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/articles")
    class GetAllArticles {

        @Test
        @DisplayName("Should return articles without status filter")
        void shouldReturnAllArticles() {
            PageResponse<ArticleResponse> page = PageResponse.<ArticleResponse>builder()
                    .content(List.of(publishedArticle, draftArticle))
                    .page(0)
                    .size(10)
                    .totalElements(2)
                    .totalPages(1)
                    .first(true)
                    .last(true)
                    .build();

            when(articleAdminService.getAllArticles(0, 10, null))
                    .thenReturn(Mono.just(page));

            StepVerifier.create(controller.getAllArticles(0, 10, null, null))
                    .assertNext(result -> {
                        assertThat(result.getContent()).hasSize(2);
                        assertThat(result.getTotalElements()).isEqualTo(2);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return articles filtered by status")
        void shouldReturnFilteredByStatus() {
            PageResponse<ArticleResponse> page = PageResponse.<ArticleResponse>builder()
                    .content(List.of(publishedArticle))
                    .page(0)
                    .size(10)
                    .totalElements(1)
                    .totalPages(1)
                    .first(true)
                    .last(true)
                    .build();

            when(articleAdminService.getAllArticles(0, 10, "PUBLISHED"))
                    .thenReturn(Mono.just(page));

            StepVerifier.create(controller.getAllArticles(0, 10, "PUBLISHED", null))
                    .assertNext(result -> {
                        assertThat(result.getContent()).hasSize(1);
                        assertThat(result.getContent().getFirst().getStatus()).isEqualTo("PUBLISHED");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should search articles when search param provided")
        void shouldSearchArticles() {
            PageResponse<ArticleResponse> page = PageResponse.<ArticleResponse>builder()
                    .content(List.of(publishedArticle))
                    .page(0)
                    .size(10)
                    .totalElements(1)
                    .totalPages(1)
                    .first(true)
                    .last(true)
                    .build();

            when(articleService.searchArticles("Spring", 0, 10))
                    .thenReturn(Mono.just(page));

            StepVerifier.create(controller.getAllArticles(0, 10, null, "Spring"))
                    .assertNext(result -> {
                        assertThat(result.getContent()).hasSize(1);
                    })
                    .verifyComplete();

            verify(articleService).searchArticles("Spring", 0, 10);
            verifyNoInteractions(articleAdminService);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/articles/{id}")
    class GetArticleById {

        @Test
        @DisplayName("Should return article when found")
        void shouldReturnArticleWhenFound() {
            when(articleAdminService.getArticleById(1001L))
                    .thenReturn(Mono.just(publishedArticle));

            StepVerifier.create(controller.getArticleById(1001L))
                    .assertNext(result -> {
                        assertThat(result.getId()).isEqualTo("1001");
                        assertThat(result.getTitle()).isEqualTo("Spring Boot Guide");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should complete empty when article not found")
        void shouldCompleteEmptyWhenNotFound() {
            when(articleAdminService.getArticleById(9999L))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.getArticleById(9999L))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/articles")
    class CreateArticle {

        @Test
        @DisplayName("Should create article")
        void shouldCreateArticle() {
            ArticleResponse created = ArticleResponse.builder()
                    .id("1003")
                    .slug("new-article")
                    .title("New Article")
                    .status("DRAFT")
                    .createdAt(LocalDateTime.now())
                    .build();

            when(articleAdminService.createArticle(articleRequest))
                    .thenReturn(Mono.just(created));

            StepVerifier.create(controller.createArticle(articleRequest))
                    .assertNext(result -> {
                        assertThat(result.getId()).isEqualTo("1003");
                        assertThat(result.getSlug()).isEqualTo("new-article");
                        assertThat(result.getStatus()).isEqualTo("DRAFT");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/articles/{id}")
    class UpdateArticle {

        @Test
        @DisplayName("Should update article")
        void shouldUpdateArticle() {
            ArticleResponse updated = ArticleResponse.builder()
                    .id("1001")
                    .slug("spring-boot-guide")
                    .title("Updated Title")
                    .status("PUBLISHED")
                    .build();

            when(articleAdminService.updateArticle(1001L, articleRequest))
                    .thenReturn(Mono.just(updated));

            StepVerifier.create(controller.updateArticle(1001L, articleRequest))
                    .assertNext(result -> {
                        assertThat(result.getTitle()).isEqualTo("Updated Title");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/articles/{id}")
    class DeleteArticle {

        @Test
        @DisplayName("Should delete article")
        void shouldDeleteArticle() {
            when(articleAdminService.deleteArticle(1001L))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteArticle(1001L))
                    .verifyComplete();

            verify(articleAdminService).deleteArticle(1001L);
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/admin/articles/{id}/publish")
    class PublishArticle {

        @Test
        @DisplayName("Should publish article")
        void shouldPublishArticle() {
            ArticleResponse published = ArticleResponse.builder()
                    .id("1002")
                    .status("PUBLISHED")
                    .publishedAt(LocalDateTime.now())
                    .build();

            when(articleAdminService.publishArticle(1002L))
                    .thenReturn(Mono.just(published));

            StepVerifier.create(controller.publishArticle(1002L))
                    .assertNext(result -> assertThat(result.getStatus()).isEqualTo("PUBLISHED"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/admin/articles/{id}/unpublish")
    class UnpublishArticle {

        @Test
        @DisplayName("Should unpublish article")
        void shouldUnpublishArticle() {
            ArticleResponse unpublished = ArticleResponse.builder()
                    .id("1001")
                    .status("DRAFT")
                    .build();

            when(articleAdminService.unpublishArticle(1001L))
                    .thenReturn(Mono.just(unpublished));

            StepVerifier.create(controller.unpublishArticle(1001L))
                    .assertNext(result -> assertThat(result.getStatus()).isEqualTo("DRAFT"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/admin/articles/{id}/archive")
    class ArchiveArticle {

        @Test
        @DisplayName("Should archive article")
        void shouldArchiveArticle() {
            ArticleResponse archived = ArticleResponse.builder()
                    .id("1001")
                    .status("ARCHIVED")
                    .build();

            when(articleAdminService.archiveArticle(1001L))
                    .thenReturn(Mono.just(archived));

            StepVerifier.create(controller.archiveArticle(1001L))
                    .assertNext(result -> assertThat(result.getStatus()).isEqualTo("ARCHIVED"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Translation Endpoints")
    class TranslationEndpoints {

        @Test
        @DisplayName("Should translate article to supported locale")
        void shouldTranslateArticle() {
            ArticleI18n i18n = ArticleI18n.builder()
                    .articleId(1001L)
                    .locale("pt")
                    .title("Guia Spring Boot")
                    .content("Conteúdo traduzido")
                    .autoTranslated(true)
                    .reviewed(false)
                    .translatedAt(LocalDateTime.now())
                    .build();

            when(articleTranslationService.translateArticle(1001L, "pt"))
                    .thenReturn(Mono.just(i18n));

            StepVerifier.create(controller.translateArticle(1001L, "pt"))
                    .assertNext(result -> {
                        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                        assertThat(result.getBody()).isNotNull();
                        assertThat(result.getBody().locale()).isEqualTo("pt");
                        assertThat(result.getBody().title()).isEqualTo("Guia Spring Boot");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject unsupported locale")
        void shouldRejectUnsupportedLocale() {
            StepVerifier.create(controller.translateArticle(1001L, "xx"))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                            && ex.getMessage().contains("Unsupported locale"))
                    .verify();
        }

        @Test
        @DisplayName("Should get all translations for article")
        void shouldGetTranslations() {
            ArticleI18n i18nPt = ArticleI18n.builder()
                    .articleId(1001L)
                    .locale("pt")
                    .title("Guia Spring Boot")
                    .translatedAt(LocalDateTime.now())
                    .build();

            ArticleI18n i18nEs = ArticleI18n.builder()
                    .articleId(1001L)
                    .locale("es")
                    .title("Guía Spring Boot")
                    .translatedAt(LocalDateTime.now())
                    .build();

            when(articleTranslationService.getTranslations(1001L))
                    .thenReturn(Flux.just(i18nPt, i18nEs));

            StepVerifier.create(controller.getTranslations(1001L))
                    .assertNext(list -> {
                        assertThat(list).hasSize(2);
                        assertThat(list.get(0).locale()).isEqualTo("pt");
                        assertThat(list.get(1).locale()).isEqualTo("es");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should delete translation")
        void shouldDeleteTranslation() {
            when(articleTranslationService.deleteTranslation(1001L, "pt"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteTranslation(1001L, "pt"))
                    .verifyComplete();

            verify(articleTranslationService).deleteTranslation(1001L, "pt");
        }
    }
}
