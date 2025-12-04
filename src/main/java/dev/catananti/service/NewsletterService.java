package dev.catananti.service;

import dev.catananti.dto.PageResponse;
import dev.catananti.dto.SubscribeRequest;
import dev.catananti.dto.SubscriberResponse;
import dev.catananti.entity.Subscriber;
import dev.catananti.entity.SubscriberStatus;
import dev.catananti.exception.DuplicateResourceException;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
// TODO F-190: Add double opt-in email confirmation with token expiry for GDPR compliance
public class NewsletterService {

    private final SubscriberRepository subscriberRepository;
    private final EmailService emailService;
    private final IdService idService;
    private final HtmlSanitizerService htmlSanitizerService;
    private final NotificationEventService notificationEventService;
    private final BlogMetrics blogMetrics;

    @Value("${app.site-url:http://localhost:4200}")
    private String siteUrl;

    @Value("${newsletter.confirmation-expiration-hours:48}")
    private int confirmationExpirationHours;

    public Mono<Map<String, String>> subscribe(SubscribeRequest request) {
        return subscriberRepository.findByEmail(request.getEmail())
                .flatMap(existing -> {
                    if (SubscriberStatus.UNSUBSCRIBED.matches(existing.getStatus())) {
                        // Re-subscribe
                        existing.setStatus(SubscriberStatus.PENDING.name());
                        existing.setConfirmationToken(UUID.randomUUID().toString());
                        existing.setUnsubscribedAt(null);
                        existing.setCreatedAt(LocalDateTime.now()); // Reset creation time for expiration
                        return subscriberRepository.save(existing)
                                .map(s -> createConfirmationResponse(s));
                    } else if (SubscriberStatus.CONFIRMED.matches(existing.getStatus())) {
                        return Mono.error(new DuplicateResourceException("error.email_already_subscribed"));
                    } else {
                        // Still pending - check if expired, then regenerate token
                        if (isTokenExpired(existing)) {
                            existing.setConfirmationToken(UUID.randomUUID().toString());
                            existing.setCreatedAt(LocalDateTime.now());
                            return subscriberRepository.save(existing)
                                    .map(this::createConfirmationResponse);
                        }
                        return Mono.just(createConfirmationResponse(existing));
                    }
                })
                .switchIfEmpty(createNewSubscriber(request))
                .flatMap(response -> {
                    String token = response.get("_token");
                    if (token != null) {
                        return emailService.sendNewsletterConfirmation(
                                request.getEmail(),
                                request.getName(),
                                token
                        ).onErrorResume(e -> {
                            log.warn("Failed to send confirmation email to {}: {}", request.getEmail(), e.getMessage());
                            return Mono.empty();
                        }).thenReturn(Map.of("message", response.get("message")));
                    }
                    return Mono.just(Map.of("message", response.get("message")));
                });
    }

    private boolean isTokenExpired(Subscriber subscriber) {
        if (subscriber.getCreatedAt() == null) {
            return true;
        }
        return subscriber.getCreatedAt()
                .plusHours(confirmationExpirationHours)
                .isBefore(LocalDateTime.now());
    }

    private Mono<Map<String, String>> createNewSubscriber(SubscribeRequest request) {
        Subscriber subscriber = Subscriber.builder()
                .id(idService.nextId())
                .email(request.getEmail())
                .name(request.getName() != null ? htmlSanitizerService.stripHtml(request.getName()) : null)
                .status(SubscriberStatus.PENDING.name())
                .confirmationToken(UUID.randomUUID().toString())
                .unsubscribeToken(UUID.randomUUID().toString())
                .createdAt(LocalDateTime.now())
                .build();

        return subscriberRepository.save(subscriber)
                .map(this::createConfirmationResponse)
                .doOnSuccess(s -> log.debug("New subscriber: {}", request.getEmail()));
    }

    private Map<String, String> createConfirmationResponse(Subscriber subscriber) {
        // Token is only passed internally via _token for email sending; never exposed to HTTP response
        return Map.of(
                "message", "success.newsletter_confirm_email",
                "_token", subscriber.getConfirmationToken()
        );
    }

    public Mono<Map<String, String>> confirmSubscription(String token) {
        return subscriberRepository.findByConfirmationToken(token)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Subscription", "token", token)))
                .flatMap(subscriber -> {
                    if (SubscriberStatus.CONFIRMED.matches(subscriber.getStatus())) {
                        return Mono.just(Map.of("message", "success.newsletter_already_confirmed"));
                    }

                    // Check if confirmation token has expired
                    if (isTokenExpired(subscriber)) {
                        return Mono.error(new ResourceNotFoundException(
                                "Confirmation link has expired. Please subscribe again."));
                    }

                    subscriber.setStatus(SubscriberStatus.CONFIRMED.name());
                    subscriber.setConfirmedAt(LocalDateTime.now());
                    subscriber.setConfirmationToken(null);
                    // Generate a persistent unsubscribe token that survives confirmation
                    if (subscriber.getUnsubscribeToken() == null) {
                        subscriber.setUnsubscribeToken(UUID.randomUUID().toString());
                    }

                    return subscriberRepository.save(subscriber)
                            .flatMap(s -> {
                                // Send welcome email after confirmation
                                return emailService.sendNewsletterWelcome(s.getEmail(), s.getName())
                                        .onErrorResume(e -> {
                                            log.warn("Failed to send welcome email to {}: {}", s.getEmail(), e.getMessage());
                                            return Mono.empty();
                                        })
                                        .thenReturn(Map.of("message", "success.newsletter_confirmed"));
                            })
                            .doOnSuccess(m -> {
                                log.info("Subscriber confirmed: {}", subscriber.getEmail());
                                notificationEventService.subscriberJoined(subscriber.getEmail());
                                blogMetrics.incrementSubscription();
                            });
                });
    }

