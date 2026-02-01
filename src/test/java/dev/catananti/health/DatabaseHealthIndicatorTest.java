package dev.catananti.health;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseHealthIndicator")
class DatabaseHealthIndicatorTest {

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private ConnectionFactory connectionFactory;

    @InjectMocks
    private DatabaseHealthIndicator indicator;

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("should report UP when database query succeeds")
    void shouldReportUpWhenDatabaseQuerySucceeds() {
        GenericExecuteSpec executeSpec = mock(GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);
        ConnectionFactoryMetadata metadata = mock(ConnectionFactoryMetadata.class);

        when(databaseClient.sql("SELECT 1")).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.one()).thenReturn(Mono.just(Map.of("?column?", 1)));
        when(connectionFactory.getMetadata()).thenReturn(metadata);
        when(metadata.getName()).thenReturn("PostgreSQL");

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("database", "PostgreSQL");
                    assertThat(health.getDetails()).containsEntry("validationQuery", "SELECT 1");
                    assertThat(health.getDetails()).containsEntry("connectionFactory", "PostgreSQL");
                })
                .verifyComplete();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("should report UP with fallback when query returns empty")
    void shouldReportUpWhenQueryReturnsEmpty() {
        GenericExecuteSpec executeSpec = mock(GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);

        when(databaseClient.sql("SELECT 1")).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.one()).thenReturn(Mono.empty());

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("database", "PostgreSQL");
                    assertThat(health.getDetails()).containsEntry("status", "Connected");
                })
                .verifyComplete();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("should report DOWN when database connection fails")
    void shouldReportDownWhenDatabaseConnectionFails() {
        GenericExecuteSpec executeSpec = mock(GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);

        when(databaseClient.sql("SELECT 1")).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.one()).thenReturn(Mono.error(new RuntimeException("Connection refused")));

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsEntry("database", "PostgreSQL");
                    assertThat(health.getDetails()).containsEntry("error", "RuntimeException");
                    assertThat(health.getDetails()).containsEntry("message", "Connection refused");
                })
                .verifyComplete();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("should report DOWN on timeout")
    void shouldReportDownOnTimeout() {
        GenericExecuteSpec executeSpec = mock(GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);

        when(databaseClient.sql("SELECT 1")).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.one()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> indicator.health())
                .thenAwait(Duration.ofSeconds(6))
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsKey("error");
                })
                .verifyComplete();
    }
}
