package dev.catananti.controller;

import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Simplified status controller for dev profile (no Redis dependency).
 */
@RestController
@RequestMapping("/api/v1/status")
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "simple")
@RequiredArgsConstructor
@Slf4j
public class DevStatusController {

    private final R2dbcEntityTemplate r2dbcTemplate;
    private final ArticleRepository articleRepository;
    private final CommentRepository commentRepository;

    @GetMapping
    public Mono<Map<String, Object>> getStatus() {
        log.debug("Fetching dev status");
        return Mono.zip(
                checkDatabase(),
                getBlogStats()
        ).map(tuple -> Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().toString(),
                "profile", "dev",
                "database", tuple.getT1(),
                "redis", Map.of("status", "DISABLED"),
                "blog", tuple.getT2()
        ));
    }

    private Mono<Map<String, Object>> checkDatabase() {
        return r2dbcTemplate.getDatabaseClient()
                .sql("SELECT 1")
                .fetch()
                .first()
                .map(result -> Map.<String, Object>of("status", "UP", "type", "H2"))
                .onErrorResume(ex -> Mono.just(Map.of("status", "DOWN", "error", ex.getMessage())));
    }

    private Mono<Map<String, Object>> getBlogStats() {
        return Mono.zip(
                articleRepository.countAll().defaultIfEmpty(0L),
                articleRepository.countByStatus("PUBLISHED").defaultIfEmpty(0L),
                commentRepository.count().defaultIfEmpty(0L)
        ).map(tuple -> Map.<String, Object>of(
                "totalArticles", tuple.getT1(),
                "publishedArticles", tuple.getT2(),
                "totalComments", tuple.getT3()
        ));
    }

    @GetMapping("/health")
    public Mono<Map<String, String>> healthCheck() {
        log.debug("Dev health check requested");
        return Mono.just(Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
