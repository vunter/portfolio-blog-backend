package dev.catananti.controller;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.dto.SearchRequest;
import dev.catananti.service.SearchService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private SearchService searchService;

    @InjectMocks
    private SearchController controller;

    private ArticleResponse javaArticle;
    private ArticleResponse springArticle;

    @BeforeEach
    void setUp() {
        javaArticle = ArticleResponse.builder()
                .id("2001")
                .slug("introducao-java-21")
                .title("Introdução ao Java 21")
                .excerpt("Novidades do Java 21 LTS")
                .status("PUBLISHED")
                .viewCount(250)
                .likeCount(45)
                .tags(Set.of())
                .publishedAt(LocalDateTime.now().minusDays(5))
                .createdAt(LocalDateTime.now().minusDays(10))
                .build();

        springArticle = ArticleResponse.builder()
                .id("2002")
                .slug("spring-boot-4-novidades")
                .title("Spring Boot 4: Novidades")
                .excerpt("O que esperar do Spring Boot 4")
                .status("PUBLISHED")
                .viewCount(180)
                .likeCount(32)
                .tags(Set.of())
                .publishedAt(LocalDateTime.now().minusDays(3))
                .createdAt(LocalDateTime.now().minusDays(7))
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/search")
    class SearchArticles {

        @Test
        @DisplayName("Should search articles by query text")
        void shouldSearchByQuery() {
            PageResponse<ArticleResponse> result = PageResponse.<ArticleResponse>builder()
                    .content(List.of(javaArticle))
                    .page(0).size(10).totalElements(1).totalPages(1)
                    .first(true).last(true)
                    .build();

            when(searchService.searchArticles(any(SearchRequest.class)))
                    .thenReturn(Mono.just(result));

            StepVerifier.create(controller.search("java", null, "date", "desc", 0, 10, null, null, null))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                        assertThat(page.getContent().getFirst().getTitle()).contains("Java");
                        assertThat(page.getTotalElements()).isEqualTo(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should search with tag filters")
        void shouldSearchWithTags() {
            PageResponse<ArticleResponse> result = PageResponse.<ArticleResponse>builder()
                    .content(List.of(springArticle))
                    .page(0).size(10).totalElements(1).totalPages(1)
                    .first(true).last(true)
                    .build();

            when(searchService.searchArticles(any(SearchRequest.class)))
                    .thenReturn(Mono.just(result));

            StepVerifier.create(controller.search(null, List.of("spring-boot"), "date", "desc", 0, 10, null, null, null))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty results for no match")
        void shouldReturnEmptyResults() {
            PageResponse<ArticleResponse> empty = PageResponse.<ArticleResponse>builder()
                    .content(List.of())
                    .page(0).size(10).totalElements(0).totalPages(0)
                    .first(true).last(true)
                    .build();

            when(searchService.searchArticles(any(SearchRequest.class)))
                    .thenReturn(Mono.just(empty));

            StepVerifier.create(controller.search("xylophone-quantum", null, "date", "desc", 0, 10, null, null, null))
                    .assertNext(page -> {
                        assertThat(page.getContent()).isEmpty();
                        assertThat(page.getTotalElements()).isZero();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle pagination parameters correctly")
        void shouldHandlePagination() {
            PageResponse<ArticleResponse> result = PageResponse.<ArticleResponse>builder()
                    .content(List.of(springArticle))
                    .page(2).size(5).totalElements(12).totalPages(3)
                    .first(false).last(true)
                    .build();

            when(searchService.searchArticles(any(SearchRequest.class)))
                    .thenReturn(Mono.just(result));

            StepVerifier.create(controller.search("spring", null, "views", "asc", 2, 5, null, null, null))
                    .assertNext(page -> {
                        assertThat(page.getPage()).isEqualTo(2);
                        assertThat(page.getSize()).isEqualTo(5);
                        assertThat(page.isFirst()).isFalse();
                        assertThat(page.isLast()).isTrue();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/search/suggestions")
    class SearchSuggestions {

        @Test
        @DisplayName("Should return search suggestions")
        void shouldReturnSuggestions() {
            when(searchService.getSuggestions("jav"))
                    .thenReturn(Flux.just("Introdução ao Java 21", "Java Collections Deep Dive"));

            StepVerifier.create(controller.getSuggestions("jav"))
                    .assertNext(suggestions -> {
                        assertThat(suggestions).hasSize(2);
                        assertThat(suggestions).contains("Introdução ao Java 21");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty suggestions for no match")
        void shouldReturnEmptySuggestions() {
            when(searchService.getSuggestions("zzz")).thenReturn(Flux.empty());

            StepVerifier.create(controller.getSuggestions("zzz"))
                    .assertNext(suggestions -> assertThat(suggestions).isEmpty())
                    .verifyComplete();
        }
    }
}
