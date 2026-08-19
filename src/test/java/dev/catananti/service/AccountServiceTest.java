package dev.catananti.service;

import dev.catananti.entity.Subscriber;
import dev.catananti.entity.SubscriberStatus;
import dev.catananti.entity.User;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.BookmarkRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.EmailChangeTokenRepository;
import dev.catananti.repository.EmailVerificationTokenRepository;
import dev.catananti.repository.MfaBackupCodeRepository;
import dev.catananti.repository.PasswordResetTokenRepository;
import dev.catananti.repository.ReadingHistoryRepository;
import dev.catananti.repository.RefreshTokenRepository;
import dev.catananti.repository.SearchQueryRepository;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.repository.UserMfaConfigRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.repository.UserSocialAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SubscriberRepository subscriberRepository;
    @Mock private CommentRepository commentRepository;
    @Mock private ArticleRepository articleRepository;
    @Mock private BookmarkRepository bookmarkRepository;
    @Mock private ReadingHistoryRepository readingHistoryRepository;
    @Mock private UserSocialAccountRepository socialAccountRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private EmailChangeTokenRepository emailChangeTokenRepository;
    @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private SearchQueryRepository searchQueryRepository;
    @Mock private UserMfaConfigRepository mfaConfigRepository;
    @Mock private MfaBackupCodeRepository mfaBackupCodeRepository;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AuditService auditService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserCacheService userCacheService;
    @Mock private TransactionalOperator transactionalOperator;

    @InjectMocks
    private AccountService service;

    private static final String EMAIL = "ana@test.dev";
    private static final String HASH = "$2a$12$storedhash";
    private static final Long USER_ID = 10L;

    private User user() {
        return User.builder().id(USER_ID).email(EMAIL).name("Ana")
                .passwordHash(HASH).active(true).build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(auditService.logAction(anyString(), anyString(), anyString(), anyLong(), any(), anyString()))
                .thenReturn(Mono.empty());
    }

    private void stubUserFound() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user()));
    }

    private void stubGoodPassword() {
        when(passwordEncoder.matches("pw", HASH)).thenReturn(true);
    }

    /** The common cascade shared by both modes, in the shape each repository returns. */
    private void stubCommonCascade() {
        when(passwordResetTokenRepository.deleteByUserId(USER_ID)).thenReturn(Mono.just(1L));
        when(emailChangeTokenRepository.deleteByUserId(USER_ID)).thenReturn(Mono.just(0L));
        when(emailVerificationTokenRepository.deleteByUserId(USER_ID)).thenReturn(Mono.just(1L));
        when(mfaConfigRepository.deleteByUserId(USER_ID)).thenReturn(Mono.empty());
        when(mfaBackupCodeRepository.deleteByUserId(USER_ID)).thenReturn(Mono.empty());
        when(socialAccountRepository.deleteByUserId(USER_ID)).thenReturn(Mono.just(1L));
        when(bookmarkRepository.deleteByUserId(USER_ID)).thenReturn(Mono.just(2L));
        when(readingHistoryRepository.deleteByUserId(USER_ID)).thenReturn(Mono.empty());
        when(searchQueryRepository.detachUser(USER_ID)).thenReturn(Mono.just(3L));
    }

    @Nested
    @DisplayName("reautenticação")
    class Reauthentication {

        @Test
        @DisplayName("AUD19C-A5: senha errada -> 400 (não 401), nenhum efeito colateral, nem transação aberta")
        void wrongPasswordHasNoEffect() {
            stubUserFound();
            when(passwordEncoder.matches("wrong", HASH)).thenReturn(false);

            StepVerifier.create(service.deleteAccount(EMAIL, "wrong", AccountService.Mode.DEACTIVATE, false))
                    .expectErrorSatisfies(err -> {
                        // reautenticação falha em requisição autenticada é 400 (validação de
                        // negócio), não 401 — 401 fazia o cliente descartar a sessão válida
                        org.assertj.core.api.Assertions.assertThat(err)
                                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
                        org.springframework.web.server.ResponseStatusException rse =
                                (org.springframework.web.server.ResponseStatusException) err;
                        org.assertj.core.api.Assertions.assertThat(rse.getStatusCode().value()).isEqualTo(400);
                        org.assertj.core.api.Assertions.assertThat(rse.getReason()).isEqualTo("error.password_invalid");
                    })
                    .verify();

            verify(transactionalOperator, never()).transactional(any(Mono.class));
            verify(userRepository, never()).deactivateAccount(anyLong(), any());
            verify(refreshTokenService, never()).revokeAllUserTokens(anyLong());
            verify(auditService, never()).logAction(anyString(), anyString(), anyString(), anyLong(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("DEACTIVATE — nível 1, reversível")
    class Deactivate {

        @Test
        @DisplayName("cascata comum + desativação; comments e vínculo newsletter intocados")
        void deactivateRunsCascadeButPreservesContentAndLink() {
            stubUserFound();
            stubGoodPassword();
            stubCommonCascade();
            when(userRepository.deactivateAccount(eq(USER_ID), any(LocalDateTime.class))).thenReturn(Mono.just(1L));
            when(refreshTokenService.revokeAllUserTokens(USER_ID)).thenReturn(Mono.empty());

            StepVerifier.create(service.deleteAccount(EMAIL, "pw", AccountService.Mode.DEACTIVATE, false))
                    .verifyComplete();

            verify(userRepository).deactivateAccount(eq(USER_ID), any(LocalDateTime.class));
            // reversible level: PII, comments and the newsletter link all stay
            verify(userRepository, never()).eraseAccount(anyLong(), anyString(), anyString(), anyString(), any());
            verify(commentRepository, never()).anonymizeByOwner(anyLong(), anyString(), anyString());
            verify(subscriberRepository, never()).unlink(anyLong(), anyString(), any());
            verify(subscriberRepository, never()).unsubscribeByUserId(anyLong(), any());
            // sessions are revoked (kept as audit trail), not deleted
            verify(refreshTokenService).revokeAllUserTokens(USER_ID);
            verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
            // AUD18-L3 / F-046: cached auth snapshot dropped for immediate lockout
            verify(userCacheService).evict(USER_ID);
            // audit carries the userId only — no email
            verify(auditService).logAction(eq("ACCOUNT_DEACTIVATED"), eq("USER"),
                    eq(USER_ID.toString()), eq(USER_ID), isNull(), anyString());
        }

        @Test
        @DisplayName("cancelNewsletter=true cancela os envios mantendo o vínculo")
        void deactivateWithCancelNewsletterUnsubscribes() {
            stubUserFound();
            stubGoodPassword();
            stubCommonCascade();
            when(subscriberRepository.unsubscribeByUserId(eq(USER_ID), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1L));
            when(userRepository.deactivateAccount(eq(USER_ID), any(LocalDateTime.class))).thenReturn(Mono.just(1L));
            when(refreshTokenService.revokeAllUserTokens(USER_ID)).thenReturn(Mono.empty());

            StepVerifier.create(service.deleteAccount(EMAIL, "pw", AccountService.Mode.DEACTIVATE, true))
                    .verifyComplete();

            verify(subscriberRepository).unsubscribeByUserId(eq(USER_ID), any(LocalDateTime.class));
            verify(subscriberRepository, never()).unlink(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("segunda desativação: rows=0 vira 409 e nada roda depois")
        void secondDeactivateIsConflict() {
            stubUserFound();
            stubGoodPassword();
            stubCommonCascade();
            when(userRepository.deactivateAccount(eq(USER_ID), any(LocalDateTime.class))).thenReturn(Mono.just(0L));

            StepVerifier.create(service.deleteAccount(EMAIL, "pw", AccountService.Mode.DEACTIVATE, false))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(ResponseStatusException.class);
                        ResponseStatusException rse = (ResponseStatusException) error;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(rse.getReason()).isEqualTo("error.account_already_deleted");
                    })
                    .verify();

            // the post-commit steps must not run when the transaction failed
            verify(refreshTokenService, never()).revokeAllUserTokens(anyLong());
            verify(userCacheService, never()).evict(anyLong());
            verify(auditService, never()).logAction(anyString(), anyString(), anyString(), anyLong(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("ERASE — nível 2, LGPD art. 18, VI")
    class Erase {

        private void stubErase() {
            stubUserFound();
            stubGoodPassword();
            stubCommonCascade();
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$scrambled");
            when(subscriberRepository.unlink(eq(USER_ID), eq("ACCOUNT_DELETED"), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1L));
            when(userRepository.eraseAccount(eq(USER_ID), anyString(), anyString(), anyString(),
                    any(LocalDateTime.class))).thenReturn(Mono.just(1L));
            when(commentRepository.anonymizeByOwner(eq(USER_ID), anyString(), anyString())).thenReturn(Mono.just(4L));
            when(refreshTokenRepository.deleteByUserId(USER_ID)).thenReturn(Mono.just(2L));
        }

        @Test
        @DisplayName("anonimiza users e comments com o mesmo nome, mantendo a unicidade do e-mail")
        void eraseAnonymizesUserAndComments() {
            stubErase();

            StepVerifier.create(service.deleteAccount(EMAIL, "pw", AccountService.Mode.ERASE, false))
                    .verifyComplete();

            verify(userRepository).eraseAccount(eq(USER_ID),
                    eq("erased-10@anonymized.invalid"),
                    eq(AccountService.ANONYMIZED_NAME),
                    eq("$2a$12$scrambled"),
                    any(LocalDateTime.class));
            // same constant on both tables; user_id stays by contract of the query
            verify(commentRepository).anonymizeByOwner(eq(USER_ID), anyString(), eq(AccountService.ANONYMIZED_NAME));
            // erasure always drops the link, recorded as ACCOUNT_DELETED (re-linkable)
            verify(subscriberRepository).unlink(eq(USER_ID), eq("ACCOUNT_DELETED"), any(LocalDateTime.class));
            // tokens are deleted, not merely revoked
            verify(refreshTokenRepository).deleteByUserId(USER_ID);
            verify(refreshTokenService, never()).revokeAllUserTokens(anyLong());
            verify(auditService).logAction(eq("ACCOUNT_ERASED"), eq("USER"),
                    eq(USER_ID.toString()), eq(USER_ID), isNull(), anyString());
        }

        @Test
        @DisplayName("cancelNewsletter=true cancela a inscrição antes de desvincular")
        void eraseWithCancelNewsletterUnsubscribesThenUnlinks() {
            stubErase();
            when(subscriberRepository.unsubscribeByUserId(eq(USER_ID), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.deleteAccount(EMAIL, "pw", AccountService.Mode.ERASE, true))
                    .verifyComplete();

            // unlink nulls user_id, so the user_id-keyed unsubscribe must run first
            InOrder inOrder = inOrder(subscriberRepository);
            inOrder.verify(subscriberRepository).unsubscribeByUserId(eq(USER_ID), any(LocalDateTime.class));
            inOrder.verify(subscriberRepository).unlink(eq(USER_ID), eq("ACCOUNT_DELETED"), any(LocalDateTime.class));
        }
    }

    @Nested
    @DisplayName("deletion preview")
    class DeletionPreview {

        @Test
        @DisplayName("conta com inscrição vinculada e conteúdo")
        void previewWithLinkedSubscription() {
            stubUserFound();
            when(subscriberRepository.findByUserId(USER_ID)).thenReturn(Mono.just(
                    Subscriber.builder().id(50L).email(EMAIL).status(SubscriberStatus.CONFIRMED)
                            .userId(USER_ID).build()));
            when(commentRepository.countByUserId(USER_ID)).thenReturn(Mono.just(5L));
            when(articleRepository.countByAuthorId(USER_ID)).thenReturn(Mono.just(2L));

            StepVerifier.create(service.deletionPreview(EMAIL))
                    .assertNext(preview -> {
                        assertThat(preview.newsletterLinked()).isTrue();
                        assertThat(preview.newsletterStatus()).isEqualTo("CONFIRMED");
                        assertThat(preview.commentsCount()).isEqualTo(5L);
                        assertThat(preview.articlesCount()).isEqualTo(2L);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("conta sem inscrição vinculada")
        void previewWithoutLinkedSubscription() {
            stubUserFound();
            when(subscriberRepository.findByUserId(USER_ID)).thenReturn(Mono.empty());
            when(commentRepository.countByUserId(USER_ID)).thenReturn(Mono.just(0L));
            when(articleRepository.countByAuthorId(USER_ID)).thenReturn(Mono.just(0L));

            StepVerifier.create(service.deletionPreview(EMAIL))
                    .assertNext(preview -> {
                        assertThat(preview.newsletterLinked()).isFalse();
                        assertThat(preview.newsletterStatus()).isNull();
                        assertThat(preview.commentsCount()).isZero();
                        assertThat(preview.articlesCount()).isZero();
                    })
                    .verifyComplete();
        }
    }
}
