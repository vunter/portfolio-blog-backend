package dev.catananti.service;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.entity.Bookmark;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.BookmarkRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookmarkServiceTest {

    @Mock
    private BookmarkRepository bookmarkRepository;

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private ArticleService articleService;

    @Mock
    private IdService idService;

    @InjectMocks
    private BookmarkService bookmarkService;

    private Article testArticle;
    private Bookmark testBookmark;
    private ArticleResponse testArticleResponse;
    private Long articleId;
    private String visitorId;
    private String articleSlug;

    @BeforeEach
    void setUp() {
        articleId = 1234567890123456L;
        visitorId = "visitor-abc-123";
        articleSlug = "test-article";

        testArticle = Article.builder()
                .id(articleId)
                .slug(articleSlug)
                .title("Test Article")
                .content("Some content")
                .status(ArticleStatus.PUBLISHED)
                .build();

        testBookmark = Bookmark.builder()
                .id(9876543210L)
                .articleId(articleId)
                .visitorHash("hashed-visitor")
                .createdAt(LocalDateTime.now())
                .build();

        testArticleResponse = ArticleResponse.builder()
                .id(articleId.toString())
                .slug(articleSlug)
                .title("Test Article")
                .status("PUBLISHED")
                .build();
    }

    // ==================== hashVisitorId ====================

    @Nested
    @DisplayName("hashVisitorId")
    class HashVisitorId {

        @Test
        @DisplayName("Should produce deterministic SHA-256 hash")
        void shouldProduceDeterministicHash() {
            String hash1 = bookmarkService.hashVisitorId("visitor-abc-123");
            String hash2 = bookmarkService.hashVisitorId("visitor-abc-123");

            assertThat(hash1).isEqualTo(hash2);
            assertThat(hash1).hasSize(64); // SHA-256 = 64 hex chars
        }

        @Test
        @DisplayName("Should produce different hashes for different inputs")
        void shouldProduceDifferentHashesForDifferentInputs() {
            String hash1 = bookmarkService.hashVisitorId("visitor-1");
            String hash2 = bookmarkService.hashVisitorId("visitor-2");

            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("Should produce lowercase hex string")
        void shouldProduceLowercaseHex() {
            String hash = bookmarkService.hashVisitorId("test");

            assertThat(hash).matches("^[0-9a-f]{64}$");
        }
    }

    // ==================== getBookmarks ====================

    @Nested
    @DisplayName("getBookmarks")
    class GetBookmarks {

        @Test
        @DisplayName("Should return paginated bookmarks")
        void shouldReturnPaginatedBookmarks() {
            // Given
            String hashedId = bookmarkService.hashVisitorId(visitorId);
            when(bookmarkRepository.findByVisitorHash(eq(hashedId), eq(10), eq(0)))
                    .thenReturn(Flux.just(testBookmark));
            when(bookmarkRepository.countByVisitorHash(hashedId))
                    .thenReturn(Mono.just(1L));
            when(articleRepository.findAllById(java.util.List.of(articleId)))
                    .thenReturn(Flux.just(testArticle));
            when(articleService.enrichArticlesWithMetadata(anyList()))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(testArticle))
                    .thenReturn(testArticleResponse);

            // When & Then
            StepVerifier.create(bookmarkService.getBookmarks(visitorId, 0, 10))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                        assertThat(page.getContent().getFirst().getSlug()).isEqualTo(articleSlug);
                        assertThat(page.getTotalElements()).isEqualTo(1);
                        assertThat(page.getPage()).isZero();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should load the page of articles in batch, preserving bookmark order")
        void shouldBatchLoadArticlesPreservingOrder() {
            // Given two bookmarks whose articles come back from the DB in a different order
            String hashedId = bookmarkService.hashVisitorId(visitorId);
            Long secondArticleId = 2222222222222222L;
            Article secondArticle = Article.builder()
                    .id(secondArticleId)
                    .slug("second-article")
                    .title("Second Article")
                    .content("More content")
                    .status(ArticleStatus.PUBLISHED)
                    .build();
            Bookmark secondBookmark = Bookmark.builder()
                    .id(9876543211L)
                    .articleId(secondArticleId)
                    .visitorHash("hashed-visitor")
                    .createdAt(LocalDateTime.now())
                    .build();
            ArticleResponse secondResponse = ArticleResponse.builder()
                    .id(secondArticleId.toString())
                    .slug("second-article")
                    .title("Second Article")
                    .status("PUBLISHED")
                    .build();

            when(bookmarkRepository.findByVisitorHash(eq(hashedId), eq(10), eq(0)))
                    .thenReturn(Flux.just(secondBookmark, testBookmark));
            when(bookmarkRepository.countByVisitorHash(hashedId))
                    .thenReturn(Mono.just(2L));
            when(articleRepository.findAllById(java.util.List.of(secondArticleId, articleId)))
                    .thenReturn(Flux.just(testArticle, secondArticle));
            when(articleService.enrichArticlesWithMetadata(anyList()))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(testArticle)).thenReturn(testArticleResponse);
            when(articleService.mapToResponse(secondArticle)).thenReturn(secondResponse);

            // When & Then: the response follows bookmark order, not DB return order
            StepVerifier.create(bookmarkService.getBookmarks(visitorId, 0, 10))
                    .assertNext(page -> {
                        assertThat(page.getContent()).extracting(ArticleResponse::getSlug)
                                .containsExactly("second-article", articleSlug);
                        assertThat(page.getTotalElements()).isEqualTo(2);
                    })
                    .verifyComplete();

            verify(articleRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("Should return empty page when no bookmarks exist")
        void shouldReturnEmptyPage() {
            // Given
            String hashedId = bookmarkService.hashVisitorId(visitorId);
            when(bookmarkRepository.findByVisitorHash(eq(hashedId), eq(10), eq(0)))
                    .thenReturn(Flux.empty());
            when(bookmarkRepository.countByVisitorHash(hashedId))
                    .thenReturn(Mono.just(0L));

            // When & Then
            StepVerifier.create(bookmarkService.getBookmarks(visitorId, 0, 10))
                    .assertNext(page -> {
                        assertThat(page.getContent()).isEmpty();
                        assertThat(page.getTotalElements()).isZero();
                    })
                    .verifyComplete();
        }
    }

    // ==================== addBookmark ====================

    @Nested
    @DisplayName("addBookmark")
    class AddBookmark {

        @Test
        @DisplayName("Should add new bookmark successfully")
        void shouldAddNewBookmark() {
            // Given
            String hashedId = bookmarkService.hashVisitorId(visitorId);
            when(articleRepository.findBySlug(articleSlug))
                    .thenReturn(Mono.just(testArticle));
            when(bookmarkRepository.findByArticleIdAndVisitorHash(articleId, hashedId))
                    .thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(999L);
            when(bookmarkRepository.save(any(Bookmark.class)))
                    .thenReturn(Mono.just(testBookmark));

            // When & Then
            StepVerifier.create(bookmarkService.addBookmark(visitorId, articleSlug))
                    .expectNext(true)
                    .verifyComplete();

            verify(bookmarkRepository).save(any(Bookmark.class));
        }

        @Test
        @DisplayName("Should return true when bookmark already exists (dedup)")
        void shouldReturnTrueWhenAlreadyBookmarked() {
            // Given
            String hashedId = bookmarkService.hashVisitorId(visitorId);
            when(articleRepository.findBySlug(articleSlug))
                    .thenReturn(Mono.just(testArticle));
            when(bookmarkRepository.findByArticleIdAndVisitorHash(articleId, hashedId))
                    .thenReturn(Mono.just(testBookmark));

            // When & Then
            StepVerifier.create(bookmarkService.addBookmark(visitorId, articleSlug))
                    .expectNext(true)
                    .verifyComplete();

            verify(bookmarkRepository, never()).save(any(Bookmark.class));
        }

        @Test
        @DisplayName("Should return false when article not found")
        void shouldReturnFalseWhenArticleNotFound() {
            // Given
            when(articleRepository.findBySlug("non-existent"))
                    .thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(bookmarkService.addBookmark(visitorId, "non-existent"))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    // ==================== removeBookmark ====================

    @Nested
    @DisplayName("removeBookmark")
    class RemoveBookmark {

        @Test
        @DisplayName("Should remove bookmark successfully")
        void shouldRemoveBookmark() {
            // Given
            String hashedId = bookmarkService.hashVisitorId(visitorId);
            when(articleRepository.findBySlug(articleSlug))
                    .thenReturn(Mono.just(testArticle));
            when(bookmarkRepository.deleteByArticleIdAndVisitorHash(articleId, hashedId))
                    .thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(bookmarkService.removeBookmark(visitorId, articleSlug))
                    .verifyComplete();

            verify(bookmarkRepository).deleteByArticleIdAndVisitorHash(articleId, hashedId);
        }

        @Test
        @DisplayName("Should complete when article not found")
        void shouldCompleteWhenArticleNotFound() {
            // Given
            when(articleRepository.findBySlug("non-existent"))
                    .thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(bookmarkService.removeBookmark(visitorId, "non-existent"))
                    .verifyComplete();
        }
    }
}
