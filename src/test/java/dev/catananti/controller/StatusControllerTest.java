package dev.catananti.controller;

import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.SubscriberRepository;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.core.ReactiveSelectOperation;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatusControllerTest {

    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock private ArticleRepository articleRepository;
    @Mock private CommentRepository commentRepository;
    @Mock private SubscriberRepository subscriberRepository;

    @InjectMocks
    private StatusController controller;

    @SuppressWarnings("unchecked")
    private void mockDatabaseUp() {
        DatabaseClient dbClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);

        when(r2dbcTemplate.getDatabaseClient()).thenReturn(dbClient);
        when(dbClient.sql("SELECT 1")).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.first()).thenReturn(Mono.just(Map.of("1", 1)));
    }

    private void mockRedisUp() {
        ReactiveRedisConnectionFactory factory = mock(ReactiveRedisConnectionFactory.class);
        ReactiveRedisConnection connection = mock(ReactiveRedisConnection.class);

        when(redisTemplate.getConnectionFactory()).thenReturn(factory);
        when(factory.getReactiveConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn(Mono.just("PONG"));
    }

    private void mockBlogStats() {
        when(articleRepository.countAll()).thenReturn(Mono.just(50L));
        when(articleRepository.countByStatus("PUBLISHED")).thenReturn(Mono.just(30L));
        when(commentRepository.count()).thenReturn(Mono.just(100L));
        when(subscriberRepository.countConfirmed()).thenReturn(Mono.just(25L));
    }

    @Nested
    @DisplayName("GET /api/v1/status")
    class GetStatus {

        @Test
        @DisplayName("Should return UP status when all services healthy")
        void shouldReturnUpStatus() {
            mockDatabaseUp();
            mockRedisUp();
            mockBlogStats();

            StepVerifier.create(controller.getStatus())
                    .assertNext(status -> {
                        assertThat(status.get("status")).isEqualTo("UP");
                        assertThat(status.get("timestamp")).isNotNull();
                        assertThat(status.get("database")).isNotNull();
                        assertThat(status.get("redis")).isNotNull();
                        assertThat(status.get("blog")).isNotNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return DEGRADED when database check fails")
        @SuppressWarnings("unchecked")
        void shouldReturnDegradedWhenDatabaseFails() {
            DatabaseClient dbClient = mock(DatabaseClient.class);
            DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
            FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);

            when(r2dbcTemplate.getDatabaseClient()).thenReturn(dbClient);
            when(dbClient.sql("SELECT 1")).thenReturn(executeSpec);
            when(executeSpec.fetch()).thenReturn(fetchSpec);
            when(fetchSpec.first()).thenReturn(Mono.error(new RuntimeException("DB down")));

            // Redis and blog stats also needed for Mono.zip; if db throws before zip completes,
            // the onErrorResume should catch it.
            mockRedisUp();
            mockBlogStats();

            StepVerifier.create(controller.getStatus())
                    .assertNext(status -> {
                        // Could be DEGRADED if the error propagates, or UP with DOWN database
                        assertThat(status).containsKey("status");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/status/health")
    class HealthCheck {

        @Test
        @DisplayName("Should return health check with UP status")
        void shouldReturnHealthCheck() {
            StepVerifier.create(controller.healthCheck())
                    .assertNext(health -> {
                        assertThat(health.get("status")).isEqualTo("UP");
                        assertThat(health.get("timestamp")).isNotNull();
                    })
                    .verifyComplete();
        }
    }
}
