package dev.catananti.service;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.dto.SearchRequest;
import dev.catananti.entity.Article;
import dev.catananti.entity.User;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.TagRepository;
import dev.catananti.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private RowsFetchSpec tagsFetchSpec;

    @InjectMocks
    private SearchService searchService;

    private Article publishedArticle;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        // Mock the r2dbcTemplate.getDatabaseClient() chain used by enrichArticleWithTags
        lenient().when(r2dbcTemplate.getDatabaseClient()).thenReturn(databaseClient);
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.map(any(BiFunction.class))).thenReturn(tagsFetchSpec);
        lenient().when(tagsFetchSpec.all()).thenReturn(Flux.empty());
        lenient().when(tagsFetchSpec.one()).thenReturn(Mono.empty());

        publishedArticle = Article.builder()
                .id(1L)
                .slug("test-article")
                .title("Test Article")
                .subtitle("Subtitle")
                .content("Article content about Java")
                .excerpt("Short excerpt")
                .authorId(100L)
                .status("PUBLISHED")
                .publishedAt(LocalDateTime.now().minusDays(1))
                .readingTimeMinutes(5)
                .viewsCount(100)
                .likesCount(10)
                .createdAt(LocalDateTime.now().minusDays(2))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    @Nested
    @DisplayName("searchArticles")
    class SearchArticles {

        @Test
        @DisplayName("Should return recent articles when no query or tags provided")
        void shouldReturnRecentArticles_WhenNoQueryOrTags() {
            SearchRequest request = SearchRequest.builder()
                    .query("")
                    .page(0)
                    .size(10)
                    .build();

            when(articleRepository.findByStatusOrderByPublishedAtDesc("PUBLISHED", 10, 0))
                    .thenReturn(Flux.just(publishedArticle));
            when(articleRepository.countByStatus("PUBLISHED"))
                    .thenReturn(Mono.just(1L));
            when(userRepository.findAllById(any(Iterable.class)))
                    .thenReturn(Flux.just(User.builder().id(100L).name("Author").build()));

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                        assertThat(page.getTotalElements()).isEqualTo(1);
                        assertThat(page.getPage()).isEqualTo(0);
                        assertThat(page.getSize()).isEqualTo(10);
                        assertThat(page.isFirst()).isTrue();
                        assertThat(page.isLast()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should search by query text")
        void shouldSearchByQuery() {
            SearchRequest request = SearchRequest.builder()
                    .query("Java")
                    .page(0)
                    .size(10)
                    .build();

            when(articleRepository.searchByStatusAndQuery("PUBLISHED", "Java", 10, 0))
                    .thenReturn(Flux.just(publishedArticle));
            when(articleRepository.countSearchByStatusAndQuery("PUBLISHED", "Java"))
                    .thenReturn(Mono.just(1L));
            when(userRepository.findAllById(any(Iterable.class)))
                    .thenReturn(Flux.just(User.builder().id(100L).name("Author").build()));

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                        ArticleResponse article = page.getContent().getFirst();
                        assertThat(article.getTitle()).isEqualTo("Test Article");
                        assertThat(article.getSlug()).isEqualTo("test-article");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty page when no results")
        void shouldReturnEmptyPage_WhenNoResults() {
            SearchRequest request = SearchRequest.builder()
                    .query("nonexistent-topic")
                    .page(0)
                    .size(10)
                    .build();

            when(articleRepository.searchByStatusAndQuery("PUBLISHED", "nonexistent-topic", 10, 0))
                    .thenReturn(Flux.empty());
            when(articleRepository.countSearchByStatusAndQuery("PUBLISHED", "nonexistent-topic"))
                    .thenReturn(Mono.just(0L));

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> {
                        assertThat(page.getContent()).isEmpty();
                        assertThat(page.getTotalElements()).isEqualTo(0);
                        assertThat(page.getTotalPages()).isEqualTo(0);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle null query as empty string")
        void shouldHandleNullQuery() {
            SearchRequest request = SearchRequest.builder()
                    .query(null)
                    .page(0)
                    .size(10)
                    .build();

            when(articleRepository.findByStatusOrderByPublishedAtDesc("PUBLISHED", 10, 0))
                    .thenReturn(Flux.empty());
            when(articleRepository.countByStatus("PUBLISHED"))
                    .thenReturn(Mono.just(0L));

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> assertThat(page.getContent()).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should calculate pagination metadata correctly")
        void shouldCalculatePaginationCorrectly() {
            SearchRequest request = SearchRequest.builder()
                    .query("")
                    .page(1)
                    .size(5)
                    .build();

            when(articleRepository.findByStatusOrderByPublishedAtDesc("PUBLISHED", 5, 5))
                    .thenReturn(Flux.just(publishedArticle));
            when(articleRepository.countByStatus("PUBLISHED"))
                    .thenReturn(Mono.just(12L));
            when(userRepository.findAllById(any(Iterable.class)))
                    .thenReturn(Flux.just(User.builder().id(100L).name("Author").build()));

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> {
                        assertThat(page.getPage()).isEqualTo(1);
                        assertThat(page.getSize()).isEqualTo(5);
                        assertThat(page.getTotalElements()).isEqualTo(12);
                        assertThat(page.getTotalPages()).isEqualTo(3);
                        assertThat(page.isFirst()).isFalse();
                        assertThat(page.isLast()).isFalse();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getSuggestions")
    class GetSuggestions {

        @Test
        @DisplayName("Should return empty when prefix is null")
        void shouldReturnEmpty_WhenPrefixNull() {
            StepVerifier.create(searchService.getSuggestions(null))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty when prefix is too short")
        void shouldReturnEmpty_WhenPrefixTooShort() {
            StepVerifier.create(searchService.getSuggestions("a"))
                    .verifyComplete();
        }
    }

    // ==================== ADDED TESTS ====================

    @Nested
    @DisplayName("searchArticles with tags")
    class SearchArticlesWithTags {

        @Test
        @DisplayName("Should search by query and tags")
        @SuppressWarnings("unchecked")
        void shouldSearchByQueryAndTags() {
            SearchRequest request = SearchRequest.builder()
                    .query("Java")
                    .tags(List.of("spring"))
                    .page(0)
                    .size(10)
                    .build();

            // The tag+query path uses r2dbcTemplate dynamic SQL
            // executeSpec chain is already mocked in setUp - returns empty
            when(tagsFetchSpec.all()).thenReturn(Flux.empty());

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> {
                        assertThat(page.getContent()).isEmpty();
                        assertThat(page.getTotalElements()).isEqualTo(0);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty when all tags are invalid")
        @SuppressWarnings("unchecked")
        void shouldReturnEmpty_WhenAllTagsInvalid() {
            SearchRequest request = SearchRequest.builder()
                    .query("Java")
                    .tags(List.of("invalid tag!", "<script>", ""))
                    .page(0)
                    .size(10)
                    .build();

            // Invalid tags are filtered → sanitizedTags is empty → Flux.empty()
            // countByQueryAndTags returns 0 for empty tags
            // Since query is not empty and tags are present, it goes to searchByQueryAndTags branch
            // which returns Flux.empty() and Mono.just(0L)
            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> {
                        assertThat(page.getContent()).isEmpty();
                        assertThat(page.getTotalElements()).isEqualTo(0);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("searchArticles with date range")
    class SearchArticlesWithDateRange {

        @Test
        @DisplayName("Should search with dateFrom only")
        @SuppressWarnings("unchecked")
        void shouldSearchWithDateFrom() {
            SearchRequest request = SearchRequest.builder()
                    .query("")
                    .dateFrom(java.time.LocalDate.of(2026, 1, 1))
                    .page(0)
                    .size(10)
                    .build();

            // Date range path uses r2dbcTemplate dynamic SQL
            when(tagsFetchSpec.all()).thenReturn(Flux.empty());

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> assertThat(page.getContent()).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should search with dateTo only")
        @SuppressWarnings("unchecked")
        void shouldSearchWithDateTo() {
            SearchRequest request = SearchRequest.builder()
                    .query("")
                    .dateTo(java.time.LocalDate.of(2026, 12, 31))
                    .page(0)
                    .size(10)
                    .build();

            when(tagsFetchSpec.all()).thenReturn(Flux.empty());

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> assertThat(page.getContent()).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should search with both dateFrom and dateTo plus query")
        @SuppressWarnings("unchecked")
        void shouldSearchWithDateRangeAndQuery() {
            SearchRequest request = SearchRequest.builder()
                    .query("Spring")
                    .dateFrom(java.time.LocalDate.of(2026, 1, 1))
                    .dateTo(java.time.LocalDate.of(2026, 6, 30))
                    .page(0)
                    .size(10)
                    .build();

            when(tagsFetchSpec.all()).thenReturn(Flux.empty());

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> assertThat(page.getContent()).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should search with tags and date range")
        @SuppressWarnings("unchecked")
        void shouldSearchWithTagsAndDateRange() {
            SearchRequest request = SearchRequest.builder()
                    .query("Java")
                    .tags(List.of("spring"))
                    .dateFrom(java.time.LocalDate.of(2026, 1, 1))
                    .dateTo(java.time.LocalDate.of(2026, 12, 31))
                    .page(0)
                    .size(10)
                    .build();

            when(tagsFetchSpec.all()).thenReturn(Flux.empty());

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> assertThat(page.getContent()).isEmpty())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("searchArticles pagination edge cases")
    class SearchPaginationEdgeCases {

        @Test
        @DisplayName("Should handle page 0 with single result")
        void shouldHandleSingleResultFirstPage() {
            SearchRequest request = SearchRequest.builder()
                    .query("")
                    .page(0)
                    .size(1)
                    .build();

            when(articleRepository.findByStatusOrderByPublishedAtDesc("PUBLISHED", 1, 0))
                    .thenReturn(Flux.just(publishedArticle));
            when(articleRepository.countByStatus("PUBLISHED"))
                    .thenReturn(Mono.just(5L));
            when(userRepository.findAllById(any(Iterable.class)))
                    .thenReturn(Flux.just(User.builder().id(100L).name("Author").build()));

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> {
                        assertThat(page.getPage()).isEqualTo(0);
                        assertThat(page.getSize()).isEqualTo(1);
                        assertThat(page.getTotalElements()).isEqualTo(5);
                        assertThat(page.getTotalPages()).isEqualTo(5);
                        assertThat(page.isFirst()).isTrue();
                        assertThat(page.isLast()).isFalse();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle last page")
        void shouldHandleLastPage() {
            SearchRequest request = SearchRequest.builder()
                    .query("")
                    .page(2)
                    .size(5)
                    .build();

            when(articleRepository.findByStatusOrderByPublishedAtDesc("PUBLISHED", 5, 10))
                    .thenReturn(Flux.just(publishedArticle));
            when(articleRepository.countByStatus("PUBLISHED"))
                    .thenReturn(Mono.just(11L));
            when(userRepository.findAllById(any(Iterable.class)))
                    .thenReturn(Flux.just(User.builder().id(100L).name("Author").build()));

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> {
                        assertThat(page.getTotalPages()).isEqualTo(3);
                        assertThat(page.isLast()).isTrue();
                        assertThat(page.isFirst()).isFalse();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("searchArticles sort options")
    class SearchSortOptions {

        @Test
        @DisplayName("Should search with views sort (via tags path)")
        @SuppressWarnings("unchecked")
        void shouldSearchWithViewsSort() {
            SearchRequest request = SearchRequest.builder()
                    .query("test")
                    .tags(List.of("java"))
                    .sortBy("views")
                    .page(0)
                    .size(10)
                    .build();

            when(tagsFetchSpec.all()).thenReturn(Flux.empty());

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> assertThat(page.getContent()).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should search with title sort (via tags path)")
        @SuppressWarnings("unchecked")
        void shouldSearchWithTitleSort() {
            SearchRequest request = SearchRequest.builder()
                    .query("test")
                    .tags(List.of("java"))
                    .sortBy("title")
                    .page(0)
                    .size(10)
                    .build();

            when(tagsFetchSpec.all()).thenReturn(Flux.empty());

            StepVerifier.create(searchService.searchArticles(request))
                    .assertNext(page -> assertThat(page.getContent()).isEmpty())
                    .verifyComplete();
        }
    }
}