    public Mono<Map<String, String>> unsubscribe(String email) {
        return subscriberRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Subscriber", "email", email)))
                .flatMap(subscriber -> {
                    subscriber.setStatus(SubscriberStatus.UNSUBSCRIBED.name());
                    subscriber.setUnsubscribedAt(LocalDateTime.now());

                    return subscriberRepository.save(subscriber)
                            .map(s -> Map.of("message", "success.newsletter_unsubscribed"))
                            .doOnSuccess(m -> {
                                log.debug("Subscriber unsubscribed: {}", email);
                                blogMetrics.incrementUnsubscription();
                            });
                });
    }

    public Mono<Map<String, String>> unsubscribeByToken(String token) {
        // Try unsubscribeToken first (for confirmed subscribers), then confirmationToken (for pending)
        return subscriberRepository.findByUnsubscribeToken(token)
                .switchIfEmpty(subscriberRepository.findByConfirmationToken(token))
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Subscription", "token", token)))
                .flatMap(subscriber -> {
                    subscriber.setStatus(SubscriberStatus.UNSUBSCRIBED.name());
                    subscriber.setUnsubscribedAt(LocalDateTime.now());

                    return subscriberRepository.save(subscriber)
                            .map(s -> Map.of("message", "success.newsletter_unsubscribed"));
                });
    }

    // Admin methods
    public Flux<Subscriber> getAllSubscribers(String status) {
        if (status != null && !status.isEmpty()) {
            return subscriberRepository.findByStatus(status.toUpperCase());
        }
        return subscriberRepository.findAll();
    }

    public Mono<PageResponse<SubscriberResponse>> getAllSubscribersPaginated(String status, String email, int page, int size) {
        int offset = page * size;
        Flux<Subscriber> subscribersFlux;
        Mono<Long> countMono;

        boolean hasStatus = status != null && !status.isEmpty();
        boolean hasEmail = email != null && !email.isBlank();

        if (hasStatus && hasEmail) {
            subscribersFlux = subscriberRepository.findByStatusAndEmailContainingPaginated(status.toUpperCase(), email, size, offset);
            countMono = subscriberRepository.countByStatusAndEmailContaining(status.toUpperCase(), email);
        } else if (hasStatus) {
            subscribersFlux = subscriberRepository.findByStatusPaginated(status.toUpperCase(), size, offset);
            countMono = subscriberRepository.countByStatus(status.toUpperCase());
        } else if (hasEmail) {
            subscribersFlux = subscriberRepository.findByEmailContainingPaginated(email, size, offset);
            countMono = subscriberRepository.countByEmailContaining(email);
        } else {
            subscribersFlux = subscriberRepository.findAllPaginated(size, offset);
            countMono = subscriberRepository.count();
        }

        return subscribersFlux
                .map(SubscriberResponse::fromEntity)
                .collectList()
                .zipWith(countMono)
                .map(tuple -> {
                    var content = tuple.getT1();
                    var total = tuple.getT2();
                    return PageResponse.of(content, page, size, total);
                });
    }

    public Mono<Map<String, Long>> getStats() {
        return Mono.zip(
                subscriberRepository.countConfirmed(),
                subscriberRepository.countPending(),
                subscriberRepository.count()
        ).map(tuple -> Map.of(
                "confirmed", tuple.getT1(),
                "pending", tuple.getT2(),
                "total", tuple.getT3()
        ));
    }

    public Flux<Subscriber> getActiveSubscribers() {
        return subscriberRepository.findAllConfirmed();
    }

    public Mono<Void> deleteSubscriber(Long id) {
        return subscriberRepository.deleteById(id);
    }

    public Mono<Long> deleteSubscribersBatch(java.util.List<Long> ids) {
        // PERF-06: True batch delete using single SQL query
        if (ids.isEmpty()) return Mono.just(0L);
        return subscriberRepository.deleteAllByIdIn(ids);
    }

    /**
     * Cleanup expired pending subscriptions.
     * Runs daily at 3 AM by default.
     * Uses .subscribe() to avoid blocking the scheduler thread.
     */
    @Scheduled(cron = "${scheduling.newsletter-cleanup-cron:0 0 3 * * *}")
    public void cleanupExpiredPendingSubscriptions() {
        LocalDateTime expirationDate = LocalDateTime.now().minusHours(confirmationExpirationHours);

        subscriberRepository.countExpiredPendingSubscriptions(expirationDate)
                .flatMap(count -> {
                    if (count > 0) {
                        log.info("Cleaning up {} expired pending subscriptions", count);
                        return subscriberRepository.deleteExpiredPendingSubscriptions(expirationDate)
                                .doOnSuccess(deleted -> log.info("Deleted {} expired pending subscriptions", deleted));
                    }
                    return Mono.just(0);
                })
                .subscribe(
                        result -> log.debug("Newsletter cleanup completed"),
                        error -> log.error("Error cleaning up expired subscriptions: {}", error.getMessage(), error)
                );
    }
}
