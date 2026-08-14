package dev.catananti.service;

import dev.catananti.entity.Subscriber;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Creates and undoes the link between a newsletter subscriber and a user
 * account. Knows nothing about HTTP: callers discover the pair and guarantee
 * the account-side precondition (email_verified).
 *
 * <p>The link only exists when both sides proved ownership of the address, and
 * the whole linking policy lives in the conditional UPDATEs of
 * {@link SubscriberRepository} so concurrent triggers (email verification and
 * subscription confirmation racing each other) resolve in the database, not in
 * Java.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NewsletterLinkService {

    public static final String ORIGIN_AUTO_REGISTER = "AUTO_REGISTER";
    public static final String ORIGIN_AUTO_SUBSCRIBE = "AUTO_SUBSCRIBE";
    public static final String ORIGIN_MANUAL_USER = "MANUAL_USER";

    private final SubscriberRepository subscriberRepository;
    private final AuditService auditService;

    /**
     * Automatic link, triggered by email verification (AUTO_REGISTER) or
     * subscription confirmation (AUTO_SUBSCRIBE). Respects an earlier refusal by
     * the holder (unlinked_by = 'USER'). Returns true only when this call
     * created the link; 0 rows (already linked, refused, or race lost) and a
     * DuplicateKeyException from the unique index are both idempotent no-ops.
     */
    public Mono<Boolean> autoLink(Long userId, String email, String origin) {
        return subscriberRepository.findByEmail(email)
                .filter(Subscriber::isConfirmed)
                .flatMap(subscriber -> subscriberRepository
                        .autoLink(subscriber.getId(), userId, origin, LocalDateTime.now())
                        .onErrorResume(DuplicateKeyException.class, e -> {
                            // another subscriber row won the index for this user — already linked
                            log.debug("Concurrent newsletter link for {}: {}",
                                    PiiMasker.maskEmail(email), e.getMessage());
                            return Mono.just(0L);
                        })
                        .flatMap(rows -> rows > 0
                                ? auditLinked(subscriber.getId(), userId, email, origin).thenReturn(true)
                                : Mono.just(false)))
                .defaultIfEmpty(false);
    }

    /**
     * Explicit link requested by the holder in the account area. The caller
     * guarantees the account email is verified. Unlike {@link #autoLink}, an
     * earlier refusal does not block: it is exactly what the holder is revoking.
     *
     * @throws ResponseStatusException 400 when no CONFIRMED subscriber exists
     *         for the account email
     */
    public Mono<Boolean> linkOnRequest(Long userId, String userEmail) {
        return subscriberRepository.findByEmail(userEmail)
                .filter(Subscriber::isConfirmed)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "error.newsletter_link_unavailable")))
                .flatMap(subscriber -> subscriberRepository
                        .linkIgnoringRefusal(subscriber.getId(), userId, ORIGIN_MANUAL_USER, LocalDateTime.now())
                        .onErrorResume(DuplicateKeyException.class, e -> Mono.just(0L))
                        .flatMap(rows -> rows > 0
                                ? auditLinked(subscriber.getId(), userId, userEmail, ORIGIN_MANUAL_USER).thenReturn(true)
                                : Mono.just(false)));
    }

    /**
     * Undoes the link for the given user. {@code by} decides whether the
     * automatic path may ever re-link: only 'USER' blocks it.
     */
    public Mono<Boolean> unlink(Long userId, String by) {
        return subscriberRepository.unlink(userId, by, LocalDateTime.now())
                .flatMap(rows -> rows > 0
                        ? auditService.logAction(AuditEventType.NEWSLETTER_UNLINKED.action(), "USER",
                                userId.toString(), userId, null,
                                "Newsletter subscription unlinked by " + by)
                                .thenReturn(true)
                        : Mono.just(false));
    }

    private Mono<Void> auditLinked(Long subscriberId, Long userId, String email, String origin) {
        return auditService.logAction(AuditEventType.NEWSLETTER_LINKED.action(), "SUBSCRIBER",
                subscriberId.toString(), userId, email,
                "Newsletter subscription linked to account (origin: " + origin + ")");
    }
}
