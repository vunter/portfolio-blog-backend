package dev.catananti.service;

import dev.catananti.dto.DeletionPreviewResponse;
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
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Account deletion by the holder (LGPD art. 18). Two operations, not one:
 *
 * <p><b>DEACTIVATE</b> (level 1) — the account goes off the air, public content
 * keeps its authorship, internal traceability is total. Reversible.
 *
 * <p><b>ERASE</b> (level 2, art. 18, VI) — everything from level 1 plus
 * anonymization of the PII in {@code users} and {@code comments}. The
 * {@code user_id} on content tables is KEPT: it points at a row that no longer
 * re-identifies anyone, preserving referential integrity and statistics without
 * personal data (art. 16, IV). Irreversible.
 *
 * <p>{@code articles.author_id} is never touched — published content stays, in
 * ERASE pointing at the anonymized row. Audit logs are retained (art. 16, I).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    public enum Mode { DEACTIVATE, ERASE }

    /** Spec value shown as the author of anonymized content (users.name and comments.author_name). */
    public static final String ANONYMIZED_NAME = "Usuário removido";

    /** Recorded on the subscriber unlink; unlike 'USER', it never blocks automatic re-linking. */
    static final String UNLINKED_BY_ACCOUNT_DELETED = "ACCOUNT_DELETED";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final SubscriberRepository subscriberRepository;
    private final CommentRepository commentRepository;
    private final ArticleRepository articleRepository;
    private final BookmarkRepository bookmarkRepository;
    private final ReadingHistoryRepository readingHistoryRepository;
    private final UserSocialAccountRepository socialAccountRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailChangeTokenRepository emailChangeTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SearchQueryRepository searchQueryRepository;
    private final UserMfaConfigRepository mfaConfigRepository;
    private final MfaBackupCodeRepository mfaBackupCodeRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionalOperator transactionalOperator;

    /** What deletion will touch, so the screen can inform before the holder decides. */
    public Mono<DeletionPreviewResponse> deletionPreview(String principalEmail) {
        return requireUser(principalEmail)
                .flatMap(user -> subscriberRepository.findByUserId(user.getId())
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(maybeSub -> Mono.zip(
                                        commentRepository.countByUserId(user.getId()),
                                        articleRepository.countByAuthorId(user.getId()))
                                .map(counts -> new DeletionPreviewResponse(
                                        maybeSub.isPresent(),
                                        maybeSub.map(sub -> sub.getStatus() != null
                                                ? sub.getStatus().name() : null).orElse(null),
                                        counts.getT1(),
                                        counts.getT2()))));
    }

    /**
     * Deletes the account after mandatory reauthentication. The cascade commits
     * atomically; token revocation and the audit entry run only after the commit,
     * so a rolled-back deletion leaves sessions working.
     */
    public Mono<Void> deleteAccount(String principalEmail, String rawPassword,
                                    Mode mode, boolean cancelNewsletter) {
        return requireUser(principalEmail)
                .flatMap(user -> reauthenticate(user, rawPassword))
                .flatMap(user -> prepareScrambledHash(mode)
                        .flatMap(scrambledHash -> {
                            Long userId = user.getId();
                            LocalDateTime now = LocalDateTime.now();
                            Mono<Void> finalStep = mode == Mode.DEACTIVATE
                                    ? deactivate(userId, now)
                                    : erase(userId, user.getEmail(), scrambledHash, now);
                            // defer: the post-commit steps must not even be assembled
                            // when the transaction errors out (e.g. the 409 above)
                            return transactionalOperator.transactional(
                                            commonCascade(userId, mode, cancelNewsletter, now)
                                                    .then(finalStep))
                                    .then(Mono.defer(() -> revokeSessions(userId, mode)))
                                    .then(Mono.defer(() -> audit(userId, mode)));
                        }));
    }

    // ---------------------------------------------------------------- steps

    private Mono<User> requireUser(String email) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "error.user_not_found")));
    }

    private Mono<User> reauthenticate(User user, String rawPassword) {
        // BCrypt is blocking — same offloading as AuthService.verifyCredentials
        return Mono.fromCallable(() -> passwordEncoder.matches(rawPassword, user.getPasswordHash()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(matches -> {
                    if (!matches) {
                        log.warn("Account deletion denied — reauthentication failed for {}",
                                PiiMasker.maskEmail(user.getEmail()));
                        return Mono.error(new BadCredentialsException("error.invalid_credentials"));
                    }
                    return Mono.just(user);
                });
    }

    /**
     * ERASE replaces the password with the BCrypt of random bytes nobody ever
     * saw, so the row can never authenticate again. Computed before the
     * transaction opens: BCrypt takes ~100ms and must not hold the tx.
     */
    private Mono<String> prepareScrambledHash(Mode mode) {
        if (mode != Mode.ERASE) {
            return Mono.just("");
        }
        return Mono.fromCallable(() -> {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            return passwordEncoder.encode(Base64.getEncoder().encodeToString(bytes));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * The cascade both modes share. Serialized with then(): statements on a
     * single R2DBC transaction share one connection and must not run
     * concurrently (never Mono.when/zip here).
     */
    private Mono<Void> commonCascade(Long userId, Mode mode, boolean cancelNewsletter, LocalDateTime now) {
        Mono<Void> steps = passwordResetTokenRepository.deleteByUserId(userId)
                .then(emailChangeTokenRepository.deleteByUserId(userId))
                .then(emailVerificationTokenRepository.deleteByUserId(userId))
                .then(mfaConfigRepository.deleteByUserId(userId))
                .then(mfaBackupCodeRepository.deleteByUserId(userId))
                .then(socialAccountRepository.deleteByUserId(userId))
                .then(bookmarkRepository.deleteByUserId(userId))
                .then(readingHistoryRepository.deleteByUserId(userId))
                .then(searchQueryRepository.detachUser(userId))
                .then();
        if (cancelNewsletter) {
            // the holder's explicit choice on the deletion screen
            steps = steps.then(subscriberRepository.unsubscribeByUserId(userId, now)).then();
        }
        if (mode == Mode.ERASE) {
            // erasure always drops the link (before the final step; the unsubscribe
            // above is also user_id-keyed, so ordering matters). In DEACTIVATE the
            // link stays: the account still exists, just off the air.
            steps = steps.then(subscriberRepository.unlink(userId, UNLINKED_BY_ACCOUNT_DELETED, now)).then();
        }
        return steps;
    }

    private Mono<Void> deactivate(Long userId, LocalDateTime now) {
        return userRepository.deactivateAccount(userId, now)
                .flatMap(rows -> rows > 0
                        ? Mono.empty()
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "error.account_already_deleted")))
                .then();
    }

    private Mono<Void> erase(Long userId, String email, String scrambledHash, LocalDateTime now) {
        // "erased-<id>@" keeps users.email unique without carrying the old address
        String anonEmail = "erased-" + userId + "@anonymized.invalid";
        return userRepository.eraseAccount(userId, anonEmail, ANONYMIZED_NAME, scrambledHash, now)
                .flatMap(rows -> rows > 0
                        // by user_id AND by email: comments the backfill missed still
                        // carry the address and must not survive the erasure
                        ? commentRepository.anonymizeByOwner(userId, email, ANONYMIZED_NAME)
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "error.account_already_deleted")))
                .then();
    }

    private Mono<Void> revokeSessions(Long userId, Mode mode) {
        // DEACTIVATE keeps the (revoked) rows as session history — it is reversible;
        // ERASE deletes them: token strings are artifacts of an erased identity.
        return mode == Mode.DEACTIVATE
                ? refreshTokenService.revokeAllUserTokens(userId)
                : refreshTokenRepository.deleteByUserId(userId).then();
    }

    private Mono<Void> audit(Long userId, Mode mode) {
        AuditEventType type = mode == Mode.DEACTIVATE
                ? AuditEventType.ACCOUNT_DEACTIVATED : AuditEventType.ACCOUNT_ERASED;
        log.info("Account {} completed for userId={}", type.action(), userId);
        // userId only — the audit row must not re-introduce the PII just removed
        return auditService.logAction(type.action(), "USER", userId.toString(), userId, null,
                mode == Mode.DEACTIVATE
                        ? "Account deactivated by the holder"
                        : "Account erased by the holder (LGPD art. 18, VI)");
    }
}
