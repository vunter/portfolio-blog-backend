package dev.catananti.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Service for broadcasting real-time notification events to connected admin clients via SSE.
 * Uses a Sinks.Many (multicast) to broadcast events to all subscribers.
 */
@Service
@Slf4j
public class NotificationEventService {

    // Buffer size is bounded; oldest events are dropped on overflow to prevent memory pressure
    private final Sinks.Many<NotificationEvent> sink = Sinks.many().multicast().onBackpressureBuffer(256, false);

    /**
     * Subscribe to the notification event stream.
     * Each admin client gets a Flux that emits events as they occur.
     */
    public Flux<NotificationEvent> subscribe() {
        return sink.asFlux();
    }

    /**
     * Publish a notification event to all connected clients.
     */
    public void publish(String type, String action, String title, Map<String, Object> data) {
        var event = new NotificationEvent(type, action, title, data, LocalDateTime.now());
        var result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("Failed to emit notification event: {}", result);
        } else {
            log.debug("Notification event published: {} - {} - {}", type, action, title);
        }
    }

    public void articlePublished(String articleTitle, String slug) {
        publish(NotificationType.ARTICLE.value(), "published", articleTitle, Map.of("slug", slug));
    }

    public void articleCreated(String articleTitle, String slug) {
        publish(NotificationType.ARTICLE.value(), "created", articleTitle, Map.of("slug", slug));
    }

    public void commentReceived(String articleSlug, String authorName) {
        publish(NotificationType.COMMENT.value(), "created", "New comment on " + articleSlug, Map.of("articleSlug", articleSlug, "author", authorName));
    }

    public void commentApproved(Long commentId) {
        publish(NotificationType.COMMENT.value(), "approved", "Comment approved", Map.of("commentId", commentId));
    }

    public void subscriberJoined(String email) {
        publish(NotificationType.SUBSCRIBER.value(), "joined", "New subscriber", Map.of("email", email));
    }

    public void contactReceived(String name) {
        publish(NotificationType.CONTACT.value(), "received", "New contact from " + name, Map.of("name", name));
    }

    public void userLoggedIn(String email) {
        publish(NotificationType.AUTH.value(), "login", "User logged in", Map.of("email", email));
    }

    /**
     * Notification event record.
     */
    public record NotificationEvent(
            String type,
            String action,
            String title,
            Map<String, Object> data,
            LocalDateTime timestamp
    ) {}
}
