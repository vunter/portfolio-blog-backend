package dev.catananti.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Properties;

/**
 * Reactive health indicator for Redis.
 * Executes PING command to verify Redis connectivity.
 */
@Component("redisCustom")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class RedisHealthIndicator implements ReactiveHealthIndicator {

    private final ReactiveRedisConnectionFactory connectionFactory;

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Override
    public Mono<Health> health() {
        return checkRedisConnection()
                .timeout(TIMEOUT)
                .onErrorResume(this::buildDownHealth);
    }

    // F-048: Use Mono.usingWhen to ensure reactive connection is released after use
    private Mono<Health> checkRedisConnection() {
        return Mono.usingWhen(
                Mono.fromSupplier(connectionFactory::getReactiveConnection),
                connection -> connection.serverCommands()
                        .info()
                        .map(this::buildUpHealth)
                        .onErrorResume(e -> connection.ping()
                                .map(pong -> Health.up()
                                        .withDetail("ping", pong)
                                        .build())),
                ReactiveRedisConnection::closeLater
        );
    }

    private Health buildUpHealth(Properties info) {
        Health.Builder builder = Health.up();
        
        // Extract key Redis info
        String version = info.getProperty("redis_version");
        String mode = info.getProperty("redis_mode", "standalone");
        String connectedClients = info.getProperty("connected_clients");
        String usedMemory = info.getProperty("used_memory_human");
        String uptime = info.getProperty("uptime_in_seconds");

        if (version != null) {
            builder.withDetail("version", version);
        }
        builder.withDetail("mode", mode);
        if (connectedClients != null) {
            builder.withDetail("connectedClients", connectedClients);
        }
        if (usedMemory != null) {
            builder.withDetail("usedMemory", usedMemory);
        }
        if (uptime != null) {
            builder.withDetail("uptimeSeconds", uptime);
        }

        return builder.build();
    }

    private Mono<Health> buildDownHealth(Throwable ex) {
        log.error("Redis health check failed: {}", ex.getMessage());
        return Mono.just(Health.down()
                .withDetail("error", ex.getClass().getSimpleName())
                .withDetail("message", ex.getMessage())
                .build());
    }
}
