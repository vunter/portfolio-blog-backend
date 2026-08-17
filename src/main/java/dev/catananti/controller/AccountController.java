package dev.catananti.controller;

import dev.catananti.dto.AccountDeletionRequest;
import dev.catananti.dto.AccountNewsletterResponse;
import dev.catananti.dto.ConsentResponse;
import dev.catananti.dto.ConsentUpdateRequest;
import dev.catananti.dto.DeletionPreviewResponse;
import dev.catananti.entity.Subscriber;
import dev.catananti.entity.SubscriberStatus;
import dev.catananti.entity.User;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.AccountService;
import dev.catananti.service.AuditEventType;
import dev.catananti.service.AuditService;
import dev.catananti.service.IdService;
import dev.catananti.service.NewsletterLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Account-area management of the newsletter link and the purpose-specific
 * analytics consents (LGPD art. 8 §4). Every route requires authentication —
 * SecurityConfig has no /api/v1/account allowlist entry, so
 * {@code anyExchange().authenticated()} covers it.
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
@Tag(name = "Account", description = "Account-area newsletter and consent management")
@Slf4j
public class AccountController {

    private final UserRepository userRepository;
    private final SubscriberRepository subscriberRepository;
    private final NewsletterLinkService newsletterLinkService;
    private final AuditService auditService;
    private final IdService idService;
    private final AccountService accountService;

    @GetMapping("/newsletter")
    @Operation(summary = "Newsletter subscription as seen from the account")
    public Mono<AccountNewsletterResponse> getNewsletter(@AuthenticationPrincipal String email) {
        return requireUser(email)
                .flatMap(user -> findSubscriber(user)
                        .map(this::toNewsletterResponse)
                        .defaultIfEmpty(new AccountNewsletterResponse(false, false, null, null, null)));
    }

    @PostMapping("/newsletter/link")
    @Operation(summary = "Link the newsletter subscription to this account")
    public Mono<Map<String, String>> linkNewsletter(@AuthenticationPrincipal String email) {
        return requireVerifiedUser(email)
                .flatMap(user -> newsletterLinkService.linkOnRequest(user.getId(), user.getEmail()))
                .thenReturn(Map.of("message", "success.newsletter_linked"));
    }

    @DeleteMapping("/newsletter/link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Undo the newsletter link")
    public Mono<Void> unlinkNewsletter(@AuthenticationPrincipal String email) {
        // 'USER' records that the holder refused: only this blocks automatic re-linking.
        return requireUser(email)
                .flatMap(user -> newsletterLinkService.unlink(user.getId(), "USER"))
                .then();
    }

    @PostMapping("/newsletter/subscribe")
    @Operation(summary = "Subscribe to the newsletter from the account area")
    public Mono<Map<String, String>> subscribeNewsletter(@AuthenticationPrincipal String email) {
        // No double opt-in here: the verified account already proved ownership
        // of the address, so the subscriber is born CONFIRMED and linked.
        return requireVerifiedUser(email)
                .flatMap(user -> subscriberRepository.findByEmail(user.getEmail())
                        .flatMap(existing -> reactivateAndLink(existing, user))
                        .switchIfEmpty(Mono.defer(() -> createLinkedSubscriber(user))))
                .thenReturn(Map.of("message", "success.newsletter_subscribed"));
    }

    @PostMapping("/newsletter/unsubscribe")
    @Operation(summary = "Unsubscribe from the newsletter keeping the account link")
    public Mono<Map<String, String>> unsubscribeNewsletter(@AuthenticationPrincipal String email) {
        return requireUser(email)
                .flatMap(user -> findSubscriber(user)
                        .filter(sub -> sub.getStatus() != SubscriberStatus.UNSUBSCRIBED)
                        .flatMap(sub -> {
                            // cancels the emails, not the link: the account keeps
                            // seeing (and managing) its own subscription
                            sub.setStatus(SubscriberStatus.UNSUBSCRIBED);
                            sub.setUnsubscribedAt(LocalDateTime.now());
                            return subscriberRepository.save(sub);
                        })
                        .then())
                .thenReturn(Map.of("message", "success.newsletter_unsubscribed"));
    }

    @GetMapping("/consent")
    @Operation(summary = "Both purpose-specific analytics consents")
    public Mono<ConsentResponse> getConsent(@AuthenticationPrincipal String email) {
        return requireUser(email)
                .flatMap(user -> findSubscriber(user)
                        .map(sub -> new ConsentResponse(user.getAnalyticsConsent(), sub.getAnalyticsConsent()))
                        .defaultIfEmpty(new ConsentResponse(user.getAnalyticsConsent(), null)));
    }

    @PutMapping("/consent")
    @Operation(summary = "Partially update the analytics consents")
    public Mono<ConsentResponse> updateConsent(@AuthenticationPrincipal String email,
                                               @RequestBody ConsentUpdateRequest request) {
        return requireUser(email)
                .flatMap(user -> findSubscriber(user)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        // sequential on purpose: no concurrent writes inside one transaction
                        .flatMap(maybeSub -> updateSiteConsent(user, request.siteAnalyticsConsent())
                                .then(updateEmailConsent(user, maybeSub.orElse(null), request.emailAnalyticsConsent()))
                                .thenReturn(mergedConsent(user, maybeSub.orElse(null), request))));
    }

