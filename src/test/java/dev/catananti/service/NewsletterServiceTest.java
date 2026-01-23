package dev.catananti.service;

import dev.catananti.dto.PageResponse;
import dev.catananti.dto.SubscribeRequest;
import dev.catananti.dto.SubscriberResponse;
import dev.catananti.entity.Subscriber;
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
    @Mock private EmailService emailService;
    @Mock private IdService idService;
    @Mock private HtmlSanitizerService htmlSanitizerService;
    @Mock private NotificationEventService notificationEventService;
    @Mock private dev.catananti.metrics.BlogMetrics blogMetrics;

    @InjectMocks
    private NewsletterService newsletterService;

    @BeforeEach
    void setUp() {
        lenient().when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));

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
                    .status("CONFIRMED")
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
                    .status("UNSUBSCRIBED")
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
                    .status("PENDING")
                    .confirmationToken("valid-token-123")
                    .createdAt(LocalDateTime.now().minusHours(1))
                    .build();

            when(subscriberRepository.findByConfirmationToken("valid-token-123"))
                    .thenReturn(Mono.just(pending));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(emailService.sendNewsletterWelcome(anyString(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.confirmSubscription("valid-token-123"))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("success.newsletter_confirmed");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return already confirmed message for re-confirmation")
        void shouldHandleReConfirmation() {
            Subscriber confirmed = Subscriber.builder()
                    .id(4001L)
                    .email("confirmed@example.com")
                    .status("CONFIRMED")
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
                    .status("PENDING")
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
        @DisplayName("Should unsubscribe by email")
        void shouldUnsubscribeByEmail() {
            Subscriber active = Subscriber.builder()
                    .id(4001L)
                    .email("active@example.com")
                    .status("CONFIRMED")
                    .build();

            when(subscriberRepository.findByEmail("active@example.com"))
                    .thenReturn(Mono.just(active));
            when(subscriberRepository.save(any(Subscriber.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(newsletterService.unsubscribe("active@example.com"))
                    .assertNext(response -> {
                        assertThat(response.get("message")).isEqualTo("success.newsletter_unsubscribed");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for unknown email")
        void shouldThrowForUnknownEmail() {
            when(subscriberRepository.findByEmail("unknown@example.com"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(newsletterService.unsubscribe("unknown@example.com"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
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
                    .status("CONFIRMED")
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
                    .id(4001L).email("sub@example.com").status("CONFIRMED").build();

            when(subscriberRepository.findByStatus("CONFIRMED"))
                    .thenReturn(Flux.just(sub));

            StepVerifier.create(newsletterService.getAllSubscribers("confirmed").collectList())
                    .assertNext(subs -> {
                        assertThat(subs).hasSize(1);
                        assertThat(subs.getFirst().getStatus()).isEqualTo("CONFIRMED");
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
            Subscriber sub1 = Subscriber.builder()
                    .id(4001L).email("sub1@example.com").name("Sub One")
                    .status("CONFIRMED").createdAt(LocalDateTime.now()).build();
            Subscriber sub2 = Subscriber.builder()
                    .id(4002L).email("sub2@example.com").name("Sub Two")
                    .status("PENDING").createdAt(LocalDateTime.now()).build();

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
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return paginated subscribers with status filter")
        void shouldReturnPaginatedWithStatusFilter() {
            Subscriber confirmed = Subscriber.builder()
                    .id(4001L).email("confirmed@example.com").name("Confirmed")
                    .status("CONFIRMED").createdAt(LocalDateTime.now()).build();

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
                    .status("CONFIRMED").createdAt(LocalDateTime.now()).build();

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
                    .status("CONFIRMED").createdAt(LocalDateTime.now()).build();

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
                    .status("CONFIRMED")
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
                    "UNSUBSCRIBED".equals(s.getStatus()) && s.getUnsubscribedAt() != null));
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
                    .id(1L).email("one@example.com").status("CONFIRMED").build();
            Subscriber sub2 = Subscriber.builder()
                    .id(2L).email("two@example.com").status("CONFIRMED").build();

            when(subscriberRepository.findAllConfirmed()).thenReturn(Flux.just(sub1, sub2));

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
                    .status("PENDING")
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
                    .status("PENDING")
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
