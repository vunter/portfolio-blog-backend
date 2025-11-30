package dev.catananti.config;

import dev.catananti.service.storage.LocalStorageProvider;
import dev.catananti.service.storage.S3StorageProvider;
import dev.catananti.service.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@Slf4j
public class StorageConfig {

    /**
     * Local filesystem storage provider — default for development.
     * Active when app.storage.type=local (or not set).
     */
    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
    public StorageProvider localStorageProvider(
            @Value("${app.upload.path:uploads}") String uploadPath,
            @Value("${app.site-url:https://catananti.dev}") String siteUrl) {
        log.info("Configuring LOCAL storage provider (uploadPath={})", uploadPath);
        return new LocalStorageProvider(uploadPath, siteUrl);
    }

    /**
     * S3-compatible storage provider — for MinIO (local K3s) or Cloudflare R2 (production CDN).
     * Active when app.storage.type=s3.
     */
    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
    public StorageProvider s3StorageProvider(
            @Value("${app.storage.s3.endpoint}") String endpoint,
            @Value("${app.storage.s3.access-key}") String accessKey,
            @Value("${app.storage.s3.secret-key}") String secretKey,
            @Value("${app.storage.s3.bucket}") String bucket,
            @Value("${app.storage.s3.region:auto}") String region,
            @Value("${app.storage.s3.public-url}") String publicUrl) {
        log.info("Configuring S3 storage provider (endpoint={}, bucket={})", endpoint, bucket);

        S3AsyncClient s3Client = S3AsyncClient.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region))
                .forcePathStyle(true) // Required for MinIO and Cloudflare R2
                .build();

        return new S3StorageProvider(s3Client, bucket, publicUrl);
    }
}