    @GetMapping("/deletion-preview")
    @Operation(summary = "What account deletion will touch, before the holder decides")
    public Mono<DeletionPreviewResponse> deletionPreview(@AuthenticationPrincipal String email) {
        return accountService.deletionPreview(email);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate (reversible) or erase (LGPD art. 18, VI) the account")
    public Mono<Void> deleteAccount(@AuthenticationPrincipal String email,
                                    @RequestBody AccountDeletionRequest request) {
        return parseMode(request.mode())
                .flatMap(mode -> accountService.deleteAccount(
                        email, request.password(), mode, request.cancelNewsletter()));
    }

    private Mono<AccountService.Mode> parseMode(String raw) {
        try {
            return Mono.just(AccountService.Mode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (RuntimeException e) {
            // null or anything outside {DEACTIVATE, ERASE}
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "error.invalid_request"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private Mono<User> requireUser(String email) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "error.user_not_found")));
    }

    /** Everything that assumes ownership of the address is gated on the proof. */
    private Mono<User> requireVerifiedUser(String email) {
        return requireUser(email)
                .filter(user -> Boolean.TRUE.equals(user.getEmailVerified()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "error.email_not_verified")));
    }

    /** By link first; by address as fallback for the not-yet-linked subscription. */
    private Mono<Subscriber> findSubscriber(User user) {
        return subscriberRepository.findByUserId(user.getId())
                .switchIfEmpty(Mono.defer(() -> subscriberRepository.findByEmail(user.getEmail())));
    }

    private AccountNewsletterResponse toNewsletterResponse(Subscriber subscriber) {
        boolean linked = subscriber.getUserId() != null;
        return new AccountNewsletterResponse(
                subscriber.getStatus() == SubscriberStatus.CONFIRMED,
                linked,
                subscriber.getStatus() != null ? subscriber.getStatus().name() : null,
                linked ? subscriber.getLinkedAt() : null,
                subscriber.getAnalyticsConsent());
    }

    private Mono<Map<String, String>> createLinkedSubscriber(User user) {
        LocalDateTime now = LocalDateTime.now();
        Subscriber subscriber = Subscriber.builder()
                .id(idService.nextId())
                .email(user.getEmail())
                .name(user.getName())
                .status(SubscriberStatus.CONFIRMED)
                .unsubscribeToken(UUID.randomUUID().toString())
                .confirmedAt(now)
                .createdAt(now)
                .userId(user.getId())
                .linkedAt(now)
                .linkOrigin(NewsletterLinkService.ORIGIN_MANUAL_USER)
                .build();
        return subscriberRepository.save(subscriber)
                .thenReturn(Map.of("message", "success.newsletter_subscribed"));
    }

    private Mono<Map<String, String>> reactivateAndLink(Subscriber existing, User user) {
        Mono<Subscriber> reactivated;
        if (existing.getStatus() == SubscriberStatus.CONFIRMED) {
            reactivated = Mono.just(existing);
        } else {
            existing.setStatus(SubscriberStatus.CONFIRMED);
            existing.setConfirmedAt(LocalDateTime.now());
            existing.setUnsubscribedAt(null);
            existing.setConfirmationToken(null);
            if (existing.getUnsubscribeToken() == null) {
                existing.setUnsubscribeToken(UUID.randomUUID().toString());
            }
            reactivated = subscriberRepository.save(existing);
        }
        return reactivated
                .flatMap(sub -> sub.getUserId() == null
                        // the holder is explicitly asking: an earlier refusal is being revoked
                        ? subscriberRepository.linkIgnoringRefusal(sub.getId(), user.getId(),
                                        NewsletterLinkService.ORIGIN_MANUAL_USER, LocalDateTime.now())
                                .then(Mono.just(sub))
                        : Mono.just(sub))
                .thenReturn(Map.of("message", "success.newsletter_subscribed"));
    }

    private Mono<Void> updateSiteConsent(User user, Boolean consent) {
        if (consent == null) {
            return Mono.empty();
        }
        return userRepository.updateAnalyticsConsent(user.getId(), consent, LocalDateTime.now())
                .then(auditService.logAction(AuditEventType.CONSENT_UPDATED.action(), "USER",
                        user.getId().toString(), user.getId(), user.getEmail(),
                        "Site analytics consent set to " + consent));
    }

    private Mono<Void> updateEmailConsent(User user, Subscriber subscriber, Boolean consent) {
        if (consent == null || subscriber == null) {
            // no subscriber to hold the consent: nothing to record, not an error
            return Mono.empty();
        }
        return subscriberRepository.updateAnalyticsConsent(subscriber.getId(), consent)
                .then(auditService.logAction(AuditEventType.CONSENT_UPDATED.action(), "SUBSCRIBER",
                        subscriber.getId().toString(), user.getId(), user.getEmail(),
                        "Email analytics consent set to " + consent));
    }

    /** Response reflects the values just written without a re-read. */
    private ConsentResponse mergedConsent(User user, Subscriber subscriber, ConsentUpdateRequest request) {
        Boolean site = request.siteAnalyticsConsent() != null
                ? request.siteAnalyticsConsent() : user.getAnalyticsConsent();
        Boolean mail;
        if (subscriber == null) {
            mail = null;
        } else {
            mail = request.emailAnalyticsConsent() != null
                    ? request.emailAnalyticsConsent() : subscriber.getAnalyticsConsent();
        }
        return new ConsentResponse(site, mail);
    }
}
