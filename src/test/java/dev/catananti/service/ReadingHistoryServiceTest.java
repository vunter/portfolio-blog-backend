package dev.catananti.service;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.entity.ReadingHistory;
import dev.catananti.entity.User;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.ReadingHistoryRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.ReadingHistoryService.ReadingHistoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
class ReadingHistoryServiceTest {

    @Mock
    private ReadingHistoryRepository readingHistoryRepository;

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private ArticleService articleService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private IdService idService;

    @InjectMocks
    private ReadingHistoryService readingHistoryService;

    private User testUser;
    private Article testArticle;
    private ReadingHistory testHistory;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(100L)
                .email("reader@example.com")
                .name("Reader")
                .role("VIEWER")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        testArticle = Article.builder()
                .id(200L)
                .slug("test-article")
                .title("Test Article")
                .authorId(1L)
                .status(ArticleStatus.PUBLISHED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        testHistory = ReadingHistory.builder()
                .id(300L)
                .userId(100L)
                .articleId(200L)
                .readCount(1)
                .lastReadAt(LocalDateTime.now())
                .build();
    }

    // ==================== recordReading ====================

    @Test
    @DisplayName("recordReading should create new history entry when none exists")
    void recordReading_ShouldCreateNewEntry_WhenNoneExists() {
        ReadingHistory created = ReadingHistory.builder()
                .id(300L)
                .userId(100L)
                .articleId(200L)
                .readCount(1)
                .lastReadAt(LocalDateTime.now())
                .build();

        when(idService.nextId()).thenReturn(300L);
        when(readingHistoryRepository.upsertReading(eq(300L), eq(100L), eq(200L), any(LocalDateTime.class)))
                .thenReturn(Mono.just(created));

        StepVerifier.create(readingHistoryService.recordReading(100L, 200L))
                .verifyComplete();

        verify(readingHistoryRepository).upsertReading(eq(300L), eq(100L), eq(200L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("recordReading should increment read count when entry exists")
    void recordReading_ShouldIncrementReadCount_WhenEntryExists() {
        // The DB now performs the increment atomically via upsert; the returned row
        // already carries the incremented read_count (was 3, now 4).
        ReadingHistory incremented = ReadingHistory.builder()
                .id(300L)
                .userId(100L)
                .articleId(200L)
                .readCount(4)
                .lastReadAt(LocalDateTime.now())
                .build();

        when(idService.nextId()).thenReturn(300L);
        when(readingHistoryRepository.upsertReading(eq(300L), eq(100L), eq(200L), any(LocalDateTime.class)))
                .thenReturn(Mono.just(incremented));

        StepVerifier.create(readingHistoryService.recordReading(100L, 200L))
                .verifyComplete();

        verify(readingHistoryRepository).upsertReading(eq(300L), eq(100L), eq(200L), any(LocalDateTime.class));
    }

    // ==================== recordReadingByEmailAndSlug ====================

    @Test
    @DisplayName("recordReadingByEmailAndSlug should resolve user and article then record")
    void recordReadingByEmailAndSlug_ShouldResolveAndRecord() {
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Mono.just(testUser));
        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(idService.nextId()).thenReturn(300L);
        when(readingHistoryRepository.upsertReading(eq(300L), eq(100L), eq(200L), any(LocalDateTime.class)))
                .thenReturn(Mono.just(testHistory));

        StepVerifier.create(readingHistoryService.recordReadingByEmailAndSlug("reader@example.com", "test-article"))
                .verifyComplete();

        verify(userRepository).findByEmail("reader@example.com");
        verify(articleRepository).findBySlug("test-article");
        verify(readingHistoryRepository).upsertReading(eq(300L), eq(100L), eq(200L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("recordReadingByEmailAndSlug should complete empty when user not found")
    void recordReadingByEmailAndSlug_ShouldCompleteEmpty_WhenUserNotFound() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Mono.empty());

        StepVerifier.create(readingHistoryService.recordReadingByEmailAndSlug("unknown@example.com", "test-article"))
                .verifyComplete();

        verify(readingHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordReadingByEmailAndSlug should complete empty when article not found")
    void recordReadingByEmailAndSlug_ShouldCompleteEmpty_WhenArticleNotFound() {
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Mono.just(testUser));
        when(articleRepository.findBySlug("nonexistent")).thenReturn(Mono.empty());

        StepVerifier.create(readingHistoryService.recordReadingByEmailAndSlug("reader@example.com", "nonexistent"))
                .verifyComplete();

        verify(readingHistoryRepository, never()).save(any());
    }

    // ==================== getReadingHistory ====================

    @Test
    @DisplayName("getReadingHistory should return paginated history with enriched articles")
    void getReadingHistory_ShouldReturnPaginatedHistory() {
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Mono.just(testUser));
        when(readingHistoryRepository.findByUserIdOrderByLastReadAtDesc(100L, 10, 0))
                .thenReturn(Flux.just(testHistory));
        when(articleRepository.findById(200L)).thenReturn(Mono.just(testArticle));
        when(articleService.enrichArticleWithMetadata(any(Article.class))).thenReturn(Mono.just(testArticle));

        ArticleResponse articleResponse = ArticleResponse.builder()
                .id("200")
                .slug("test-article")
                .title("Test Article")
                .build();
        when(articleService.mapToResponse(any(Article.class))).thenReturn(articleResponse);
        when(readingHistoryRepository.countByUserId(100L)).thenReturn(Mono.just(1L));

        StepVerifier.create(readingHistoryService.getReadingHistory("reader@example.com", 0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(1);
                    assertThat(page.getTotalElements()).isEqualTo(1);
                    assertThat(page.getPage()).isEqualTo(0);
                    assertThat(page.getSize()).isEqualTo(10);
                    assertThat(page.isFirst()).isTrue();
                    assertThat(page.isLast()).isTrue();

                    ReadingHistoryResponse entry = page.getContent().getFirst();
                    assertThat(entry.article().getSlug()).isEqualTo("test-article");
                    assertThat(entry.readCount()).isEqualTo(1);
                    assertThat(entry.lastReadAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getReadingHistory should return empty page when user not found")
    void getReadingHistory_ShouldReturnEmptyPage_WhenUserNotFound() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Mono.empty());

        StepVerifier.create(readingHistoryService.getReadingHistory("unknown@example.com", 0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).isEmpty();
                    assertThat(page.getTotalElements()).isEqualTo(0);
                    assertThat(page.getPage()).isEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getReadingHistory should return empty page when user has no history")
    void getReadingHistory_ShouldReturnEmptyPage_WhenNoHistory() {
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Mono.just(testUser));
        when(readingHistoryRepository.findByUserIdOrderByLastReadAtDesc(100L, 10, 0))
                .thenReturn(Flux.empty());
        when(readingHistoryRepository.countByUserId(100L)).thenReturn(Mono.just(0L));

        StepVerifier.create(readingHistoryService.getReadingHistory("reader@example.com", 0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).isEmpty();
                    assertThat(page.getTotalElements()).isEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getReadingHistory should handle pagination offset correctly")
    void getReadingHistory_ShouldHandlePaginationOffset() {
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Mono.just(testUser));
        when(readingHistoryRepository.findByUserIdOrderByLastReadAtDesc(100L, 5, 5))
                .thenReturn(Flux.just(testHistory));
        when(articleRepository.findById(200L)).thenReturn(Mono.just(testArticle));
        when(articleService.enrichArticleWithMetadata(any(Article.class))).thenReturn(Mono.just(testArticle));
        when(articleService.mapToResponse(any(Article.class))).thenReturn(
                ArticleResponse.builder().id("200").slug("test-article").build());
        when(readingHistoryRepository.countByUserId(100L)).thenReturn(Mono.just(6L));

        StepVerifier.create(readingHistoryService.getReadingHistory("reader@example.com", 1, 5))
                .assertNext(page -> {
                    assertThat(page.getPage()).isEqualTo(1);
                    assertThat(page.getSize()).isEqualTo(5);
                    assertThat(page.getTotalElements()).isEqualTo(6);
                    assertThat(page.getTotalPages()).isEqualTo(2);
                    assertThat(page.isFirst()).isFalse();
                    assertThat(page.isLast()).isTrue();
                })
                .verifyComplete();

        verify(readingHistoryRepository).findByUserIdOrderByLastReadAtDesc(100L, 5, 5);
    }

    @Test
    @DisplayName("getReadingHistory should return multiple entries")
    void getReadingHistory_ShouldReturnMultipleEntries() {
        ReadingHistory history2 = ReadingHistory.builder()
                .id(301L).userId(100L).articleId(201L)
                .readCount(5).lastReadAt(LocalDateTime.now().minusHours(1))
                .build();
        Article article2 = Article.builder()
                .id(201L).slug("second-article").title("Second Article")
                .authorId(1L).status(ArticleStatus.PUBLISHED)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        when(userRepository.findByEmail("reader@example.com")).thenReturn(Mono.just(testUser));
        when(readingHistoryRepository.findByUserIdOrderByLastReadAtDesc(100L, 10, 0))
                .thenReturn(Flux.just(testHistory, history2));
        when(articleRepository.findById(200L)).thenReturn(Mono.just(testArticle));
        when(articleRepository.findById(201L)).thenReturn(Mono.just(article2));
        when(articleService.enrichArticleWithMetadata(any(Article.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(articleService.mapToResponse(any(Article.class)))
                .thenAnswer(inv -> {
                    Article a = inv.getArgument(0);
                    return ArticleResponse.builder()
                            .id(String.valueOf(a.getId()))
                            .slug(a.getSlug())
                            .build();
                });
        when(readingHistoryRepository.countByUserId(100L)).thenReturn(Mono.just(2L));

        StepVerifier.create(readingHistoryService.getReadingHistory("reader@example.com", 0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(2);
                    assertThat(page.getTotalElements()).isEqualTo(2);
                })
                .verifyComplete();
    }

    // ==================== clearHistory ====================

    @Test
    @DisplayName("clearHistory should delete all history for the user")
    void clearHistory_ShouldDeleteAllHistoryForUser() {
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Mono.just(testUser));
        when(readingHistoryRepository.deleteByUserId(100L)).thenReturn(Mono.empty());

        StepVerifier.create(readingHistoryService.clearHistory("reader@example.com"))
                .verifyComplete();

        verify(readingHistoryRepository).deleteByUserId(100L);
    }

    @Test
    @DisplayName("clearHistory should complete when user not found")
    void clearHistory_ShouldComplete_WhenUserNotFound() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Mono.empty());

        StepVerifier.create(readingHistoryService.clearHistory("unknown@example.com"))
                .verifyComplete();

        verify(readingHistoryRepository, never()).deleteByUserId(anyLong());
    }
}
