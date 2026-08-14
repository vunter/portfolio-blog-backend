package dev.catananti.service;

import dev.catananti.dto.PageResponse;
import dev.catananti.dto.SubscribeRequest;
import dev.catananti.dto.SubscriberResponse;
import dev.catananti.entity.Subscriber;
import dev.catananti.entity.SubscriberStatus;
import dev.catananti.exception.DuplicateResourceException;
import org.springframework.dao.DuplicateKeyException;
import dev.catananti.exception.ResourceNotFoundException;
import org.springframework.transaction.annotation.Transactional;
import dev.catananti.config.PaginationConfig;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.util.DigestUtils;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NewsletterService {

    private final SubscriberRepository subscriberRepository;
    private final dev.catananti.repository.UserRepository userRepository;
    private final NewsletterLinkService newsletterLinkService;
    private final EmailService emailService;
    private final IdService idService;
    private final HtmlSanitizerService htmlSanitizerService;
    private final NotificationEventService notificationEventService;
    private final BlogMetrics blogMetrics;
    private final PaginationConfig paginationConfig;
    private final dev.catananti.scheduler.SchedulerLock schedulerLock;

    @Value("${app.site-url:http://localhost:4200}")
    private String siteUrl;

    @Value("${newsletter.confirmation-expiration-hours:48}")
    private int confirmationExpirationHours;

    @Transactional
    public Mono<Map<String, String>> subscribe(SubscribeRequest request) {
        return subscriberRepository.findByEmail(request.getEmail())
                .flatMap(existing -> {
                    if (existing.getStatus() == SubscriberStatus.UNSUBSCRIBED) {
                        // Re-subscribe
                        existing.setStatus(SubscriberStatus.PENDING);
                        existing.setConfirmationToken(UUID.randomUUID().toString());
                        existing.setUnsubscribedAt(null);
                        existing.setCreatedAt(LocalDateTime.now());
                        existing.setAnalyticsConsent(Boolean.TRUE.equals(request.getAnalyticsConsent()));
                        return subscriberRepository.save(existing)
                                .map(s -> createConfirmationResponse(s));
                    } else if (existing.getStatus() == SubscriberStatus.CONFIRMED) {
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
                            log.warn("Failed to send confirmation email to {}: {}", PiiMasker.maskEmail(request.getEmail()), e.getMessage());
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
                .status(SubscriberStatus.PENDING)
                .confirmationToken(UUID.randomUUID().toString())
                .unsubscribeToken(UUID.randomUUID().toString())
                .createdAt(LocalDateTime.now())
                .analyticsConsent(Boolean.TRUE.equals(request.getAnalyticsConsent()))
                .build();

        return subscriberRepository.save(subscriber)
                .map(this::createConfirmationResponse)
                .doOnSuccess(s -> log.debug("New subscriber: {}", PiiMasker.maskEmail(request.getEmail())))
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("Concurrent subscription for {}: {}", PiiMasker.maskEmail(request.getEmail()), e.getMessage());
                    return Mono.error(new DuplicateResourceException("error.email_already_subscribed"));
                });
    }

    private Map<String, String> createConfirmationResponse(Subscriber subscriber) {
        // Token is only passed internally via _token for email sending; never exposed to HTTP response
        return Map.of(
                "message", "success.newsletter_confirm_email",
                "_token", subscriber.getConfirmationToken()
        );
    }

    @Transactional
    public Mono<Map<String, String>> confirmSubscription(String token) {
        return subscriberRepository.findByConfirmationToken(token)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Subscription", "token", token)))
                .flatMap(subscriber -> {
                    if (subscriber.getStatus() == SubscriberStatus.CONFIRMED) {
                        return Mono.just(Map.of("message", "success.newsletter_already_confirmed"));
                    }

                    // Check if confirmation token has expired
                    if (isTokenExpired(subscriber)) {
                        return Mono.error(new ResourceNotFoundException(
                                "Confirmation link has expired. Please subscribe again."));
                    }

                    subscriber.setStatus(SubscriberStatus.CONFIRMED);
                    subscriber.setConfirmedAt(LocalDateTime.now());
                    subscriber.setConfirmationToken(null);
                    // Generate a persistent unsubscribe token that survives confirmation
                    if (subscriber.getUnsubscribeToken() == null) {
                        subscriber.setUnsubscribeToken(UUID.randomUUID().toString());
                    }

                    return subscriberRepository.save(subscriber)
                            .flatMap(s -> {
                                // Confirmation is the moment the address is proven, so it is
                                // also the moment a verified account may link to it.
                                return autoLinkVerifiedAccount(s)
                                        // Send welcome email after confirmation
                                        .then(Mono.defer(() -> emailService.sendNewsletterWelcome(s.getEmail(), s.getName(), s.getUnsubscribeToken())
                                                .onErrorResume(e -> {
                                                    log.warn("Failed to send welcome email to {}: {}", PiiMasker.maskEmail(s.getEmail()), e.getMessage());
                                                    return Mono.empty();
                                                })))
                                        .thenReturn(Map.of("message", "success.newsletter_confirmed"));
                            })
                            .doOnSuccess(m -> {
                                log.info("Subscriber confirmed: {}", PiiMasker.maskEmail(subscriber.getEmail()));
                                notificationEventService.subscriberJoined(subscriber.getEmail());
                                blogMetrics.incrementSubscription();
                            });
                });
    }

    /**
     * Best-effort account link after the address is proven by confirmation.
     * Failures are logged and swallowed: the confirmation already happened and
     * must never be taken down by the link.
     */
    private Mono<Void> autoLinkVerifiedAccount(Subscriber subscriber) {
        return userRepository.findByEmail(subscriber.getEmail())
                .filter(user -> Boolean.TRUE.equals(user.getEmailVerified()))
                .flatMap(user -> newsletterLinkService.autoLink(
                        user.getId(), subscriber.getEmail(), NewsletterLinkService.ORIGIN_AUTO_SUBSCRIBE))
                .onErrorResume(e -> {
                    log.warn("Newsletter auto-link failed after confirmation for {}: {}",
                            PiiMasker.maskEmail(subscriber.getEmail()), e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    public Mono<Map<String, String>> unsubscribe(String email) {
        String normalizedEmail = email.toLowerCase().trim();
        return subscriberRepository.findByEmail(normalizedEmail)
                .flatMap(subscriber -> {
                    if (subscriber.getStatus() == SubscriberStatus.UNSUBSCRIBED) {
                        return Mono.just(Map.of("message", "success.generic_unsubscribe"));
                    }

                    String unsubscribeToken = subscriber.getUnsubscribeToken();
                    Mono<Subscriber> subscriberMono = Mono.just(subscriber);
                    if (!StringUtils.hasText(unsubscribeToken)) {
                        unsubscribeToken = UUID.randomUUID().toString();
                        subscriber.setUnsubscribeToken(unsubscribeToken);
                        subscriberMono = subscriberRepository.save(subscriber);
                    }

                    String finalUnsubscribeToken = unsubscribeToken;
                    return subscriberMono.flatMap(saved -> emailService.sendNewsletterUnsubscribeConfirmation(
                                    saved.getEmail(),
                                    saved.getName(),
                                    finalUnsubscribeToken)
                            .onErrorResume(e -> {
                                log.warn("Failed to send unsubscribe confirmation to {}: {}",
                                        PiiMasker.maskEmail(saved.getEmail()), e.getMessage());
                                return Mono.empty();
                            })
                            .thenReturn(Map.of("message", "success.generic_unsubscribe")));
                })
                .switchIfEmpty(Mono.just(Map.of("message", "success.generic_unsubscribe")));
    }

    public Mono<Map<String, String>> unsubscribeByToken(String token) {
        // Try unsubscribeToken first (for confirmed subscribers), then confirmationToken (for pending)
        return subscriberRepository.findByUnsubscribeToken(token)
                .switchIfEmpty(subscriberRepository.findByConfirmationToken(token))
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Subscription", "token", token)))
                .flatMap(subscriber -> {
                    if (subscriber.getStatus() == SubscriberStatus.UNSUBSCRIBED) {
                        return Mono.just(Map.of("message", "success.newsletter_unsubscribed"));
                    }
                    subscriber.setStatus(SubscriberStatus.UNSUBSCRIBED);
                    subscriber.setUnsubscribedAt(LocalDateTime.now());

                    return subscriberRepository.save(subscriber)
                            .doOnSuccess(s -> blogMetrics.incrementUnsubscription())
                            .map(s -> Map.of("message", "success.newsletter_unsubscribed"));
                });
    }

    // Admin methods
    public Flux<Subscriber> getAllSubscribers(String status) {
        if (status != null && !status.isEmpty()) {
            return subscriberRepository.findByStatus(status.toUpperCase(), paginationConfig.getBulkQueryMax());
        }
        return subscriberRepository.findAll();
    }

    public Mono<PageResponse<SubscriberResponse>> getAllSubscribersPaginated(String status, String email, int page, int size) {
        int offset = page * size;
        Flux<Subscriber> subscribersFlux;
        Mono<Long> countMono;

        boolean hasStatus = status != null && !status.isEmpty();
        boolean hasEmail = email != null && !email.isBlank();
        String sanitizedEmail = hasEmail ? DigestUtils.escapeLikePattern(email) : null;

        if (hasStatus && hasEmail) {
            subscribersFlux = subscriberRepository.findByStatusAndEmailContainingPaginated(status.toUpperCase(), sanitizedEmail, size, offset);
            countMono = subscriberRepository.countByStatusAndEmailContaining(status.toUpperCase(), sanitizedEmail);
        } else if (hasStatus) {
            subscribersFlux = subscriberRepository.findByStatusPaginated(status.toUpperCase(), size, offset);
            countMono = subscriberRepository.countByStatus(status.toUpperCase());
        } else if (hasEmail) {
            subscribersFlux = subscriberRepository.findByEmailContainingPaginated(sanitizedEmail, size, offset);
            countMono = subscriberRepository.countByEmailContaining(sanitizedEmail);
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
        return subscriberRepository.findAllConfirmed(paginationConfig.getBulkQueryMax());
    }

    public Mono<Void> deleteSubscriber(Long id) {
        return subscriberRepository.deleteById(id);
    }

    public Mono<Long> deleteSubscribersBatch(java.util.List<Long> ids) {
        // PERF-06: True batch delete using single SQL query
        if (ids.isEmpty()) return Mono.just(0L);
        return subscriberRepository.deleteAllByIdIn(ids);
    }

    public Mono<Void> cleanupExpiredPendingSubscriptions() {
        LocalDateTime expirationDate = LocalDateTime.now().minusHours(confirmationExpirationHours);

        return schedulerLock.executeWithLock("newsletter-cleanup", Duration.ofMinutes(5),
                subscriberRepository.countExpiredPendingSubscriptions(expirationDate)
                        .flatMap(count -> {
                            if (count > 0) {
                                log.info("Cleaning up {} expired pending subscriptions", count);
                                return subscriberRepository.deleteExpiredPendingSubscriptions(expirationDate)
                                        .doOnSuccess(deleted -> log.info("Deleted {} expired pending subscriptions", deleted));
                            }
                            return Mono.just(0);
                        })
                        .timeout(Duration.ofSeconds(30))
                        .doOnError(e -> log.error("Error cleaning up expired subscriptions: {}", e.getMessage(), e))
                        .onErrorComplete()
                        .then()
        );
    }

    /**
     * Cleanup expired pending subscriptions.
     * Runs daily at 3 AM by default.
     */
    @Scheduled(cron = "${scheduling.newsletter-cleanup-cron:0 0 3 * * *}")
    public void cleanupExpiredPendingSubscriptionsScheduled() {
        cleanupExpiredPendingSubscriptions().subscribe();
    }
}
