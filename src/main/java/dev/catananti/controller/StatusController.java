package dev.catananti.controller;

import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/status")
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class StatusController {

    private final R2dbcEntityTemplate r2dbcTemplate;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ArticleRepository articleRepository;
    private final CommentRepository commentRepository;
    private final SubscriberRepository subscriberRepository;

    // TODO F-107: Limit component health details exposure — consider hiding internal metrics from unauthenticated users
    @GetMapping
    public Mono<Map<String, Object>> getStatus() {
        return Mono.zip(
                checkDatabase(),
                checkRedis(),
                getBlogStats()
        ).map(tuple -> Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().toString(),
                "database", tuple.getT1(),
                "redis", tuple.getT2(),
                "blog", tuple.getT3()
        )).onErrorResume(ex -> {
            // F-109: Don't expose internal error details
            log.error("Status check failed: {}", ex.getMessage());
            return Mono.just(Map.of(
                    "status", "DEGRADED",
                    "timestamp", LocalDateTime.now().toString(),
                    "error", "Service health check failed"
            ));
        });
    }

    private Mono<Map<String, Object>> checkDatabase() {
        return r2dbcTemplate.getDatabaseClient()
                .sql("SELECT 1")
                .fetch()
                .first()
                .timeout(Duration.ofSeconds(5))
                .map(result -> Map.<String, Object>of(
                        "status", "UP",
                        "type", "PostgreSQL"
                ))
                // F-109: Don't expose internal error details
                .onErrorResume(ex -> {
                    log.error("Database health check failed: {}", ex.getMessage());
                    return Mono.just(Map.of(
                            "status", "DOWN",
                            "error", "Database connection failed"
                    ));
                });
    }

    // F-108: Ensure Redis connection is properly released after health check
    private Mono<Map<String, Object>> checkRedis() {
        return Mono.usingWhen(
                Mono.fromSupplier(() -> redisTemplate.getConnectionFactory().getReactiveConnection()),
                connection -> connection.ping()
                        .timeout(Duration.ofSeconds(5))
                        .map(result -> Map.<String, Object>of(
                                "status", "UP",
                                "ping", result
                        )),
                connection -> Mono.fromRunnable(connection::close)
        )
        // F-109: Don't expose internal error details
        .onErrorResume(ex -> {
            log.error("Redis health check failed: {}", ex.getMessage());
            return Mono.just(Map.of(
                    "status", "DOWN",
                    "error", "Redis connection failed"
            ));
        });
    }

    private Mono<Map<String, Object>> getBlogStats() {
        return Mono.zip(
                articleRepository.countAll().defaultIfEmpty(0L),
                articleRepository.countByStatus("PUBLISHED").defaultIfEmpty(0L),
                commentRepository.count().defaultIfEmpty(0L),
                subscriberRepository.countConfirmed().defaultIfEmpty(0L)
        ).map(tuple -> Map.<String, Object>of(
                "totalArticles", tuple.getT1(),
                "publishedArticles", tuple.getT2(),
                "totalComments", tuple.getT3(),
                "activeSubscribers", tuple.getT4()
        ));
    }

    @GetMapping("/health")
    public Mono<Map<String, String>> healthCheck() {
        return Mono.just(Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
