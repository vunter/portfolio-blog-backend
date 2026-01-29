package dev.catananti.controller;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.BookmarkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookmarkControllerTest {

    @Mock
    private BookmarkService bookmarkService;

    @InjectMocks
    private BookmarkController controller;

    private static final String VISITOR_ID = "visitor-abc-12345678";
    private static final String ARTICLE_SLUG = "spring-boot-guide";

    private ArticleResponse article;

    @BeforeEach
    void setUp() {
        article = ArticleResponse.builder()
                .id("1001")
                .slug(ARTICLE_SLUG)
                .title("Spring Boot Guide")
                .status("PUBLISHED")
                .publishedAt(LocalDateTime.now().minusDays(5))
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/bookmarks")
    class GetBookmarks {

        @Test
        @DisplayName("Should return bookmarked articles for visitor")
        void shouldReturnBookmarkedArticles() {
            PageResponse<ArticleResponse> page = PageResponse.<ArticleResponse>builder()
                    .content(List.of(article))
                    .page(0)
                    .size(10)
                    .totalElements(1)
                    .totalPages(1)
                    .first(true)
                    .last(true)
                    .build();

            when(bookmarkService.getBookmarks(VISITOR_ID, 0, 10))
                    .thenReturn(Mono.just(page));

            StepVerifier.create(controller.getBookmarks(VISITOR_ID, 0, 10))
                    .assertNext(result -> {
                        assertThat(result.getContent()).hasSize(1);
                        assertThat(result.getContent().getFirst().getSlug()).isEqualTo(ARTICLE_SLUG);
                        assertThat(result.getTotalElements()).isEqualTo(1);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/bookmarks/{articleSlug}")
    class IsBookmarked {

        @Test
        @DisplayName("Should return true when article is bookmarked")
        void shouldReturnTrueWhenBookmarked() {
            when(bookmarkService.isBookmarked(VISITOR_ID, ARTICLE_SLUG))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(controller.isBookmarked(VISITOR_ID, ARTICLE_SLUG))
                    .assertNext(status -> assertThat(status.bookmarked()).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when article is not bookmarked")
        void shouldReturnFalseWhenNotBookmarked() {
            when(bookmarkService.isBookmarked(VISITOR_ID, ARTICLE_SLUG))
                    .thenReturn(Mono.just(false));

            StepVerifier.create(controller.isBookmarked(VISITOR_ID, ARTICLE_SLUG))
                    .assertNext(status -> assertThat(status.bookmarked()).isFalse())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bookmarks/{articleSlug}")
    class AddBookmark {

        @Test
        @DisplayName("Should add bookmark and return status true")
        void shouldAddBookmark() {
            when(bookmarkService.addBookmark(VISITOR_ID, ARTICLE_SLUG))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(controller.addBookmark(VISITOR_ID, ARTICLE_SLUG))
                    .assertNext(status -> assertThat(status.bookmarked()).isTrue())
                    .verifyComplete();

            verify(bookmarkService).addBookmark(VISITOR_ID, ARTICLE_SLUG);
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/bookmarks/{articleSlug}")
    class RemoveBookmark {

        @Test
        @DisplayName("Should remove bookmark")
        void shouldRemoveBookmark() {
            when(bookmarkService.removeBookmark(VISITOR_ID, ARTICLE_SLUG))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.removeBookmark(VISITOR_ID, ARTICLE_SLUG))
                    .verifyComplete();

            verify(bookmarkService).removeBookmark(VISITOR_ID, ARTICLE_SLUG);
        }
    }
}
