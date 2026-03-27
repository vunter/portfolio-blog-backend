package dev.catananti.controller;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.ReadingHistoryService;
import dev.catananti.service.ReadingHistoryService.ReadingHistoryResponse;
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
class ReadingHistoryControllerTest {

    @Mock
    private ReadingHistoryService readingHistoryService;

    @InjectMocks
    private ReadingHistoryController controller;

    @Nested
    @DisplayName("GET /api/v1/admin/reading-history")
    class GetReadingHistory {

        @Test
        @DisplayName("Should return paginated reading history")
        void shouldReturnPaginatedReadingHistory() {
            ArticleResponse article = ArticleResponse.builder()
                    .id("1001")
                    .slug("spring-boot-guide")
                    .title("Spring Boot Guide")
                    .status("PUBLISHED")
                    .build();

            ReadingHistoryResponse entry = new ReadingHistoryResponse(
                    article,
                    LocalDateTime.now().minusHours(2),
                    3
            );

            PageResponse<ReadingHistoryResponse> page = PageResponse.<ReadingHistoryResponse>builder()
                    .content(List.of(entry))
                    .page(0)
                    .size(20)
                    .totalElements(1)
                    .totalPages(1)
                    .first(true)
                    .last(true)
                    .build();

            when(readingHistoryService.getReadingHistory("user@test.com", 0, 20))
                    .thenReturn(Mono.just(page));

            StepVerifier.create(controller.getReadingHistory("user@test.com", 0, 20))
                    .assertNext(result -> {
                        assertThat(result.getContent()).hasSize(1);
                        assertThat(result.getTotalElements()).isEqualTo(1);
                        assertThat(result.getPage()).isEqualTo(0);
                        assertThat(result.getContent().getFirst().article().getTitle())
                                .isEqualTo("Spring Boot Guide");
                        assertThat(result.getContent().getFirst().readCount()).isEqualTo(3);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty page when no history exists")
        void shouldReturnEmptyPageWhenNoHistory() {
            PageResponse<ReadingHistoryResponse> emptyPage = PageResponse.<ReadingHistoryResponse>builder()
                    .content(List.of())
                    .page(0)
                    .size(20)
                    .totalElements(0)
                    .totalPages(0)
                    .first(true)
                    .last(true)
                    .build();

            when(readingHistoryService.getReadingHistory("user@test.com", 0, 20))
                    .thenReturn(Mono.just(emptyPage));

            StepVerifier.create(controller.getReadingHistory("user@test.com", 0, 20))
                    .assertNext(result -> {
                        assertThat(result.getContent()).isEmpty();
                        assertThat(result.getTotalElements()).isEqualTo(0);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should pass custom pagination parameters")
        void shouldPassCustomPaginationParams() {
            PageResponse<ReadingHistoryResponse> page = PageResponse.<ReadingHistoryResponse>builder()
                    .content(List.of())
                    .page(2)
                    .size(10)
                    .totalElements(25)
                    .totalPages(3)
                    .first(false)
                    .last(true)
                    .build();

            when(readingHistoryService.getReadingHistory("user@test.com", 2, 10))
                    .thenReturn(Mono.just(page));

            StepVerifier.create(controller.getReadingHistory("user@test.com", 2, 10))
                    .assertNext(result -> {
                        assertThat(result.getPage()).isEqualTo(2);
                        assertThat(result.getSize()).isEqualTo(10);
                        assertThat(result.getTotalElements()).isEqualTo(25);
                        assertThat(result.isFirst()).isFalse();
                        assertThat(result.isLast()).isTrue();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/reading-history")
    class ClearHistory {

        @Test
        @DisplayName("Should clear reading history")
        void shouldClearHistory() {
            when(readingHistoryService.clearHistory("user@test.com"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.clearHistory("user@test.com"))
                    .verifyComplete();

            verify(readingHistoryService).clearHistory("user@test.com");
        }
    }
}
