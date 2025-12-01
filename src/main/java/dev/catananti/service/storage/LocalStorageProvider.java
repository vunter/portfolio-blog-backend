package dev.catananti.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Local filesystem storage provider.
 * Stores files under the configured upload directory (default: "uploads/").
 * Files are served by StaticResourceConfig at /images/{year}/{month}/{filename}.
 */
@Slf4j
public class LocalStorageProvider implements StorageProvider {

    private final String uploadPath;
    private final String siteUrl;

    public LocalStorageProvider(
            @Value("${app.upload.path:uploads}") String uploadPath,
            @Value("${app.site-url:https://catananti.dev}") String siteUrl) {
        this.uploadPath = uploadPath;
        this.siteUrl = siteUrl;
        log.info("LocalStorageProvider initialized: uploadPath={}", uploadPath);
    }

    @Override
    public Mono<String> store(String key, byte[] data, String contentType) {
        return Mono.fromCallable(() -> {
            Path filePath = Paths.get(uploadPath, key);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, data);
            log.info("File stored locally: {} ({} bytes)", filePath, data.length);
            return getUrl(key);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> delete(String key) {
        return Mono.fromCallable(() -> {
            Path filePath = Paths.get(uploadPath, key).normalize();
            Path uploadRoot = Paths.get(uploadPath).toAbsolutePath().normalize();

            // Prevent path traversal
            if (!filePath.toAbsolutePath().normalize().startsWith(uploadRoot)) {
                log.warn("Path traversal attempt blocked in LocalStorageProvider.delete: {}", key);
                return null;
            }

            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                log.info("File deleted: {}", filePath);
            } else {
                log.warn("File not found for deletion: {}", filePath);
            }
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public String getUrl(String key) {
        return siteUrl + "/images/" + key;
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return Mono.fromCallable(() -> {
            Path dir = Paths.get(uploadPath);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return Files.isWritable(dir);
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorReturn(false);
    }

    @Override
    public String getType() {
        return "LOCAL";
    }
}
