package dev.catananti.controller;

import dev.catananti.dto.AccountDeletionRequest;
import dev.catananti.dto.ConsentUpdateRequest;
import dev.catananti.dto.DeletionPreviewResponse;
import dev.catananti.service.AccountService;
import dev.catananti.entity.Subscriber;
import dev.catananti.entity.SubscriberStatus;
import dev.catananti.entity.User;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.AuditService;
import dev.catananti.service.IdService;
import dev.catananti.service.NewsletterLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private SubscriberRepository subscriberRepository;
    @Mock private NewsletterLinkService newsletterLinkService;
    @Mock private AuditService auditService;
    @Mock private IdService idService;
    @Mock private AccountService accountService;

    @InjectMocks
    private AccountController controller;

    private static final String EMAIL = "ana@test.dev";
    private static final LocalDateTime LINKED_AT = LocalDateTime.of(2026, 8, 1, 12, 0);

    private User user(boolean verified) {
        return User.builder().id(10L).email(EMAIL).name("Ana").emailVerified(verified).build();
    }

    private Subscriber subscriber(SubscriberStatus status, Long userId) {
        return Subscriber.builder()
                .id(50L)
                .email(EMAIL)
                .status(status)
                .userId(userId)
                .linkedAt(userId != null ? LINKED_AT : null)
                .linkOrigin(userId != null ? "AUTO_REGISTER" : null)
                .analyticsConsent(true)
                .unsubscribeToken("unsub-token")
                .build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(auditService.logAction(anyString(), anyString(), anyString(), anyLong(), any(), anyString()))
                .thenReturn(Mono.empty());
    }

    @Nested
    @DisplayName("GET /api/v1/account/newsletter")
    class GetNewsletter {

        @Test
        @DisplayName("returns the linked subscription")
        void returnsLinkedSubscription() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByUserId(10L))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED, 10L)));

            StepVerifier.create(controller.getNewsletter(EMAIL))
                    .assertNext(response -> {
                        assertThat(response.subscribed()).isTrue();
                        assertThat(response.linked()).isTrue();
                        assertThat(response.subscriberStatus()).isEqualTo("CONFIRMED");
                        assertThat(response.linkedAt()).isEqualTo(LINKED_AT);
                        assertThat(response.emailAnalyticsConsent()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("falls back to email lookup when nothing is linked")
        void fallsBackToEmailLookup() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByUserId(10L)).thenReturn(Mono.empty());
            when(subscriberRepository.findByEmail(EMAIL))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED, null)));

            StepVerifier.create(controller.getNewsletter(EMAIL))
                    .assertNext(response -> {
                        assertThat(response.subscribed()).isTrue();
                        assertThat(response.linked()).isFalse();
                        assertThat(response.linkedAt()).isNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("returns empty shape when there is no subscriber at all")
        void returnsEmptyShapeWithoutSubscriber() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByUserId(10L)).thenReturn(Mono.empty());
            when(subscriberRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());

            StepVerifier.create(controller.getNewsletter(EMAIL))
                    .assertNext(response -> {
                        assertThat(response.subscribed()).isFalse();
                        assertThat(response.linked()).isFalse();
                        assertThat(response.subscriberStatus()).isNull();
                        assertThat(response.linkedAt()).isNull();
                        assertThat(response.emailAnalyticsConsent()).isNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("unsubscribed but still linked reports subscribed=false, linked=true")
        void unsubscribedButLinked() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByUserId(10L))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.UNSUBSCRIBED, 10L)));

            StepVerifier.create(controller.getNewsletter(EMAIL))
                    .assertNext(response -> {
                        assertThat(response.subscribed()).isFalse();
                        assertThat(response.linked()).isTrue();
                        assertThat(response.subscriberStatus()).isEqualTo("UNSUBSCRIBED");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/account/newsletter/link")
    class LinkNewsletter {

        @Test
        @DisplayName("links on request for a verified account")
        void linksVerifiedAccount() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(newsletterLinkService.linkOnRequest(10L, EMAIL)).thenReturn(Mono.just(true));

            StepVerifier.create(controller.linkNewsletter(EMAIL))
                    .assertNext(response ->
                            assertThat(response.get("message")).isEqualTo("success.newsletter_linked"))
                    .verifyComplete();

            verify(newsletterLinkService).linkOnRequest(10L, EMAIL);
        }

        @Test
        @DisplayName("rejects with 400 error.email_not_verified for an unverified account")
        void rejectsUnverifiedAccount() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(false)));

            StepVerifier.create(controller.linkNewsletter(EMAIL))
                    .expectErrorSatisfies(e -> {
                        ResponseStatusException rse = (ResponseStatusException) e;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(rse.getReason()).isEqualTo("error.email_not_verified");
                    })
                    .verify();

            verify(newsletterLinkService, never()).linkOnRequest(anyLong(), anyString());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/account/newsletter/link")
    class UnlinkNewsletter {

        @Test
        @DisplayName("unlinks recording USER as the author of the refusal")
        void unlinksAsUser() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(newsletterLinkService.unlink(10L, "USER")).thenReturn(Mono.just(true));

            StepVerifier.create(controller.unlinkNewsletter(EMAIL)).verifyComplete();

            verify(newsletterLinkService).unlink(10L, "USER");
        }
    }

    @Nested
    @DisplayName("POST /api/v1/account/newsletter/subscribe")
    class Subscribe {

        @Test
        @DisplayName("creates a CONFIRMED subscriber already linked — ownership is proven")
        void createsConfirmedLinkedSubscriber() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(90L);
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(controller.subscribeNewsletter(EMAIL))
                    .assertNext(response ->
                            assertThat(response.get("message")).isEqualTo("success.newsletter_subscribed"))
                    .verifyComplete();

            verify(subscriberRepository).save(argThat(s ->
                    s.getStatus() == SubscriberStatus.CONFIRMED
                            && s.getUserId() != null && s.getUserId() == 10L
                            && s.getLinkedAt() != null
                            && "MANUAL_USER".equals(s.getLinkOrigin())
                            && s.getUnsubscribeToken() != null
                            && s.getConfirmedAt() != null));
        }

        @Test
        @DisplayName("reactivates an UNSUBSCRIBED subscriber and links it when unlinked")
        void reactivatesAndLinksExistingSubscriber() {
            Subscriber unsubscribed = subscriber(SubscriberStatus.UNSUBSCRIBED, null);
            unsubscribed.setUnsubscribedAt(LocalDateTime.now().minusDays(3));

            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByEmail(EMAIL)).thenReturn(Mono.just(unsubscribed));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(subscriberRepository.linkIgnoringRefusal(eq(50L), eq(10L), eq("MANUAL_USER"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(controller.subscribeNewsletter(EMAIL))
                    .assertNext(response ->
                            assertThat(response.get("message")).isEqualTo("success.newsletter_subscribed"))
                    .verifyComplete();

            verify(subscriberRepository).save(argThat(s ->
                    s.getStatus() == SubscriberStatus.CONFIRMED && s.getUnsubscribedAt() == null));
            verify(subscriberRepository).linkIgnoringRefusal(eq(50L), eq(10L), eq("MANUAL_USER"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("does not re-link a subscriber that is already linked")
        void doesNotRelinkAlreadyLinkedSubscriber() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByEmail(EMAIL))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED, 10L)));

            StepVerifier.create(controller.subscribeNewsletter(EMAIL))
                    .assertNext(response ->
                            assertThat(response.get("message")).isEqualTo("success.newsletter_subscribed"))
                    .verifyComplete();

            verify(subscriberRepository, never()).linkIgnoringRefusal(anyLong(), anyLong(), anyString(), any());
            verify(subscriberRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects with 400 when the account email is not verified")
        void rejectsUnverifiedAccount() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(false)));

            StepVerifier.create(controller.subscribeNewsletter(EMAIL))
                    .expectErrorSatisfies(e -> {
                        ResponseStatusException rse = (ResponseStatusException) e;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(rse.getReason()).isEqualTo("error.email_not_verified");
                    })
                    .verify();

            verify(subscriberRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/account/newsletter/unsubscribe")
    class Unsubscribe {

        @Test
        @DisplayName("unsubscribes keeping the link")
        void unsubscribesKeepingTheLink() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByUserId(10L))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED, 10L)));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(controller.unsubscribeNewsletter(EMAIL))
                    .assertNext(response ->
                            assertThat(response.get("message")).isEqualTo("success.newsletter_unsubscribed"))
                    .verifyComplete();

            verify(subscriberRepository).save(argThat(s ->
                    s.getStatus() == SubscriberStatus.UNSUBSCRIBED
                            && s.getUnsubscribedAt() != null
                            // unsubscribing cancels the emails, not the account link
                            && s.getUserId() != null && s.getUserId() == 10L));
        }

        @Test
        @DisplayName("is idempotent when there is no subscriber")
        void idempotentWithoutSubscriber() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByUserId(10L)).thenReturn(Mono.empty());
            when(subscriberRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());

            StepVerifier.create(controller.unsubscribeNewsletter(EMAIL))
                    .assertNext(response ->
                            assertThat(response.get("message")).isEqualTo("success.newsletter_unsubscribed"))
                    .verifyComplete();

            verify(subscriberRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/account/consent")
    class GetConsent {

        @Test
        @DisplayName("returns both purpose-specific consents")
        void returnsBothConsents() {
            User u = user(true);
            u.setAnalyticsConsent(false);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(u));
            when(subscriberRepository.findByUserId(10L))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED, 10L)));

            StepVerifier.create(controller.getConsent(EMAIL))
                    .assertNext(response -> {
                        assertThat(response.siteAnalyticsConsent()).isFalse();
                        assertThat(response.emailAnalyticsConsent()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("never-decided stays null — distinct from refusal")
        void neverDecidedStaysNull() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByUserId(10L)).thenReturn(Mono.empty());
            when(subscriberRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());

            StepVerifier.create(controller.getConsent(EMAIL))
                    .assertNext(response -> {
                        assertThat(response.siteAnalyticsConsent()).isNull();
                        assertThat(response.emailAnalyticsConsent()).isNull();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/account/consent")
    class UpdateConsent {

        @Test
        @DisplayName("updates only the site consent when only it is present")
        void updatesOnlySiteConsent() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByUserId(10L))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED, 10L)));
            when(userRepository.updateAnalyticsConsent(eq(10L), eq(true), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(controller.updateConsent(EMAIL, new ConsentUpdateRequest(true, null)))
                    .assertNext(response -> {
                        assertThat(response.siteAnalyticsConsent()).isTrue();
                        assertThat(response.emailAnalyticsConsent()).isTrue();
                    })
                    .verifyComplete();

            verify(subscriberRepository, never()).updateAnalyticsConsent(anyLong(), anyBoolean());
            verify(auditService).logAction(eq("CONSENT_UPDATED"), eq("USER"), eq("10"), eq(10L), any(), anyString());
        }

        @Test
        @DisplayName("updates only the email consent when only it is present")
        void updatesOnlyEmailConsent() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByUserId(10L))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED, 10L)));
            when(subscriberRepository.updateAnalyticsConsent(50L, false)).thenReturn(Mono.just(1L));

            StepVerifier.create(controller.updateConsent(EMAIL, new ConsentUpdateRequest(null, false)))
                    .assertNext(response -> {
                        assertThat(response.siteAnalyticsConsent()).isNull();
                        assertThat(response.emailAnalyticsConsent()).isFalse();
                    })
                    .verifyComplete();

            verify(userRepository, never()).updateAnalyticsConsent(anyLong(), anyBoolean(), any());
            verify(auditService).logAction(eq("CONSENT_UPDATED"), eq("SUBSCRIBER"), eq("50"), eq(10L), any(), anyString());
        }

        @Test
        @DisplayName("touches nothing when both fields are absent")
        void touchesNothingWhenBodyIsEmpty() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByUserId(10L))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED, 10L)));

            StepVerifier.create(controller.updateConsent(EMAIL, new ConsentUpdateRequest(null, null)))
                    .assertNext(response -> {
                        assertThat(response.siteAnalyticsConsent()).isNull();
                        assertThat(response.emailAnalyticsConsent()).isTrue();
                    })
                    .verifyComplete();

            verify(userRepository, never()).updateAnalyticsConsent(anyLong(), anyBoolean(), any());
            verify(subscriberRepository, never()).updateAnalyticsConsent(anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("email consent without a subscriber is a no-op, not an error")
        void emailConsentWithoutSubscriberIsNoOp() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user(true)));
            when(subscriberRepository.findByUserId(10L)).thenReturn(Mono.empty());
            when(subscriberRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());

            StepVerifier.create(controller.updateConsent(EMAIL, new ConsentUpdateRequest(null, true)))
                    .assertNext(response -> {
                        assertThat(response.siteAnalyticsConsent()).isNull();
                        assertThat(response.emailAnalyticsConsent()).isNull();
                    })
                    .verifyComplete();

            verify(subscriberRepository, never()).updateAnalyticsConsent(anyLong(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/account/deletion-preview")
    class DeletionPreview {

        @Test
        @DisplayName("delegates to the service with the principal email")
        void returnsThePreview() {
            DeletionPreviewResponse preview = new DeletionPreviewResponse(true, "CONFIRMED", 5L, 2L);
            when(accountService.deletionPreview(EMAIL)).thenReturn(Mono.just(preview));

            StepVerifier.create(controller.deletionPreview(EMAIL))
                    .expectNext(preview)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/account")
    class DeleteAccount {

        @Test
        @DisplayName("DEACTIVATE delegates with the parsed mode and the newsletter choice")
        void deactivateDelegates() {
            when(accountService.deleteAccount(EMAIL, "pw", AccountService.Mode.DEACTIVATE, false))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteAccount(EMAIL,
                            new AccountDeletionRequest("pw", "DEACTIVATE", false)))
                    .verifyComplete();

            verify(accountService).deleteAccount(EMAIL, "pw", AccountService.Mode.DEACTIVATE, false);
        }

        @Test
        @DisplayName("ERASE delegates with cancelNewsletter=true")
        void eraseDelegates() {
            when(accountService.deleteAccount(EMAIL, "pw", AccountService.Mode.ERASE, true))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteAccount(EMAIL,
                            new AccountDeletionRequest("pw", "ERASE", true)))
                    .verifyComplete();

            verify(accountService).deleteAccount(EMAIL, "pw", AccountService.Mode.ERASE, true);
        }

        @Test
        @DisplayName("unknown mode is 400 before anything else happens")
        void unknownModeIsBadRequest() {
            StepVerifier.create(controller.deleteAccount(EMAIL,
                            new AccountDeletionRequest("pw", "BANANA", false)))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(ResponseStatusException.class);
                        ResponseStatusException rse = (ResponseStatusException) error;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(rse.getReason()).isEqualTo("error.invalid_request");
                    })
                    .verify();

            verify(accountService, never()).deleteAccount(anyString(), anyString(), any(), anyBoolean());
        }

        @Test
        @DisplayName("missing mode is 400 as well")
        void nullModeIsBadRequest() {
            StepVerifier.create(controller.deleteAccount(EMAIL,
                            new AccountDeletionRequest("pw", null, false)))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(ResponseStatusException.class);
                        assertThat(((ResponseStatusException) error).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST);
                    })
                    .verify();

            verify(accountService, never()).deleteAccount(anyString(), anyString(), any(), anyBoolean());
        }
    }
}
