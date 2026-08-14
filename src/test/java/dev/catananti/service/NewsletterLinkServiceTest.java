package dev.catananti.service;

import dev.catananti.entity.Subscriber;
import dev.catananti.entity.SubscriberStatus;
import dev.catananti.repository.SubscriberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Matriz de estados da spec: os 4 estados do vínculo × os gatilhos, mais a
 * exceção que confirma a regra — quem recusou não re-vincula automaticamente,
 * mas consegue quando pede pela área da conta.
 */
@ExtendWith(MockitoExtension.class)
class NewsletterLinkServiceTest {

    @Mock private SubscriberRepository subscriberRepository;
    @Mock private AuditService auditService;

    @InjectMocks
    private NewsletterLinkService service;

    private Subscriber subscriber(SubscriberStatus status) {
        return Subscriber.builder()
                .id(50L)
                .email("ana@test.dev")
                .status(status)
                .build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(auditService.logAction(anyString(), anyString(), anyString(), anyLong(), any(), anyString()))
                .thenReturn(Mono.empty());
    }

    @Nested
    @DisplayName("autoLink")
    class AutoLink {

        @Test
        @DisplayName("vincula inscrito CONFIRMED nunca vinculado")
        void linksConfirmedNeverLinkedSubscriber() {
            when(subscriberRepository.findByEmail("ana@test.dev"))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED)));
            when(subscriberRepository.autoLink(eq(50L), eq(10L), eq("AUTO_REGISTER"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.autoLink(10L, "ana@test.dev", "AUTO_REGISTER"))
                    .expectNext(true)
                    .verifyComplete();

            verify(auditService).logAction(eq("NEWSLETTER_LINKED"), eq("SUBSCRIBER"), eq("50"),
                    eq(10L), any(), anyString());
        }

        @Test
        @DisplayName("quem desvinculou com unlinked_by=USER não re-vincula automaticamente")
        void doesNotRelinkWhenHolderRefused() {
            // The refusal lives in the SQL guard, so the repository answers 0 rows.
            when(subscriberRepository.findByEmail("ana@test.dev"))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED)));
            when(subscriberRepository.autoLink(eq(50L), eq(10L), eq("AUTO_SUBSCRIBE"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));

            StepVerifier.create(service.autoLink(10L, "ana@test.dev", "AUTO_SUBSCRIBE"))
                    .expectNext(false)
                    .verifyComplete();

            verify(auditService, never()).logAction(anyString(), anyString(), anyString(), anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("DuplicateKeyException do índice único é sucesso idempotente")
        void treatsDuplicateKeyAsIdempotentSuccess() {
            when(subscriberRepository.findByEmail("ana@test.dev"))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED)));
            when(subscriberRepository.autoLink(eq(50L), eq(10L), eq("AUTO_REGISTER"), any(LocalDateTime.class)))
                    .thenReturn(Mono.error(new DuplicateKeyException("uq_subscribers_user_id")));

            StepVerifier.create(service.autoLink(10L, "ana@test.dev", "AUTO_REGISTER"))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("inscrito PENDING não vincula — ainda não provou a posse do endereço")
        void doesNotLinkPendingSubscriber() {
            when(subscriberRepository.findByEmail("ana@test.dev"))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.PENDING)));

            StepVerifier.create(service.autoLink(10L, "ana@test.dev", "AUTO_REGISTER"))
                    .expectNext(false)
                    .verifyComplete();

            verify(subscriberRepository, never()).autoLink(anyLong(), anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("sem inscrito para o e-mail, completa com false")
        void completesFalseWhenNoSubscriberExists() {
            when(subscriberRepository.findByEmail("ana@test.dev")).thenReturn(Mono.empty());

            StepVerifier.create(service.autoLink(10L, "ana@test.dev", "AUTO_REGISTER"))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("linkOnRequest")
    class LinkOnRequest {

        @Test
        @DisplayName("quem recusou consegue re-vincular quando pede pela área da conta")
        void relinksWhenTheHolderAsksDespiteEarlierRefusal() {
            // linkIgnoringRefusal has no unlinked_by guard: the earlier refusal is
            // exactly what the holder is revoking. Without this behavior the guard
            // would be a door locked from the outside.
            when(subscriberRepository.findByEmail("ana@test.dev"))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED)));
            when(subscriberRepository.linkIgnoringRefusal(eq(50L), eq(10L), eq("MANUAL_USER"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.linkOnRequest(10L, "ana@test.dev"))
                    .expectNext(true)
                    .verifyComplete();

            verify(subscriberRepository, never()).autoLink(anyLong(), anyLong(), anyString(), any());
            verify(auditService).logAction(eq("NEWSLETTER_LINKED"), eq("SUBSCRIBER"), eq("50"),
                    eq(10L), any(), anyString());
        }

        @Test
        @DisplayName("sem inscrito elegível responde 400 error.newsletter_link_unavailable")
        void rejectsWhenNoSubscriberExists() {
            when(subscriberRepository.findByEmail("ana@test.dev")).thenReturn(Mono.empty());

            StepVerifier.create(service.linkOnRequest(10L, "ana@test.dev"))
                    .expectErrorSatisfies(e -> {
                        ResponseStatusException rse = (ResponseStatusException) e;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(rse.getReason()).isEqualTo("error.newsletter_link_unavailable");
                    })
                    .verify();
        }

        @Test
        @DisplayName("inscrito PENDING não é elegível nem a pedido")
        void rejectsPendingSubscriber() {
            when(subscriberRepository.findByEmail("ana@test.dev"))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.PENDING)));

            StepVerifier.create(service.linkOnRequest(10L, "ana@test.dev"))
                    .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST))
                    .verify();

            verify(subscriberRepository, never()).linkIgnoringRefusal(anyLong(), anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("corrida: quem obtém 0 linhas não vinculou e não audita")
        void losingTheRaceYieldsFalse() {
            when(subscriberRepository.findByEmail("ana@test.dev"))
                    .thenReturn(Mono.just(subscriber(SubscriberStatus.CONFIRMED)));
            when(subscriberRepository.linkIgnoringRefusal(eq(50L), eq(10L), eq("MANUAL_USER"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));

            StepVerifier.create(service.linkOnRequest(10L, "ana@test.dev"))
                    .expectNext(false)
                    .verifyComplete();

            verify(auditService, never()).logAction(anyString(), anyString(), anyString(), anyLong(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("unlink")
    class Unlink {

        @Test
        @DisplayName("desfaz o vínculo e audita quem desfez")
        void unlinksAndAudits() {
            when(subscriberRepository.unlink(eq(10L), eq("USER"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.unlink(10L, "USER"))
                    .expectNext(true)
                    .verifyComplete();

            verify(auditService).logAction(eq("NEWSLETTER_UNLINKED"), eq("USER"), eq("10"),
                    eq(10L), any(), anyString());
        }

        @Test
        @DisplayName("sem vínculo existente devolve false sem auditar")
        void returnsFalseWhenNothingWasLinked() {
            when(subscriberRepository.unlink(eq(10L), eq("USER"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));

            StepVerifier.create(service.unlink(10L, "USER"))
                    .expectNext(false)
                    .verifyComplete();

            verify(auditService, never()).logAction(anyString(), anyString(), anyString(), anyLong(), any(), anyString());
        }
    }
}
