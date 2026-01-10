package dev.catananti.health;

import io.r2dbc.spi.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reactive health indicator for R2DBC PostgreSQL database.
 * Performs a simple query to verify database connectivity.
 */
@Component("db")
@RequiredArgsConstructor
@Slf4j
public class DatabaseHealthIndicator implements ReactiveHealthIndicator {

    private final DatabaseClient databaseClient;
    private final ConnectionFactory connectionFactory;

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Override
    public Mono<Health> health() {
        return checkDatabaseConnection()
                .timeout(TIMEOUT)
                .onErrorResume(this::buildDownHealth);
    }

    private Mono<Health> checkDatabaseConnection() {
        return databaseClient.sql("SELECT 1")
                .fetch()
                .one()
                .map(result -> {
                    String database = getConnectionFactoryName();
                    return Health.up()
                            .withDetail("database", "PostgreSQL")
                            .withDetail("connectionFactory", database)
                            .withDetail("validationQuery", "SELECT 1")
                            .build();
                })
                .switchIfEmpty(Mono.just(Health.up()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("status", "Connected")
                        .build()));
    }

    private String getConnectionFactoryName() {
        try {
            return connectionFactory.getMetadata().getName();
        } catch (Exception e) {
            return "R2DBC PostgreSQL";
        }
    }

    private Mono<Health> buildDownHealth(Throwable ex) {
        log.error("Database health check failed: {}", ex.getMessage());
        return Mono.just(Health.down()
                .withDetail("database", "PostgreSQL")
                .withDetail("error", ex.getClass().getSimpleName())
                .withDetail("message", ex.getMessage())
                .build());
    }
}
