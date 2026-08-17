package dev.catananti.scheduler;

import dev.catananti.repository.EmailVerificationTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Purges email verification tokens that expired or were consumed long ago.
 * Mirrors {@code RefreshTokenService.cleanupExpiredTokens}: the reactive body
 * runs under the cross-replica {@link SchedulerLock}, and the {@code @Scheduled}
 * entry point only subscribes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationCleanupScheduler {

    private final EmailVerificationTokenRepository tokenRepository;
    private final SchedulerLock schedulerLock;

    public Mono<Void> cleanup() {
        return schedulerLock.executeWithLock("email-verification-cleanup", Duration.ofMinutes(5),
                tokenRepository.deleteExpired(LocalDateTime.now().minusDays(7))
                        .doOnSuccess(n -> log.info("Deleted {} expired verification tokens", n))
                        .onErrorResume(e -> {
                            log.error("Verification token cleanup failed", e);
                            return Mono.empty();
                        })
                        .then());
    }

    @Scheduled(fixedRateString = "${scheduling.email-verification-cleanup-ms:86400000}",
               initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void cleanupScheduled() {
        cleanup().subscribe();
    }
}
