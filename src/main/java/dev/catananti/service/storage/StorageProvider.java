package dev.catananti.service.storage;

import reactor.core.publisher.Mono;

/**
 * Abstraction for file storage backends.
 * Implementations: LocalStorageProvider (filesystem), S3StorageProvider (S3-compatible: MinIO, Cloudflare R2, AWS S3).
 */
public interface StorageProvider {

    /**
     * Store a file.
     *
     * @param key  the storage key (e.g., "2026/01/uuid.jpg")
     * @param data the file bytes
     * @param contentType the MIME type
     * @return the public URL of the stored file
     */
    Mono<String> store(String key, byte[] data, String contentType);

    /**
     * Delete a file by its storage key.
     *
     * @param key the storage key
     * @return completes when deleted
     */
    Mono<Void> delete(String key);

    /**
     * Get the public URL for a given storage key.
     *
     * @param key the storage key
     * @return the public URL
     */
    String getUrl(String key);

    /**
     * Check if this provider is available / healthy.
     *
     * @return true if the provider is operational
     */
    Mono<Boolean> isHealthy();

    /**
     * @return the storage type identifier (e.g., "LOCAL", "S3")
     */
    String getType();
}
