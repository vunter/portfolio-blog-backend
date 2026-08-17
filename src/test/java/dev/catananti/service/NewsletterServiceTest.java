package dev.catananti.service;

import dev.catananti.dto.PageResponse;
import dev.catananti.dto.SubscribeRequest;
import dev.catananti.dto.SubscriberResponse;
import dev.catananti.entity.Subscriber;
import dev.catananti.entity.SubscriberStatus;
import dev.catananti.exception.DuplicateResourceException;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.SubscriberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NewsletterServiceTest {

    @Mock private SubscriberRepository subscriberRepository;
    @Mock private dev.catananti.repository.UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private IdService idService;
    @Mock private HtmlSanitizerService htmlSanitizerService;
    @Mock private NotificationEventService notificationEventService;
    @Mock private NewsletterLinkService newsletterLinkService;
    @Mock private dev.catananti.metrics.BlogMetrics blogMetrics;
    @Mock private dev.catananti.config.PaginationConfig paginationConfig;

    @InjectMocks
    private NewsletterService newsletterService;

    @BeforeEach
    void setUp() {
        lenient().when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paginationConfig.getBulkQueryMax()).thenReturn(1000);
        // most confirmation tests have no account behind the subscriber email
        lenient().when(userRepository.findByEmail(anyString())).thenReturn(Mono.empty());

        ReflectionTestUtils.setField(newsletterService, "siteUrl", "https://catananti.dev");
        ReflectionTestUtils.setField(newsletterService, "confirmationExpirationHours", 48);
    }

    @Nested
    @DisplayName("subscribe")
    class Subscribe {

        @Test
        @DisplayName("Should create new subscriber with pending status")
        void shouldCreateNewSubscriber() {
            SubscribeRequest request = SubscribeRequest.builder()
                    .email("joao@example.com")
                    .name("João Silva")
                    .build();

            when(subscriberRepository.findByEmail("joao@example.com"))
                    .thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(4001L);
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(emailService.sendNewsletterConfirmation(anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.subscribe(request))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("success.newsletter_confirm_email");
                    })
                    .verifyComplete();

            verify(subscriberRepository).save(any(Subscriber.class));
        }

        @Test
        @DisplayName("Should throw DuplicateResourceException for already confirmed email")
        void shouldThrowForAlreadyConfirmed() {
            Subscriber confirmed = Subscriber.builder()
                    .id(4001L)
                    .email("existing@example.com")
                    .status(SubscriberStatus.CONFIRMED)
                    .build();

            when(subscriberRepository.findByEmail("existing@example.com"))
                    .thenReturn(Mono.just(confirmed));
            // Safety mock: switchIfEmpty eagerly evaluates createNewSubscriber which calls save
            when(idService.nextId()).thenReturn(9999L);
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            SubscribeRequest request = SubscribeRequest.builder()
                    .email("existing@example.com")
                    .name("Test")
                    .build();

            StepVerifier.create(newsletterService.subscribe(request))
                    .expectError(DuplicateResourceException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should resubscribe unsubscribed user")
        void shouldResubscribeUnsubscribed() {
            Subscriber unsubscribed = Subscriber.builder()
                    .id(4002L)
                    .email("unsub@example.com")
                    .status(SubscriberStatus.UNSUBSCRIBED)
                    .unsubscribedAt(LocalDateTime.now().minusDays(10))
                    .build();

            when(subscriberRepository.findByEmail("unsub@example.com"))
                    .thenReturn(Mono.just(unsubscribed));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(emailService.sendNewsletterConfirmation(anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            SubscribeRequest request = SubscribeRequest.builder()
                    .email("unsub@example.com")
                    .name("Re-subscriber")
                    .build();

            StepVerifier.create(newsletterService.subscribe(request))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("success.newsletter_confirm_email");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("confirmSubscription")
    class ConfirmSubscription {

        @Test
        @DisplayName("Should confirm pending subscription with valid token")
        void shouldConfirmSubscription() {
            Subscriber pending = Subscriber.builder()
                    .id(4001L)
                    .email("pending@example.com")
                    .name("Pending User")
                    .status(SubscriberStatus.PENDING)
                    .confirmationToken("valid-token-123")
                    .createdAt(LocalDateTime.now().minusHours(1))
                    .build();

            when(subscriberRepository.findByConfirmationToken("valid-token-123"))
                    .thenReturn(Mono.just(pending));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(emailService.sendNewsletterWelcome(anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.confirmSubscription("valid-token-123"))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("success.newsletter_confirmed");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should auto-link a verified account after confirming the subscription")
        void shouldAutoLinkVerifiedAccountAfterConfirmation() {
            Subscriber pending = Subscriber.builder()
                    .id(4001L)
                    .email("owner@example.com")
                    .status(SubscriberStatus.PENDING)
                    .confirmationToken("valid-token-123")
                    .createdAt(LocalDateTime.now().minusHours(1))
                    .build();
            dev.catananti.entity.User verifiedUser = dev.catananti.entity.User.builder()
                    .id(77L).email("owner@example.com").name("Owner").emailVerified(true).build();

            when(subscriberRepository.findByConfirmationToken("valid-token-123"))
                    .thenReturn(Mono.just(pending));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(userRepository.findByEmail("owner@example.com")).thenReturn(Mono.just(verifiedUser));
            when(newsletterLinkService.autoLink(77L, "owner@example.com", "AUTO_SUBSCRIBE"))
                    .thenReturn(Mono.just(true));
            when(emailService.sendNewsletterWelcome(anyString(), any(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.confirmSubscription("valid-token-123"))
                    .assertNext(response ->
                            assertThat(response.get("message")).isEqualTo("success.newsletter_confirmed"))
                    .verifyComplete();

            verify(newsletterLinkService).autoLink(77L, "owner@example.com", "AUTO_SUBSCRIBE");
        }

        @Test
        @DisplayName("Should not link when the account email is not verified")
        void shouldNotLinkUnverifiedAccount() {
            Subscriber pending = Subscriber.builder()
                    .id(4001L)
                    .email("owner@example.com")
                    .status(SubscriberStatus.PENDING)
                    .confirmationToken("valid-token-123")
                    .createdAt(LocalDateTime.now().minusHours(1))
                    .build();
            dev.catananti.entity.User unverifiedUser = dev.catananti.entity.User.builder()
                    .id(77L).email("owner@example.com").name("Owner").emailVerified(false).build();

            when(subscriberRepository.findByConfirmationToken("valid-token-123"))
                    .thenReturn(Mono.just(pending));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(userRepository.findByEmail("owner@example.com")).thenReturn(Mono.just(unverifiedUser));
            when(emailService.sendNewsletterWelcome(anyString(), any(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.confirmSubscription("valid-token-123"))
                    .assertNext(response ->
                            assertThat(response.get("message")).isEqualTo("success.newsletter_confirmed"))
                    .verifyComplete();

            verify(newsletterLinkService, never()).autoLink(any(), any(), any());
        }

        @Test
        @DisplayName("Should confirm the subscription even when the account link fails")
        void shouldConfirmEvenWhenLinkFails() {
            // The link is best-effort; a failure there must never undo the confirmation.
            Subscriber pending = Subscriber.builder()
                    .id(4001L)
                    .email("owner@example.com")
                    .status(SubscriberStatus.PENDING)
                    .confirmationToken("valid-token-123")
                    .createdAt(LocalDateTime.now().minusHours(1))
                    .build();
            dev.catananti.entity.User verifiedUser = dev.catananti.entity.User.builder()
                    .id(77L).email("owner@example.com").name("Owner").emailVerified(true).build();

            when(subscriberRepository.findByConfirmationToken("valid-token-123"))
                    .thenReturn(Mono.just(pending));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(userRepository.findByEmail("owner@example.com")).thenReturn(Mono.just(verifiedUser));
            when(newsletterLinkService.autoLink(77L, "owner@example.com", "AUTO_SUBSCRIBE"))
                    .thenReturn(Mono.error(new RuntimeException("db hiccup")));
            when(emailService.sendNewsletterWelcome(anyString(), any(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.confirmSubscription("valid-token-123"))
                    .assertNext(response ->
                            assertThat(response.get("message")).isEqualTo("success.newsletter_confirmed"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return already confirmed message for re-confirmation")
        void shouldHandleReConfirmation() {
            Subscriber confirmed = Subscriber.builder()
                    .id(4001L)
                    .email("confirmed@example.com")
                    .status(SubscriberStatus.CONFIRMED)
                    .confirmationToken("old-token")
                    .build();

            when(subscriberRepository.findByConfirmationToken("old-token"))
                    .thenReturn(Mono.just(confirmed));

            StepVerifier.create(newsletterService.confirmSubscription("old-token"))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("success.newsletter_already_confirmed");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw for expired confirmation token")
        void shouldThrowForExpiredToken() {
            Subscriber expired = Subscriber.builder()
                    .id(4001L)
                    .email("expired@example.com")
                    .status(SubscriberStatus.PENDING)
                    .confirmationToken("expired-token")
                    .createdAt(LocalDateTime.now().minusHours(72)) // > 48h expiration
                    .build();

            when(subscriberRepository.findByConfirmationToken("expired-token"))
                    .thenReturn(Mono.just(expired));

            StepVerifier.create(newsletterService.confirmSubscription("expired-token"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should throw for invalid token")
        void shouldThrowForInvalidToken() {
            when(subscriberRepository.findByConfirmationToken("invalid"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.confirmSubscription("invalid"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("unsubscribe")
    class Unsubscribe {

        @Test
        @DisplayName("Should send unsubscribe confirmation by email")
        void shouldRequestUnsubscribeConfirmation() {
            Subscriber active = Subscriber.builder()
                    .id(4001L)
                    .email("active@example.com")
                    .name("Active User")
                    .status(SubscriberStatus.CONFIRMED)
                    .unsubscribeToken("unsub-token")
                    .build();

            when(subscriberRepository.findByEmail("active@example.com"))
                    .thenReturn(Mono.just(active));
            when(emailService.sendNewsletterUnsubscribeConfirmation("active@example.com", "Active User", "unsub-token"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.unsubscribe("active@example.com"))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("success.generic_unsubscribe");
                    })
                    .verifyComplete();

            verify(emailService).sendNewsletterUnsubscribeConfirmation("active@example.com", "Active User", "unsub-token");
            verify(subscriberRepository, never()).save(any(Subscriber.class));
        }

        @Test
        @DisplayName("Should return a generic success message for an unknown email (anti-enumeration)")
        void shouldReturnGenericSuccessForUnknownEmail() {
            // Email-by-email unsubscribe must NOT reveal whether an address is subscribed,
            // otherwise it becomes a subscriber-enumeration oracle. Unknown emails get the
            // same generic success response as known ones.
            when(subscriberRepository.findByEmail("unknown@example.com"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.unsubscribe("unknown@example.com"))
                    .assertNext(response ->
                            assertThat(response.get("message")).isEqualTo("success.generic_unsubscribe"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("unsubscribeByToken")
    class UnsubscribeByToken {

        @Test
        @DisplayName("Should unsubscribe by token")
        void shouldUnsubscribeByToken() {
            Subscriber active = Subscriber.builder()
                    .id(4001L)
                    .email("active@example.com")
                    .status(SubscriberStatus.CONFIRMED)
                    .confirmationToken("unsub-token")
                    .build();

            when(subscriberRepository.findByUnsubscribeToken("unsub-token"))
                    .thenReturn(Mono.empty());
            when(subscriberRepository.findByConfirmationToken("unsub-token"))
                    .thenReturn(Mono.just(active));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(newsletterService.unsubscribeByToken("unsub-token"))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("success.newsletter_unsubscribed");
                    })
                    .verifyComplete();

            verify(blogMetrics).incrementUnsubscription();
        }
    }

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("Should return newsletter statistics")
        void shouldReturnStats() {
            when(subscriberRepository.countConfirmed()).thenReturn(Mono.just(150L));
            when(subscriberRepository.countPending()).thenReturn(Mono.just(25L));
            when(subscriberRepository.count()).thenReturn(Mono.just(200L));

            StepVerifier.create(newsletterService.getStats())
                    .assertNext(stats -> {
                        assertThat(stats.get("confirmed")).isEqualTo(150L);
                        assertThat(stats.get("pending")).isEqualTo(25L);
                        assertThat(stats.get("total")).isEqualTo(200L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getAllSubscribers")
    class GetAllSubscribers {

        @Test
        @DisplayName("Should return all subscribers filtered by status")
        void shouldFilterByStatus() {
            Subscriber sub = Subscriber.builder()
                    .id(4001L).email("sub@example.com").status(SubscriberStatus.CONFIRMED).build();

            when(subscriberRepository.findByStatus(eq("CONFIRMED"), anyInt()))
                    .thenReturn(Flux.just(sub));

            StepVerifier.create(newsletterService.getAllSubscribers("confirmed").collectList())
                    .assertNext(subs -> {
                        assertThat(subs).hasSize(1);
                        assertThat(subs.getFirst().getStatus()).isEqualTo(SubscriberStatus.CONFIRMED);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return all subscribers when no filter")
        void shouldReturnAll() {
            when(subscriberRepository.findAll()).thenReturn(Flux.empty());

            StepVerifier.create(newsletterService.getAllSubscribers(null).collectList())
                    .assertNext(subs -> assertThat(subs).isEmpty())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getAllSubscribersPaginated")
    class GetAllSubscribersPaginated {

        @Test
        @DisplayName("Should return paginated subscribers without status filter")
        void shouldReturnPaginatedWithoutFilter() {
            LocalDateTime linkedAt = LocalDateTime.of(2026, 8, 1, 12, 0);
            Subscriber sub1 = Subscriber.builder()
                    .id(4001L).email("sub1@example.com").name("Sub One")
                    .status(SubscriberStatus.CONFIRMED).createdAt(LocalDateTime.now())
                    .userId(77L).linkedAt(linkedAt).linkOrigin("AUTO_BACKFILL").build();
            Subscriber sub2 = Subscriber.builder()
                    .id(4002L).email("sub2@example.com").name("Sub Two")
                    .status(SubscriberStatus.PENDING).createdAt(LocalDateTime.now()).build();

            when(subscriberRepository.findAllPaginated(10, 0))
                    .thenReturn(Flux.just(sub1, sub2));
            when(subscriberRepository.count()).thenReturn(Mono.just(2L));

            StepVerifier.create(newsletterService.getAllSubscribersPaginated(null, null, 0, 10))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(2);
                        assertThat(page.getPage()).isZero();
                        assertThat(page.getTotalElements()).isEqualTo(2L);
                        assertThat(page.isFirst()).isTrue();
                        assertThat(page.isLast()).isTrue();
                        // admin panel sees the account link (userId as String — JS number range)
                        assertThat(page.getContent().getFirst().getUserId()).isEqualTo("77");
                        assertThat(page.getContent().getFirst().getLinkedAt()).isEqualTo(linkedAt);
                        assertThat(page.getContent().getFirst().getLinkOrigin()).isEqualTo("AUTO_BACKFILL");
                        assertThat(page.getContent().get(1).getUserId()).isNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return paginated subscribers with status filter")
        void shouldReturnPaginatedWithStatusFilter() {
            Subscriber confirmed = Subscriber.builder()
                    .id(4001L).email("confirmed@example.com").name("Confirmed")
                    .status(SubscriberStatus.CONFIRMED).createdAt(LocalDateTime.now()).build();

            when(subscriberRepository.findByStatusPaginated("CONFIRMED", 10, 0))
                    .thenReturn(Flux.just(confirmed));
            when(subscriberRepository.countByStatus("CONFIRMED")).thenReturn(Mono.just(1L));

            StepVerifier.create(newsletterService.getAllSubscribersPaginated("confirmed", null, 0, 10))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                        assertThat(page.getContent().getFirst().getEmail()).isEqualTo("confirmed@example.com");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should filter by email substring")
        void shouldFilterByEmail() {
            Subscriber sub1 = Subscriber.builder()
                    .id(4001L).email("joao@example.com").name("João")
                    .status(SubscriberStatus.CONFIRMED).createdAt(LocalDateTime.now()).build();

            when(subscriberRepository.findByEmailContainingPaginated("joao", 10, 0))
                    .thenReturn(Flux.just(sub1));
            when(subscriberRepository.countByEmailContaining("joao")).thenReturn(Mono.just(1L));

            StepVerifier.create(newsletterService.getAllSubscribersPaginated(null, "joao", 0, 10))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                        assertThat(page.getContent().getFirst().getEmail()).isEqualTo("joao@example.com");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should filter by both status and email")
        void shouldFilterByStatusAndEmail() {
            Subscriber sub1 = Subscriber.builder()
                    .id(4001L).email("joao@example.com").name("João")
                    .status(SubscriberStatus.CONFIRMED).createdAt(LocalDateTime.now()).build();

            when(subscriberRepository.findByStatusAndEmailContainingPaginated("CONFIRMED", "joao", 10, 0))
                    .thenReturn(Flux.just(sub1));
            when(subscriberRepository.countByStatusAndEmailContaining("CONFIRMED", "joao")).thenReturn(Mono.just(1L));

            StepVerifier.create(newsletterService.getAllSubscribersPaginated("confirmed", "joao", 0, 10))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                        assertThat(page.getContent().getFirst().getEmail()).isEqualTo("joao@example.com");
                    })
                    .verifyComplete();
        }
    }

    // ==================== Additional Coverage Tests ====================

    @Nested
    @DisplayName("unsubscribeByToken - additional")
    class UnsubscribeByTokenAdditional {

        @Test
        @DisplayName("Should unsubscribe via unsubscribe token (primary path)")
        void shouldUnsubscribeViaUnsubscribeToken() {
            Subscriber active = Subscriber.builder()
                    .id(4001L)
                    .email("active@example.com")
                    .status(SubscriberStatus.CONFIRMED)
                    .unsubscribeToken("real-unsub-token")
                    .build();

            when(subscriberRepository.findByUnsubscribeToken("real-unsub-token"))
                    .thenReturn(Mono.just(active));
            when(subscriberRepository.findByConfirmationToken("real-unsub-token"))
                    .thenReturn(Mono.empty());
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(newsletterService.unsubscribeByToken("real-unsub-token"))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("success.newsletter_unsubscribed");
                    })
                    .verifyComplete();

            verify(subscriberRepository).save(argThat(s ->
                    SubscriberStatus.UNSUBSCRIBED == s.getStatus() && s.getUnsubscribedAt() != null));
        }

        @Test
        @DisplayName("Should throw for unknown unsubscribe token")
        void shouldThrowForUnknownUnsubscribeToken() {
            when(subscriberRepository.findByUnsubscribeToken("bad-token"))
                    .thenReturn(Mono.empty());
            when(subscriberRepository.findByConfirmationToken("bad-token"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.unsubscribeByToken("bad-token"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("deleteSubscriber and batch operations")
    class DeleteOperations {

        @Test
        @DisplayName("Should delete single subscriber")
        void shouldDeleteSingleSubscriber() {
            when(subscriberRepository.deleteById(4001L)).thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.deleteSubscriber(4001L))
                    .verifyComplete();

            verify(subscriberRepository).deleteById(4001L);
        }

        @Test
        @DisplayName("Should batch delete subscribers")
        void shouldBatchDeleteSubscribers() {
            when(subscriberRepository.deleteAllByIdIn(java.util.List.of(1L, 2L, 3L)))
                    .thenReturn(Mono.just(3L));

            StepVerifier.create(newsletterService.deleteSubscribersBatch(java.util.List.of(1L, 2L, 3L)))
                    .expectNext(3L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle empty batch delete")
        void shouldHandleEmptyBatchDelete() {
            StepVerifier.create(newsletterService.deleteSubscribersBatch(java.util.List.of()))
                    .expectNext(0L)
                    .verifyComplete();

            verify(subscriberRepository, never()).deleteAllByIdIn(any());
        }
    }

    @Nested
    @DisplayName("getActiveSubscribers")
    class GetActiveSubscribers {

        @Test
        @DisplayName("Should return all confirmed subscribers")
        void shouldReturnConfirmedSubscribers() {
            Subscriber sub1 = Subscriber.builder()
                    .id(1L).email("one@example.com").status(SubscriberStatus.CONFIRMED).build();
            Subscriber sub2 = Subscriber.builder()
                    .id(2L).email("two@example.com").status(SubscriberStatus.CONFIRMED).build();

            when(subscriberRepository.findAllConfirmed(anyInt())).thenReturn(Flux.just(sub1, sub2));

            StepVerifier.create(newsletterService.getActiveSubscribers().collectList())
                    .assertNext(subs -> {
                        assertThat(subs).hasSize(2);
                        assertThat(subs).extracting(Subscriber::getEmail)
                                .containsExactly("one@example.com", "two@example.com");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("subscribe - pending token regeneration")
    class SubscribePendingRegeneration {

        @Test
        @DisplayName("Should regenerate token for expired pending subscriber")
        void shouldRegenerateTokenForExpiredPending() {
            Subscriber expiredPending = Subscriber.builder()
                    .id(4003L)
                    .email("pending@example.com")
                    .status(SubscriberStatus.PENDING)
                    .confirmationToken("old-token")
                    .createdAt(LocalDateTime.now().minusHours(72)) // > 48h expiration
                    .build();

            when(subscriberRepository.findByEmail("pending@example.com"))
                    .thenReturn(Mono.just(expiredPending));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(emailService.sendNewsletterConfirmation(anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            SubscribeRequest request = SubscribeRequest.builder()
                    .email("pending@example.com")
                    .name("Pending User")
                    .build();

            StepVerifier.create(newsletterService.subscribe(request))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("success.newsletter_confirm_email");
                    })
                    .verifyComplete();

            // Token should have been regenerated for the existing subscriber
            verify(subscriberRepository, atLeastOnce()).save(argThat(s ->
                    s.getId() != null && s.getId() == 4003L && !s.getConfirmationToken().equals("old-token")));
        }

        @Test
        @DisplayName("Should return existing response for non-expired pending subscriber")
        void shouldReturnExistingForNonExpiredPending() {
            Subscriber pendingNotExpired = Subscriber.builder()
                    .id(4004L)
                    .email("pending2@example.com")
                    .status(SubscriberStatus.PENDING)
                    .confirmationToken("still-valid-token")
                    .createdAt(LocalDateTime.now().minusHours(1)) // Not expired
                    .build();

            when(subscriberRepository.findByEmail("pending2@example.com"))
                    .thenReturn(Mono.just(pendingNotExpired));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(emailService.sendNewsletterConfirmation(anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            SubscribeRequest request = SubscribeRequest.builder()
                    .email("pending2@example.com")
                    .name("Pending Two")
                    .build();

            StepVerifier.create(newsletterService.subscribe(request))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("success.newsletter_confirm_email");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getAllSubscribers - additional")
    class GetAllSubscribersAdditional {

        @Test
        @DisplayName("Should return all subscribers with empty string filter")
        void shouldReturnAllWithEmptyFilter() {
            when(subscriberRepository.findAll()).thenReturn(Flux.empty());

            StepVerifier.create(newsletterService.getAllSubscribers("").collectList())
                    .assertNext(subs -> assertThat(subs).isEmpty())
                    .verifyComplete();
        }
    }
}
