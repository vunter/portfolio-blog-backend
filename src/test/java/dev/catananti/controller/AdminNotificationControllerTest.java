package dev.catananti.controller;

import dev.catananti.service.NotificationEventService;
import dev.catananti.service.NotificationEventService.NotificationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminNotificationControllerTest {

    @Mock
    private NotificationEventService notificationEventService;

    @InjectMocks
    private AdminNotificationController controller;

    @Nested
    @DisplayName("GET /api/v1/admin/notifications/stream")
    class Stream {

        @Test
        @DisplayName("Should return SSE stream with notification events")
        void shouldReturnSseStreamWithEvents() {
            NotificationEvent event1 = new NotificationEvent(
                    "article", "published", "New Article",
                    Map.of("slug", "new-article"), LocalDateTime.now());
            NotificationEvent event2 = new NotificationEvent(
                    "comment", "created", "New comment on post",
                    Map.of("articleSlug", "some-post", "author", "Bob"), LocalDateTime.now());

            when(notificationEventService.subscribe()).thenReturn(Flux.just(event1, event2));

            // The stream method merges events + heartbeat; take only 2 events for testing
            StepVerifier.create(controller.stream().take(2))
                    .assertNext(sse -> {
                        assertThat(sse.event()).isEqualTo("article");
                        assertThat(sse.data()).isNotNull();
                        assertThat(sse.data().type()).isEqualTo("article");
                        assertThat(sse.data().action()).isEqualTo("published");
                        assertThat(sse.data().title()).isEqualTo("New Article");
                    })
                    .assertNext(sse -> {
                        assertThat(sse.event()).isEqualTo("comment");
                        assertThat(sse.data()).isNotNull();
                        assertThat(sse.data().type()).isEqualTo("comment");
                        assertThat(sse.data().action()).isEqualTo("created");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty stream when no events")
        void shouldReturnEmptyStreamWhenNoEvents() {
            when(notificationEventService.subscribe()).thenReturn(Flux.empty());

            // With empty events, only heartbeats will come - take 1 to verify the heartbeat
            StepVerifier.create(controller.stream().take(1))
                    .assertNext(sse -> {
                        // The first element is a heartbeat (comment only, no data, no event)
                        assertThat(sse.comment()).isEqualTo("heartbeat");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should wrap events as ServerSentEvent with correct type")
        void shouldWrapEventsAsServerSentEvent() {
            NotificationEvent event = new NotificationEvent(
                    "subscriber", "joined", "New subscriber",
                    Map.of("email", "test@example.com"), LocalDateTime.now());

            when(notificationEventService.subscribe()).thenReturn(Flux.just(event));

            StepVerifier.create(controller.stream().take(1))
                    .assertNext(sse -> {
                        assertThat(sse).isInstanceOf(ServerSentEvent.class);
                        assertThat(sse.event()).isEqualTo("subscriber");
                        assertThat(sse.data().title()).isEqualTo("New subscriber");
                    })
                    .verifyComplete();
        }
    }
}
