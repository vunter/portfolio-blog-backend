package dev.catananti.scheduler;

import dev.catananti.repository.EmailVerificationTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailVerificationCleanupScheduler")
class EmailVerificationCleanupSchedulerTest {

    @Mock
    private EmailVerificationTokenRepository tokenRepository;

    @Mock
    private SchedulerLock schedulerLock;

    @InjectMocks
    private EmailVerificationCleanupScheduler scheduler;

    @Test
    @DisplayName("should delete expired tokens under the scheduler lock")
    void cleanupDeletesExpiredTokens() {
        when(schedulerLock.executeWithLock(anyString(), any(Duration.class), any()))
                .thenAnswer(i -> i.getArgument(2));
        when(tokenRepository.deleteExpired(any(LocalDateTime.class))).thenReturn(Mono.just(7L));

        StepVerifier.create(scheduler.cleanup()).verifyComplete();

        verify(tokenRepository).deleteExpired(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("should swallow repository errors so the schedule keeps running")
    void cleanupSurvivesRepositoryError() {
        when(schedulerLock.executeWithLock(anyString(), any(Duration.class), any()))
                .thenAnswer(i -> i.getArgument(2));
        when(tokenRepository.deleteExpired(any(LocalDateTime.class)))
                .thenReturn(Mono.error(new RuntimeException("DB error")));

        StepVerifier.create(scheduler.cleanup()).verifyComplete();
    }
}
