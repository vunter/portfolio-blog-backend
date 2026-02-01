package dev.catananti.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveServerCommands;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisHealthIndicator")
class RedisHealthIndicatorTest {

    @Mock
    private ReactiveRedisConnectionFactory connectionFactory;

    @Mock
    private ReactiveRedisConnection connection;

    @Mock
    private ReactiveServerCommands serverCommands;

    @InjectMocks
    private RedisHealthIndicator indicator;

    @Test
    @DisplayName("should report UP when Redis INFO succeeds")
    void shouldReportUpWhenRedisInfoSucceeds() {
        Properties info = new Properties();
        info.setProperty("redis_version", "7.2.0");
        info.setProperty("redis_mode", "standalone");
        info.setProperty("connected_clients", "5");
        info.setProperty("used_memory_human", "1.5M");
        info.setProperty("uptime_in_seconds", "3600");

        when(connectionFactory.getReactiveConnection()).thenReturn(connection);
        when(connection.serverCommands()).thenReturn(serverCommands);
        when(serverCommands.info()).thenReturn(Mono.just(info));
        // F-048: usingWhen requires closeLater for cleanup
        when(connection.closeLater()).thenReturn(Mono.empty());

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("version", "7.2.0");
                    assertThat(health.getDetails()).containsEntry("mode", "standalone");
                    assertThat(health.getDetails()).containsEntry("connectedClients", "5");
                    assertThat(health.getDetails()).containsEntry("usedMemory", "1.5M");
                    assertThat(health.getDetails()).containsEntry("uptimeSeconds", "3600");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("should report UP via PING fallback when INFO fails")
    void shouldReportUpViaPingWhenInfoFails() {
        // F-048: With usingWhen, same connection is reused for fallback
        when(connectionFactory.getReactiveConnection()).thenReturn(connection);
        when(connection.serverCommands()).thenReturn(serverCommands);
        when(serverCommands.info()).thenReturn(Mono.error(new RuntimeException("INFO not allowed")));
        when(connection.ping()).thenReturn(Mono.just("PONG"));
        when(connection.closeLater()).thenReturn(Mono.empty());

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("ping", "PONG");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("should report DOWN when Redis connection fails")
    void shouldReportDownWhenConnectionFails() {
        // F-048: With usingWhen, same connection is reused for fallback
        when(connectionFactory.getReactiveConnection()).thenReturn(connection);
        when(connection.serverCommands()).thenReturn(serverCommands);
        when(serverCommands.info()).thenReturn(Mono.error(new RuntimeException("Connection refused")));
        when(connection.ping()).thenReturn(Mono.error(new RuntimeException("Connection refused")));
        when(connection.closeLater()).thenReturn(Mono.empty());

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsEntry("error", "RuntimeException");
                    assertThat(health.getDetails()).containsEntry("message", "Connection refused");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("should report DOWN on timeout")
    void shouldReportDownOnTimeout() {
        when(connectionFactory.getReactiveConnection()).thenReturn(connection);
        when(connection.serverCommands()).thenReturn(serverCommands);
        // Return a Mono that never completes to trigger timeout
        when(serverCommands.info()).thenReturn(Mono.never());
        when(connection.closeLater()).thenReturn(Mono.empty());

        StepVerifier.withVirtualTime(() -> indicator.health())
                .thenAwait(Duration.ofSeconds(6))
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsKey("error");
                })
                .verifyComplete();
    }
}
