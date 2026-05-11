package dev.catananti.service.storage;

import dev.catananti.config.ResilienceConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3StorageProvider")
class S3StorageProviderTest {

    @Mock
    private S3AsyncClient s3Client;

    @Mock
    private ResilienceConfig resilience;

    private S3StorageProvider provider;

    @BeforeEach
    void setUp() {
        when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(10));
        when(resilience.getStorageCircuitBreaker()).thenReturn(
                CircuitBreaker.of("test-storage", CircuitBreakerConfig.ofDefaults()));
        provider = new S3StorageProvider(s3Client, "test-bucket", "https://cdn.example.com", resilience);
    }

    @Nested
    @DisplayName("store")
    class Store {

        @Test
        @DisplayName("should upload to S3 and return CDN URL")
        void shouldUploadAndReturnUrl() {
            PutObjectResponse response = PutObjectResponse.builder().build();
            when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                    .thenReturn(CompletableFuture.completedFuture(response));

            StepVerifier.create(provider.store("2026/01/photo.jpg", "image data".getBytes(), "image/jpeg"))
                    .assertNext(url -> assertThat(url).isEqualTo("https://cdn.example.com/2026/01/photo.jpg"))
                    .verifyComplete();

            ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(captor.capture(), any(AsyncRequestBody.class));
            PutObjectRequest req = captor.getValue();
            assertThat(req.bucket()).isEqualTo("test-bucket");
            assertThat(req.key()).isEqualTo("2026/01/photo.jpg");
            assertThat(req.contentType()).isEqualTo("image/jpeg");
            assertThat(req.cacheControl()).isEqualTo("public, max-age=31536000");
        }

        @Test
        @DisplayName("should propagate S3 errors")
        void shouldPropagateErrors() {
            when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                    .thenReturn(CompletableFuture.failedFuture(
                            S3Exception.builder().message("Access Denied").build()));

            StepVerifier.create(provider.store("key", "data".getBytes(), "text/plain"))
                    .expectError(S3Exception.class)
                    .verify(Duration.ofSeconds(5));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("should delete object from S3")
        void shouldDeleteObject() {
            DeleteObjectResponse response = DeleteObjectResponse.builder().build();
            when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(response));

            StepVerifier.create(provider.delete("2026/01/photo.jpg"))
                    .verifyComplete();

            ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
            assertThat(captor.getValue().key()).isEqualTo("2026/01/photo.jpg");
        }
    }

    @Nested
    @DisplayName("getUrl")
    class GetUrl {

        @Test
        @DisplayName("should construct CDN URL")
        void shouldConstructUrl() {
            assertThat(provider.getUrl("2026/01/photo.jpg"))
                    .isEqualTo("https://cdn.example.com/2026/01/photo.jpg");
        }

        @Test
        @DisplayName("should strip trailing slash from publicUrl")
        void shouldStripTrailingSlash() {
            S3StorageProvider p = new S3StorageProvider(s3Client, "b", "https://cdn.example.com/", resilience);
            assertThat(p.getUrl("key")).isEqualTo("https://cdn.example.com/key");
        }
    }

    @Nested
    @DisplayName("isHealthy")
    class IsHealthy {

        @Test
        @DisplayName("should return true when bucket accessible")
        void shouldReturnTrueWhenHealthy() {
            HeadBucketResponse response = HeadBucketResponse.builder().build();
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(response));

            StepVerifier.create(provider.isHealthy())
                    .assertNext(healthy -> assertThat(healthy).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return false when bucket not accessible")
        void shouldReturnFalseOnError() {
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(
                            NoSuchBucketException.builder().message("Not found").build()));

            StepVerifier.create(provider.isHealthy())
                    .assertNext(healthy -> assertThat(healthy).isFalse())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getType")
    class GetType {

        @Test
        @DisplayName("should return S3")
        void shouldReturnS3() {
            assertThat(provider.getType()).isEqualTo("S3");
        }
    }
}
