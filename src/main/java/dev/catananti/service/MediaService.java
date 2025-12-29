package dev.catananti.service;

import dev.catananti.entity.MediaAsset;
import dev.catananti.repository.MediaAssetRepository;
import dev.catananti.service.storage.StorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/**
 * Unified media upload service.
 * Validates files, stores them via the configured StorageProvider,
 * and tracks metadata in the media_assets table.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaService {

    private final StorageProvider storageProvider;
    private final MediaAssetRepository mediaAssetRepository;
    private final IdService idService;

    @Value("${app.upload.max-size:10485760}")
    private long maxFileSize; // 10MB default

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp"
    );

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    /**
     * Upload a file and track it as a media asset.
     *
     * @param filePart   the uploaded file
     * @param purpose    the intended use (AVATAR, BLOG_COVER, BLOG_CONTENT, COMMENT, PROJECT, TESTIMONIAL, GENERAL)
     * @param altText    optional alt text for accessibility
     * @param uploaderId the ID of the user uploading the file
     * @return the created MediaAsset with URL populated
     */
    public Mono<MediaAsset> upload(FilePart filePart, String purpose, String altText, Long uploaderId) {
        return validateFile(filePart)
                .flatMap(validated -> readFileBytes(validated)
                        .flatMap(data -> {
                            // Validate size
                            if (data.length > maxFileSize) {
                                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "File size exceeds maximum allowed: " + maxFileSize + " bytes"));
                            }

                            // Validate magic bytes
                            String ext = getExtension(filePart.filename()).toLowerCase();
                            if (!isValidMagicBytes(data, ext)) {
                                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "File content does not match declared type"));
                            }

                            // Generate storage key
                            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
                            String storedFilename = UUID.randomUUID() + "." + ext;
                            String storageKey = datePath + "/" + storedFilename;
                            String contentType = filePart.headers().getContentType() != null
                                    ? filePart.headers().getContentType().toString()
                                    : "application/octet-stream";

                            // Store file
                            return storageProvider.store(storageKey, data, contentType)
                                    .flatMap(url -> {
                                        // Create and persist media asset
                                        MediaAsset asset = MediaAsset.builder()
                                                .id(idService.nextId())
                                                .originalFilename(filePart.filename())
                                                .storedFilename(storedFilename)
                                                .storageKey(storageKey)
                                                .contentType(contentType)
                                                .fileSize((long) data.length)
                                                .purpose(purpose != null ? purpose.toUpperCase() : "GENERAL")
                                                .altText(altText)
                                                .url(url)
                                                .uploaderId(uploaderId)
                                                .createdAt(LocalDateTime.now())
                                                .newRecord(true)
                                                .build();

                                        return mediaAssetRepository.save(asset);
                                    });
                        }));
    }

    /**
     * Delete a media asset by its ID.
     */
    public Mono<Void> delete(Long id) {
        return mediaAssetRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found")))
                .flatMap(asset ->
                        storageProvider.delete(asset.getStorageKey())
                                .then(mediaAssetRepository.delete(asset))
                                .doOnSuccess(_ -> log.info("Media asset deleted: id={}, key={}", id, asset.getStorageKey()))
                );
    }

    /**
     * Delete a media asset by its URL (backward compatibility with old image endpoints).
     */
    public Mono<Void> deleteByUrl(String url) {
        return mediaAssetRepository.findByUrl(url)
                .flatMap(asset -> delete(asset.getId()))
                // Fallback: if not tracked in DB, try to delete from storage directly
                .switchIfEmpty(deleteUntracked(url));
    }

    /**
     * Get a single media asset by ID.
     */
    public Mono<MediaAsset> findById(Long id) {
        return mediaAssetRepository.findById(id)
                .map(this::markExisting);
    }

    /**
     * List all media assets with pagination.
     */
    public Flux<MediaAsset> findAll(int page, int size) {
        long offset = (long) page * size;
        return mediaAssetRepository.findAllPaginated(size, offset)
                .map(this::markExisting);
    }

    /**
     * List media assets by purpose with pagination.
     */
    public Flux<MediaAsset> findByPurpose(String purpose, int page, int size) {
        long offset = (long) page * size;
        return mediaAssetRepository.findByPurposePaginated(purpose.toUpperCase(), size, offset)
                .map(this::markExisting);
    }

    /**
     * List media assets by uploader with pagination.
     */
    public Flux<MediaAsset> findByUploader(Long uploaderId, int page, int size) {
        long offset = (long) page * size;
        return mediaAssetRepository.findByUploaderIdPaginated(uploaderId, size, offset)
                .map(this::markExisting);
    }

    /**
     * Count total media assets.
     */
    public Mono<Long> countAll() {
        return mediaAssetRepository.countAll();
    }

    /**
     * Count media assets by purpose.
     */
    public Mono<Long> countByPurpose(String purpose) {
        return mediaAssetRepository.countByPurpose(purpose.toUpperCase());
    }

    /**
     * Upload and return just the URL (backward compat with existing ImageUploadService contract).
     */
    public Mono<String> uploadAndReturnUrl(FilePart filePart, Long uploaderId) {
        return upload(filePart, "GENERAL", null, uploaderId)
                .map(MediaAsset::getUrl);
    }

    // ============================
    // Private helpers
    // ============================

    private Mono<FilePart> validateFile(FilePart filePart) {
        String filename = filePart.filename();

        // Validate filename for path traversal
        if (filename == null || filename.isBlank() ||
                filename.contains("..") || filename.contains("/") ||
                filename.contains("\\") || filename.contains("\0")) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename"));
        }

        String extension = getExtension(filename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid file extension. Allowed: " + ALLOWED_EXTENSIONS));
        }

        String contentType = filePart.headers().getContentType() != null
                ? filePart.headers().getContentType().toString()
                : "";
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid content type. Allowed: " + ALLOWED_CONTENT_TYPES));
        }

        return Mono.just(filePart);
    }

    private Mono<byte[]> readFileBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }

    private boolean isValidMagicBytes(byte[] bytes, String extension) {
        if (bytes.length < 12) return false;

        return switch (extension) {
            case "jpg", "jpeg" ->
                    bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF;
            case "png" ->
                    bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                            && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
            case "gif" ->
                    bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x38;
            case "webp" ->
                    bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46
                            && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50;
            default -> false;
        };
    }

    /**
     * Attempt to delete an untracked file (uploaded before media_assets tracking was added).
     * Extracts storage key from URL pattern: .../images/{yyyy}/{mm}/{filename}
     */
    private Mono<Void> deleteUntracked(String url) {
        if (url == null || !url.contains("/images/")) {
            return Mono.empty();
        }
        String relativePath = url.substring(url.indexOf("/images/") + 8);
        if (relativePath.contains("..") || relativePath.contains("\0")) {
            log.warn("Path traversal attempt in deleteUntracked: {}", relativePath);
            return Mono.empty();
        }
        return storageProvider.delete(relativePath)
                .doOnSuccess(_ -> log.info("Untracked file deleted: {}", relativePath));
    }

    private MediaAsset markExisting(MediaAsset asset) {
        asset.setNewRecord(false);
        return asset;
    }
}
