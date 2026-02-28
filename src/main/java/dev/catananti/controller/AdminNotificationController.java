package dev.catananti.controller;

import dev.catananti.service.NotificationEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * SSE endpoint for real-time admin notifications.
 * Clients connect to /api/v1/admin/notifications/stream and receive
 * events as they occur (article published, comment received, etc.).
 * Notification types are defined in {@link dev.catananti.service.NotificationType}.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV', 'EDITOR')")
@Slf4j
public class AdminNotificationController {

    private final NotificationEventService notificationEventService;

    @GetMapping("/stream")
    public Flux<ServerSentEvent<NotificationEventService.NotificationEvent>> stream() {
        log.debug("Client subscribing to notification stream");
        Flux<ServerSentEvent<NotificationEventService.NotificationEvent>> events = notificationEventService.subscribe()
                .map(event -> ServerSentEvent.<NotificationEventService.NotificationEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());

        // Send a heartbeat every 30 seconds to keep the connection alive
        Flux<ServerSentEvent<NotificationEventService.NotificationEvent>> heartbeat = Flux.interval(Duration.ofSeconds(30))
                .map(i -> ServerSentEvent.<NotificationEventService.NotificationEvent>builder()
                        .comment("heartbeat")
                        .build());

        return Flux.merge(events, heartbeat);
    }
}
