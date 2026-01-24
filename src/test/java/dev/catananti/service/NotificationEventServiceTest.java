package dev.catananti.service;

import dev.catananti.service.NotificationEventService.NotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEventServiceTest {

    private NotificationEventService notificationEventService;

    @BeforeEach
    void setUp() {
        notificationEventService = new NotificationEventService();
    }

    // ==================== subscribe ====================

    @Nested
    @DisplayName("subscribe")
    class Subscribe {

        @Test
        @DisplayName("Should receive published events via subscription")
        void shouldReceivePublishedEvents() {
            // Given — subscribe first, then publish
            var flux = notificationEventService.subscribe();

            // When & Then
            StepVerifier.create(flux.take(1))
                    .then(() -> notificationEventService.publish(
                            "test", "action", "Test Event", java.util.Map.of("key", "value")))
                    .assertNext(event -> {
                        assertThat(event.type()).isEqualTo("test");
                        assertThat(event.action()).isEqualTo("action");
                        assertThat(event.title()).isEqualTo("Test Event");
                        assertThat(event.data()).containsEntry("key", "value");
                        assertThat(event.timestamp()).isNotNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should receive multiple events in order")
        void shouldReceiveMultipleEventsInOrder() {
            var flux = notificationEventService.subscribe();

            StepVerifier.create(flux.take(2))
                    .then(() -> {
                        notificationEventService.publish("type1", "action1", "First", java.util.Map.of());
                        notificationEventService.publish("type2", "action2", "Second", java.util.Map.of());
                    })
                    .assertNext(event -> assertThat(event.type()).isEqualTo("type1"))
                    .assertNext(event -> assertThat(event.type()).isEqualTo("type2"))
                    .verifyComplete();
        }
    }

    // ==================== publish ====================

    @Nested
    @DisplayName("publish")
    class Publish {

        @Test
        @DisplayName("Should emit event with correct type, action, and title")
        void shouldEmitEventWithCorrectFields() {
            var flux = notificationEventService.subscribe();

            StepVerifier.create(flux.take(1))
                    .then(() -> notificationEventService.publish(
                            "article", "published", "My Article", java.util.Map.of("slug", "my-article")))
                    .assertNext(event -> {
                        assertThat(event.type()).isEqualTo("article");
                        assertThat(event.action()).isEqualTo("published");
                        assertThat(event.title()).isEqualTo("My Article");
                        assertThat(event.data()).containsEntry("slug", "my-article");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should not fail when no subscribers")
        void shouldNotFailWithNoSubscribers() {
            // When — publish without any subscriber
            notificationEventService.publish("test", "action", "title", java.util.Map.of());

            // Then — no exception, message is buffered
            // Verify by subscribing after and checking the buffer is limited
            // The service uses onBackpressureBuffer(256), so this should not throw
        }
    }

    // ==================== Shortcut methods ====================

    @Nested
    @DisplayName("Shortcut methods")
    class ShortcutMethods {

        @Test
        @DisplayName("articlePublished should emit article/published event")
        void articlePublished() {
            var flux = notificationEventService.subscribe();

            StepVerifier.create(flux.take(1))
                    .then(() -> notificationEventService.articlePublished("My Post", "my-post"))
                    .assertNext(event -> {
                        assertThat(event.type()).isEqualTo("article");
                        assertThat(event.action()).isEqualTo("published");
                        assertThat(event.title()).isEqualTo("My Post");
                        assertThat(event.data()).containsEntry("slug", "my-post");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("commentReceived should emit comment/created event")
        void commentReceived() {
            var flux = notificationEventService.subscribe();

            StepVerifier.create(flux.take(1))
                    .then(() -> notificationEventService.commentReceived("my-article", "Jane"))
                    .assertNext(event -> {
                        assertThat(event.type()).isEqualTo("comment");
                        assertThat(event.action()).isEqualTo("created");
                        assertThat(event.title()).contains("my-article");
                        assertThat(event.data()).containsEntry("articleSlug", "my-article");
                        assertThat(event.data()).containsEntry("author", "Jane");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("subscriberJoined should emit subscriber/joined event")
        void subscriberJoined() {
            var flux = notificationEventService.subscribe();

            StepVerifier.create(flux.take(1))
                    .then(() -> notificationEventService.subscriberJoined("new@user.com"))
                    .assertNext(event -> {
                        assertThat(event.type()).isEqualTo("subscriber");
                        assertThat(event.action()).isEqualTo("joined");
                        assertThat(event.data()).containsEntry("email", "new@user.com");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("contactReceived should emit contact/received event")
        void contactReceived() {
            var flux = notificationEventService.subscribe();

            StepVerifier.create(flux.take(1))
                    .then(() -> notificationEventService.contactReceived("Alice"))
                    .assertNext(event -> {
                        assertThat(event.type()).isEqualTo("contact");
                        assertThat(event.action()).isEqualTo("received");
                        assertThat(event.title()).contains("Alice");
                        assertThat(event.data()).containsEntry("name", "Alice");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("articleCreated should emit article/created event")
        void articleCreated() {
            var flux = notificationEventService.subscribe();

            StepVerifier.create(flux.take(1))
                    .then(() -> notificationEventService.articleCreated("Draft Post", "draft-post"))
                    .assertNext(event -> {
                        assertThat(event.type()).isEqualTo("article");
                        assertThat(event.action()).isEqualTo("created");
                        assertThat(event.title()).isEqualTo("Draft Post");
                        assertThat(event.data()).containsEntry("slug", "draft-post");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("userLoggedIn should emit auth/login event")
        void userLoggedIn() {
            var flux = notificationEventService.subscribe();

            StepVerifier.create(flux.take(1))
                    .then(() -> notificationEventService.userLoggedIn("admin@example.com"))
                    .assertNext(event -> {
                        assertThat(event.type()).isEqualTo("auth");
                        assertThat(event.action()).isEqualTo("login");
                        assertThat(event.data()).containsEntry("email", "admin@example.com");
                    })
                    .verifyComplete();
        }
    }
}
