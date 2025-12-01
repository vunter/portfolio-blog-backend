package dev.catananti.service.storage;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3-compatible storage provider.
 * Works with AWS S3, MinIO (local dev), and Cloudflare R2 (production CDN).
 */
@Slf4j
public class S3StorageProvider implements StorageProvider {

    private final S3AsyncClient s3Client;
    private final String bucket;
    private final String publicUrl;

    /**
     * @param s3Client  the async S3 client (configured for MinIO, R2, or AWS S3)
     * @param bucket    the bucket name
     * @param publicUrl the public base URL for accessing objects (e.g., CDN URL or MinIO public endpoint)
     */
    public S3StorageProvider(S3AsyncClient s3Client, String bucket, String publicUrl) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        log.info("S3StorageProvider initialized: bucket={}, publicUrl={}", bucket, this.publicUrl);
    }

    @Override
    public Mono<String> store(String key, byte[] data, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength((long) data.length)
                .cacheControl("public, max-age=31536000") // 1-year cache for immutable uploads
                .build();

        return Mono.fromFuture(() -> s3Client.putObject(request, AsyncRequestBody.fromBytes(data)))
                .map(_ -> {
                    String url = getUrl(key);
                    log.info("File stored in S3: bucket={}, key={}, size={} bytes", bucket, key, data.length);
                    return url;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> delete(String key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        return Mono.fromFuture(() -> s3Client.deleteObject(request))
                .doOnSuccess(_ -> log.info("File deleted from S3: bucket={}, key={}", bucket, key))
                .then()
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getUrl(String key) {
        return publicUrl + "/" + key;
    }

    @Override
    public Mono<Boolean> isHealthy() {
        HeadBucketRequest request = HeadBucketRequest.builder()
                .bucket(bucket)
                .build();

        return Mono.fromFuture(() -> s3Client.headBucket(request))
                .map(_ -> true)
                .onErrorResume(e -> {
                    log.warn("S3 health check failed: {}", e.getMessage());
                    return Mono.just(false);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getType() {
        return "S3";
    }
}
