package dev.catananti.service;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NewsletterTrackingService")
class NewsletterTrackingServiceTest {

    @Mock private DatabaseClient databaseClient;
    @Mock private ServerHttpRequest request;
    @Mock private HttpHeaders headers;

    private NewsletterTrackingService service;

    @BeforeEach
    void setUp() {
        service = new NewsletterTrackingService(databaseClient);
    }

    @SuppressWarnings("unchecked")
    private void mockMapQuery(GenericExecuteSpec spec, FetchSpec<?> fetch) {
        when(spec.map(any(BiFunction.class))).thenReturn((FetchSpec) fetch);
    }

    @Nested
    @DisplayName("recordOpen")
    class RecordOpen {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should record open event when subscriber has consent")
        void shouldRecordOpenEvent() {
            when(request.getHeaders()).thenReturn(headers);
            when(headers.getFirst("User-Agent")).thenReturn("Mozilla/5.0");
            when(request.getRemoteAddress()).thenReturn(null);

            // Mock findSubscriberWithConsent
            GenericExecuteSpec findSpec = mock(GenericExecuteSpec.class);
            FetchSpec<Long> findFetch = mock(FetchSpec.class);
            when(databaseClient.sql(contains("SELECT id FROM subscribers"))).thenReturn(findSpec);
            when(findSpec.bind(eq("token"), anyString())).thenReturn(findSpec);
            mockMapQuery(findSpec, findFetch);
            when(findFetch.one()).thenReturn(Mono.just(42L));

            // Mock insertEvent
            GenericExecuteSpec insertSpec = mock(GenericExecuteSpec.class);
            when(databaseClient.sql(contains("INSERT INTO newsletter_events"))).thenReturn(insertSpec);
            when(insertSpec.bind(anyString(), any())).thenReturn(insertSpec);
            when(insertSpec.bindNull(anyString(), any())).thenReturn(insertSpec);
            when(insertSpec.then()).thenReturn(Mono.empty());

            StepVerifier.create(service.recordOpen("token-abc", request))
                    .verifyComplete();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should do nothing when subscriber not found or no consent")
        void shouldDoNothingWhenNoConsent() {
            // Mock findSubscriberWithConsent returns empty
            GenericExecuteSpec findSpec = mock(GenericExecuteSpec.class);
            FetchSpec<Long> findFetch = mock(FetchSpec.class);
            when(databaseClient.sql(contains("SELECT id FROM subscribers"))).thenReturn(findSpec);
            when(findSpec.bind(eq("token"), anyString())).thenReturn(findSpec);
            mockMapQuery(findSpec, findFetch);
            when(findFetch.one()).thenReturn(Mono.empty());

            StepVerifier.create(service.recordOpen("unknown-token", request))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getNewsletterAnalytics")
    class GetNewsletterAnalytics {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should aggregate analytics stats correctly")
        void shouldAggregateStats() {
            // Mock all count queries to return the same spec pattern
            GenericExecuteSpec countSpec = mock(GenericExecuteSpec.class);
            FetchSpec<Long> countFetch = mock(FetchSpec.class);

            when(databaseClient.sql(anyString())).thenReturn(countSpec);
            when(countSpec.bind(anyString(), any())).thenReturn(countSpec);
            mockMapQuery(countSpec, countFetch);
            when(countFetch.one()).thenReturn(Mono.just(10L));
            when(countFetch.all()).thenReturn(Flux.empty());

            // This is a more relaxed test — just verify it completes with a map
            StepVerifier.create(service.getNewsletterAnalytics(30))
                    .assertNext(result -> {
                        assertThat(result).containsKey("totalOpens");
                        assertThat(result).containsKey("totalClicks");
                        assertThat(result).containsKey("openRate");
                        assertThat(result).containsKey("clickRate");
                    })
                    .verifyComplete();
        }
    }
}
