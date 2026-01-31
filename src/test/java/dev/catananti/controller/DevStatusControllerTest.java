package dev.catananti.controller;

import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DevStatusControllerTest {

    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private ArticleRepository articleRepository;
    @Mock private CommentRepository commentRepository;

    @InjectMocks
    private DevStatusController controller;

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

    private void mockBlogStats() {
        when(articleRepository.countAll()).thenReturn(Mono.just(20L));
        when(articleRepository.countByStatus("PUBLISHED")).thenReturn(Mono.just(15L));
        when(commentRepository.count()).thenReturn(Mono.just(40L));
    }

    @Nested
    @DisplayName("GET /api/v1/status")
    class GetStatus {

        @Test
        @DisplayName("Should return UP status with dev profile info")
        void shouldReturnUpStatusWithDevProfile() {
            mockDatabaseUp();
            mockBlogStats();

            StepVerifier.create(controller.getStatus())
                    .assertNext(status -> {
                        assertThat(status.get("status")).isEqualTo("UP");
                        assertThat(status.get("profile")).isEqualTo("dev");
                        assertThat(status.get("timestamp")).isNotNull();

                        @SuppressWarnings("unchecked")
                        Map<String, Object> database = (Map<String, Object>) status.get("database");
                        assertThat(database.get("status")).isEqualTo("UP");
                        assertThat(database.get("type")).isEqualTo("H2");

                        @SuppressWarnings("unchecked")
                        Map<String, Object> redis = (Map<String, Object>) status.get("redis");
                        assertThat(redis.get("status")).isEqualTo("DISABLED");

                        @SuppressWarnings("unchecked")
                        Map<String, Object> blog = (Map<String, Object>) status.get("blog");
                        assertThat(blog.get("totalArticles")).isEqualTo(20L);
                        assertThat(blog.get("publishedArticles")).isEqualTo(15L);
                        assertThat(blog.get("totalComments")).isEqualTo(40L);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should show database DOWN when query fails")
        @SuppressWarnings("unchecked")
        void shouldShowDatabaseDownWhenQueryFails() {
            DatabaseClient dbClient = mock(DatabaseClient.class);
            DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
            FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);

            when(r2dbcTemplate.getDatabaseClient()).thenReturn(dbClient);
            when(dbClient.sql("SELECT 1")).thenReturn(executeSpec);
            when(executeSpec.fetch()).thenReturn(fetchSpec);
            when(fetchSpec.first()).thenReturn(Mono.error(new RuntimeException("Connection refused")));

            mockBlogStats();

            StepVerifier.create(controller.getStatus())
                    .assertNext(status -> {
                        assertThat(status.get("status")).isEqualTo("UP");
                        Map<String, Object> database = (Map<String, Object>) status.get("database");
                        assertThat(database.get("status")).isEqualTo("DOWN");
                        assertThat(database.get("error")).isEqualTo("Connection refused");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/status/health")
    class HealthCheck {

        @Test
        @DisplayName("Should return simple health check with UP status")
        void shouldReturnSimpleHealthCheck() {
            StepVerifier.create(controller.healthCheck())
                    .assertNext(health -> {
                        assertThat(health.get("status")).isEqualTo("UP");
                        assertThat(health.get("timestamp")).isNotNull();
                    })
                    .verifyComplete();
        }
    }
}
