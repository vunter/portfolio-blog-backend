package dev.catananti.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulerLock")
class SchedulerLockTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @Nested
    @DisplayName("executeWithLock")
    class ExecuteWithLock {

        @Test
        @DisplayName("should execute task when lock is acquired")
        void shouldExecuteWhenLockAcquired() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq("scheduler:test-job:lock"), eq("locked"), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            SchedulerLock lock = new SchedulerLock(redisTemplate);
            AtomicBoolean taskRan = new AtomicBoolean(false);

            StepVerifier.create(lock.executeWithLock("test-job", Duration.ofMinutes(5),
                            Mono.fromRunnable(() -> taskRan.set(true)).then()))
                    .verifyComplete();

            assertThat(taskRan.get()).isTrue();
        }

        @Test
        @DisplayName("should skip task when lock is held by another instance")
        void shouldSkipWhenLockHeld() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(false));

            SchedulerLock lock = new SchedulerLock(redisTemplate);
            AtomicBoolean taskRan = new AtomicBoolean(false);

            StepVerifier.create(lock.executeWithLock("test-job", Duration.ofMinutes(5),
                            Mono.fromRunnable(() -> taskRan.set(true)).then()))
                    .verifyComplete();

            assertThat(taskRan.get()).isFalse();
        }

        @Test
        @DisplayName("should execute task without lock when Redis is null")
        void shouldExecuteWithoutRedis() {
            SchedulerLock lock = new SchedulerLock(null);
            AtomicBoolean taskRan = new AtomicBoolean(false);

            StepVerifier.create(lock.executeWithLock("test-job", Duration.ofMinutes(5),
                            Mono.fromRunnable(() -> taskRan.set(true)).then()))
                    .verifyComplete();

            assertThat(taskRan.get()).isTrue();
        }

        @Test
        @DisplayName("should fail CLOSED (skip the task) when Redis is unavailable")
        void shouldSkipWhenRedisUnavailable() {
            // When Redis errors we cannot guarantee mutual exclusion across replicas,
            // so the lock must skip the run rather than let every replica proceed and
            // produce duplicate side-effects (e.g. duplicate subscriber e-mails).
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.error(new RuntimeException("Connection refused")));

            SchedulerLock lock = new SchedulerLock(redisTemplate);
            AtomicBoolean taskRan = new AtomicBoolean(false);

            StepVerifier.create(lock.executeWithLock("test-job", Duration.ofMinutes(5),
                            Mono.fromRunnable(() -> taskRan.set(true)).then()))
                    .verifyComplete();

            assertThat(taskRan.get()).isFalse();
        }

        @Test
        @DisplayName("should use correct lock key format")
        void shouldUseCorrectLockKey() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq("scheduler:analytics-cleanup:lock"), eq("locked"), eq(Duration.ofMinutes(10))))
                    .thenReturn(Mono.just(true));

            SchedulerLock lock = new SchedulerLock(redisTemplate);

            StepVerifier.create(lock.executeWithLock("analytics-cleanup", Duration.ofMinutes(10), Mono.empty()))
                    .verifyComplete();

            verify(valueOps).setIfAbsent("scheduler:analytics-cleanup:lock", "locked", Duration.ofMinutes(10));
        }
    }
}
