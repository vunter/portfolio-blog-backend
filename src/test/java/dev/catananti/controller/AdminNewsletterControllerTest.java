package dev.catananti.controller;

import dev.catananti.dto.PageResponse;
import dev.catananti.dto.SubscriberResponse;
import dev.catananti.entity.Subscriber;
import dev.catananti.service.NewsletterService;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminNewsletterControllerTest {

    @Mock
    private NewsletterService newsletterService;

    @InjectMocks
    private AdminNewsletterController controller;

    private SubscriberResponse subscriber1;
    private SubscriberResponse subscriber2;

    @BeforeEach
    void setUp() {
        subscriber1 = SubscriberResponse.builder()
                .id("1")
                .email("alice@example.com")
                .name("Alice")
                .status("CONFIRMED")
                .subscribedAt(LocalDateTime.now().minusDays(30))
                .confirmedAt(LocalDateTime.now().minusDays(29))
                .build();

        subscriber2 = SubscriberResponse.builder()
                .id("2")
                .email("bob@example.com")
                .name("Bob")
                .status("CONFIRMED")
                .subscribedAt(LocalDateTime.now().minusDays(20))
                .confirmedAt(LocalDateTime.now().minusDays(19))
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/newsletter/subscribers")
    class GetAllSubscribers {

        @Test
        @DisplayName("Should return paginated subscribers")
        void shouldReturnPaginatedSubscribers() {
            PageResponse<SubscriberResponse> page = PageResponse.<SubscriberResponse>builder()
                    .content(List.of(subscriber1, subscriber2))
                    .page(0)
                    .size(20)
                    .totalElements(2)
                    .totalPages(1)
                    .first(true)
                    .last(true)
                    .build();

            when(newsletterService.getAllSubscribersPaginated(null, null, 0, 20))
                    .thenReturn(Mono.just(page));

            StepVerifier.create(controller.getAllSubscribers(null, null, 0, 20))
                    .assertNext(result -> {
                        assertThat(result.getContent()).hasSize(2);
                        assertThat(result.getContent().getFirst().getEmail()).isEqualTo("alice@example.com");
                        assertThat(result.getTotalElements()).isEqualTo(2);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/newsletter/stats")
    class GetStats {

        @Test
        @DisplayName("Should return newsletter statistics")
        void shouldReturnStats() {
            Map<String, Long> stats = Map.of(
                    "total", 150L,
                    "confirmed", 120L,
                    "pending", 25L,
                    "unsubscribed", 5L
            );

            when(newsletterService.getStats()).thenReturn(Mono.just(stats));

            StepVerifier.create(controller.getStats())
                    .assertNext(result -> {
                        assertThat(result).containsEntry("total", 150L);
                        assertThat(result).containsEntry("confirmed", 120L);
                        assertThat(result).containsEntry("pending", 25L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/newsletter/subscribers/{id}")
    class DeleteSubscriber {

        @Test
        @DisplayName("Should delete subscriber by id")
        void shouldDeleteSubscriber() {
            when(newsletterService.deleteSubscriber(1L))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteSubscriber(1L))
                    .verifyComplete();

            verify(newsletterService).deleteSubscriber(1L);
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/newsletter/subscribers/delete-batch")
    class DeleteBatch {

        @Test
        @DisplayName("Should batch delete subscribers when confirmed")
        void shouldBatchDeleteSubscribers() {
            List<Long> ids = List.of(1L, 2L, 3L);

            when(newsletterService.deleteSubscribersBatch(ids))
                    .thenReturn(Mono.just(3L));

            StepVerifier.create(controller.deleteBatch(Map.of("ids", ids), true))
                    .assertNext(result -> {
                        assertThat(result).containsEntry("message", "Subscribers deleted");
                        assertThat(result).containsEntry("count", (Object) 3L);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject batch exceeding 1000 IDs")
        void shouldRejectBatchExceeding1000() {
            List<Long> ids = LongStream.rangeClosed(1, 1001).boxed().toList();

            StepVerifier.create(controller.deleteBatch(Map.of("ids", ids), true))
                    .assertNext(result -> {
                        assertThat(result).containsEntry("message", "Too many IDs. Maximum is 1000.");
                        assertThat(result).containsEntry("count", (Object) 0);
                    })
                    .verifyComplete();

            verifyNoInteractions(newsletterService);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/newsletter/export")
    class ExportSubscribers {

        @Test
        @DisplayName("Should export subscribers as CSV")
        void shouldExportSubscribersAsCsv() {
            Subscriber sub1 = Subscriber.builder()
                    .id(1L)
                    .email("alice@example.com")
                    .name("Alice")
                    .status("CONFIRMED")
                    .createdAt(LocalDateTime.of(2026, 1, 15, 10, 0))
                    .build();
            Subscriber sub2 = Subscriber.builder()
                    .id(2L)
                    .email("bob@example.com")
                    .name("Bob")
                    .status("CONFIRMED")
                    .createdAt(LocalDateTime.of(2026, 1, 20, 14, 30))
                    .build();

            when(newsletterService.getActiveSubscribers())
                    .thenReturn(Flux.just(sub1, sub2));

            StepVerifier.create(controller.exportSubscribers())
                    .assertNext(csv -> {
                        assertThat(csv).startsWith("email,name,status,subscribed_at");
                        assertThat(csv).contains("alice@example.com");
                        assertThat(csv).contains("bob@example.com");
                    })
                    .verifyComplete();
        }
    }
}
