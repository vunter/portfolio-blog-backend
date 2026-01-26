package dev.catananti.service;

import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleI18n;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ArticleI18nRepository;
import dev.catananti.repository.ArticleRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArticleTranslationServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private ArticleI18nRepository articleI18nRepository;

    @Mock
    private TranslationService translationService;

    @InjectMocks
    private ArticleTranslationService articleTranslationService;

    private Article testArticle;
    private ArticleI18n testI18n;

    @BeforeEach
    void setUp() {
        testArticle = Article.builder()
                .id(1L)
                .slug("test-article")
                .title("Test Article Title")
                .subtitle("Test Subtitle")
                .content("This is the test content of the article.")
                .excerpt("Test excerpt")
                .seoTitle("SEO Title")
                .seoDescription("SEO Description")
                .seoKeywords("test, article")
                .originalLocale("en")
                .status("PUBLISHED")
                .build();

        testI18n = ArticleI18n.builder()
                .articleId(1L)
                .locale("pt-br")
                .title("Titulo do Artigo de Teste")
                .subtitle("Subtitulo de Teste")
                .content("Este e o conteudo de teste do artigo.")
                .excerpt("Resumo de teste")
                .seoTitle("Titulo SEO")
                .seoDescription("Descricao SEO")
                .seoKeywords("teste, artigo")
                .autoTranslated(true)
                .reviewed(false)
                .translatedAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("isAvailable")
    class IsAvailable {

        @Test
        @DisplayName("should return true when translation service is available")
        void shouldReturnTrueWhenAvailable() {
            when(translationService.isAvailable()).thenReturn(true);
            assertThat(articleTranslationService.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("should return false when translation service is not available")
        void shouldReturnFalseWhenNotAvailable() {
            when(translationService.isAvailable()).thenReturn(false);
            assertThat(articleTranslationService.isAvailable()).isFalse();
        }
    }

    @Nested
    @DisplayName("translateArticle")
    class TranslateArticle {

        @Test
        @DisplayName("should translate article to target language")
        void shouldTranslateArticle() {
            when(translationService.isAvailable()).thenReturn(true);
            when(articleRepository.findById(1L)).thenReturn(Mono.just(testArticle));
            when(translationService.translateBatch(anyList(), eq("pt-br")))
                    .thenReturn(Mono.just(List.of(
                            "Titulo do Artigo de Teste",
                            "Subtitulo de Teste",
                            "Este e o conteudo de teste do artigo.",
                            "Resumo de teste",
                            "Titulo SEO",
                            "Descricao SEO",
                            "teste, artigo"
                    )));
            when(articleI18nRepository.upsert(any(ArticleI18n.class))).thenReturn(Mono.empty());

            StepVerifier.create(articleTranslationService.translateArticle(1L, "pt-br"))
                    .assertNext(i18n -> {
                        assertThat(i18n.getArticleId()).isEqualTo(1L);
                        assertThat(i18n.getLocale()).isEqualTo("pt-br");
                        assertThat(i18n.getTitle()).isEqualTo("Titulo do Artigo de Teste");
                        assertThat(i18n.getContent()).isEqualTo("Este e o conteudo de teste do artigo.");
                        assertThat(i18n.getAutoTranslated()).isTrue();
                        assertThat(i18n.getReviewed()).isFalse();
                    })
                    .verifyComplete();

            verify(articleI18nRepository).upsert(any(ArticleI18n.class));
        }

        @Test
        @DisplayName("should fail when translation service is not available")
        void shouldFailWhenNotAvailable() {
            when(translationService.isAvailable()).thenReturn(false);

            StepVerifier.create(articleTranslationService.translateArticle(1L, "pt-br"))
                    .expectError(IllegalStateException.class)
                    .verify();
        }

        @Test
        @DisplayName("should fail when article not found")
        void shouldFailWhenArticleNotFound() {
            when(translationService.isAvailable()).thenReturn(true);
            when(articleRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(articleTranslationService.translateArticle(999L, "pt-br"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("applyTranslation")
    class ApplyTranslation {

        @Test
        @DisplayName("should apply translation overlay when translation exists")
        void shouldApplyTranslation() {
            when(articleI18nRepository.findByArticleIdAndLocale(1L, "pt-br"))
                    .thenReturn(Mono.just(testI18n));

            StepVerifier.create(articleTranslationService.applyTranslation(testArticle, "pt-br"))
                    .assertNext(article -> {
                        assertThat(article.getTitle()).isEqualTo("Titulo do Artigo de Teste");
                        assertThat(article.getSubtitle()).isEqualTo("Subtitulo de Teste");
                        assertThat(article.getContent()).isEqualTo("Este e o conteudo de teste do artigo.");
                        assertThat(article.getExcerpt()).isEqualTo("Resumo de teste");
                        assertThat(article.getSeoTitle()).isEqualTo("Titulo SEO");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return original article when no translation exists")
        void shouldReturnOriginalWhenNoTranslation() {
            when(articleI18nRepository.findByArticleIdAndLocale(1L, "fr"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(articleTranslationService.applyTranslation(testArticle, "fr"))
                    .assertNext(article -> {
                        assertThat(article.getTitle()).isEqualTo("Test Article Title");
                        assertThat(article.getContent()).isEqualTo("This is the test content of the article.");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return original article when locale is null")
        void shouldReturnOriginalWhenLocaleNull() {
            StepVerifier.create(articleTranslationService.applyTranslation(testArticle, null))
                    .assertNext(article -> assertThat(article.getTitle()).isEqualTo("Test Article Title"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return original article when locale is en")
        void shouldReturnOriginalWhenLocaleEn() {
            StepVerifier.create(articleTranslationService.applyTranslation(testArticle, "en"))
                    .assertNext(article -> assertThat(article.getTitle()).isEqualTo("Test Article Title"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getAvailableLocales")
    class GetAvailableLocales {

        @Test
        @DisplayName("should return available locales for article")
        void shouldReturnLocales() {
            when(articleI18nRepository.findLocalesByArticleId(1L))
                    .thenReturn(Flux.just("pt-br", "es", "fr"));

            StepVerifier.create(articleTranslationService.getAvailableLocales(1L))
                    .expectNext("pt-br")
                    .expectNext("es")
                    .expectNext("fr")
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getTranslation")
    class GetTranslation {

        @Test
        @DisplayName("should return translation when it exists")
        void shouldReturnTranslation() {
            when(articleI18nRepository.findByArticleIdAndLocale(1L, "pt-br"))
                    .thenReturn(Mono.just(testI18n));

            StepVerifier.create(articleTranslationService.getTranslation(1L, "pt-br"))
                    .assertNext(i18n -> {
                        assertThat(i18n.getLocale()).isEqualTo("pt-br");
                        assertThat(i18n.getTitle()).isEqualTo("Titulo do Artigo de Teste");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty when translation not found")
        void shouldReturnEmptyWhenNotFound() {
            when(articleI18nRepository.findByArticleIdAndLocale(1L, "de"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(articleTranslationService.getTranslation(1L, "de"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("deleteTranslation")
    class DeleteTranslation {

        @Test
        @DisplayName("should delete translation")
        void shouldDeleteTranslation() {
            when(articleI18nRepository.deleteByArticleIdAndLocale(1L, "pt-br"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(articleTranslationService.deleteTranslation(1L, "pt-br"))
                    .verifyComplete();

            verify(articleI18nRepository).deleteByArticleIdAndLocale(1L, "pt-br");
        }
    }
}
