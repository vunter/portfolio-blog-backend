package dev.catananti.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class ImageUploadService {

    @Value("${app.upload.path:uploads}")
    private String uploadPath;

    @Value("${app.upload.max-size:10485760}")
    private long maxFileSize; // 10MB default

    @Value("${app.site-url:https://catananti.dev}")
    private String siteUrl;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp"
            // SVG removed due to XSS risk - SVG files can contain JavaScript
    );

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
            // image/svg+xml removed due to XSS risk
    );

    public Mono<String> uploadImage(FilePart filePart) {
        return validateFile(filePart)
                .flatMap(this::saveFile)
                .map(this::buildImageUrl);
    }

    private Mono<FilePart> validateFile(FilePart filePart) {
        String filename = filePart.filename();
        
        // Validate filename for path traversal attacks
        if (filename == null || filename.isBlank() || 
            filename.contains("..") || filename.contains("/") || 
            filename.contains("\\") || filename.contains("\0")) {
            return Mono.error(new IllegalArgumentException("Invalid filename"));
        }
        
        String extension = getExtension(filename);

        if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            return Mono.error(new IllegalArgumentException(
                    "Invalid file extension. Allowed: " + ALLOWED_EXTENSIONS));
        }

        String contentType = filePart.headers().getContentType() != null 
                ? filePart.headers().getContentType().toString() 
                : "";

        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            return Mono.error(new IllegalArgumentException(
                    "Invalid content type. Allowed: " + ALLOWED_CONTENT_TYPES));
        }

        return Mono.just(filePart);
    }

    private Mono<String> saveFile(FilePart filePart) {
        String originalFilename = filePart.filename();
        String extension = getExtension(originalFilename);
        String newFilename = generateFilename(extension);
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        
        Path directory = Paths.get(uploadPath, datePath);
        Path filePath = directory.resolve(newFilename);

        return Mono.fromCallable(() -> {
                    Files.createDirectories(directory);
                    return filePath;
                })
                .flatMap(path -> {
                    // F-180: Reject obviously oversized uploads before writing to disk
                    long contentLength = filePart.headers().getContentLength();
                    if (contentLength > 0 && contentLength > maxFileSize) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "File size exceeds maximum allowed: " + maxFileSize + " bytes"));
                    }
                    // H7: Stream directly to disk instead of buffering entire file in memory
                    return filePart.transferTo(path)
                            .then(Mono.fromCallable(() -> {
                                // Validate file size from disk
                                long fileSize = Files.size(path);
                                if (fileSize > maxFileSize) {
                                    Files.deleteIfExists(path);
                                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                            "File size exceeds maximum allowed: " + maxFileSize + " bytes");
                                }

                                // Validate magic bytes by reading only first 12 bytes
                                byte[] header = new byte[12];
                                try (InputStream fis = Files.newInputStream(path)) {
                                    int bytesRead = fis.read(header);
                                    if (bytesRead < 12) {
                                        Files.deleteIfExists(path);
                                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                "File too small to validate");
                                    }
                                }

                                String ext = getExtension(filePart.filename()).toLowerCase();
                                if (!isValidMagicBytes(header, ext)) {
                                    Files.deleteIfExists(path);
                                    throw new IllegalArgumentException(
                                            "File content does not match declared type");
                                }

                                // Strip EXIF metadata from JPEG files to prevent GPS/device info leakage
                                stripExifMetadata(path, ext);

                                log.info("Image uploaded: {}", path);
                                return datePath + "/" + newFilename;
                            }).subscribeOn(Schedulers.boundedElastic()));
                });
    }

    private String buildImageUrl(String relativePath) {
        return siteUrl + "/images/" + relativePath;
    }

    private String generateFilename(String extension) {
        return UUID.randomUUID().toString() + "." + extension;
    }

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }

    public Mono<Void> deleteImage(String imageUrl) {
        if (imageUrl == null || !imageUrl.contains("/images/")) {
            return Mono.empty();
        }

        String relativePath = imageUrl.substring(imageUrl.indexOf("/images/") + 8);
        
        // Validate against path traversal attacks
        if (relativePath.contains("..") || relativePath.contains("\0")) {
            log.warn("Path traversal attempt in deleteImage: {}", relativePath);
            return Mono.empty();
        }
        
        Path filePath = Paths.get(uploadPath, relativePath).normalize();
        Path uploadRoot = Paths.get(uploadPath).toAbsolutePath().normalize();
        
        // Ensure resolved path is within the upload directory
        if (!filePath.toAbsolutePath().normalize().startsWith(uploadRoot)) {
            log.warn("Path traversal attempt blocked in deleteImage: {}", filePath);
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
                    boolean deleted = Files.deleteIfExists(filePath);
                    if (deleted) {
                        log.info("Image deleted: {}", filePath);
                    } else {
                        log.warn("Image file not found for deletion: {}", filePath);
                    }
                    return deleted;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(IOException.class, e -> {
                    log.error("Failed to delete image: {}", filePath, e);
                    return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "error.image_delete_failed"));
                })
                .then();
    }

    /**
     * Strip EXIF metadata from JPEG files by reading and rewriting the image via ImageIO.
     * ImageIO only preserves pixel data, effectively removing all EXIF metadata.
     */
    private void stripExifMetadata(Path filePath, String extension) {
        if (!"jpg".equals(extension) && !"jpeg".equals(extension)) {
            return;
        }
        try {
            BufferedImage image = ImageIO.read(filePath.toFile());
            if (image == null) {
                log.warn("Could not read JPEG image for EXIF stripping: {}", filePath);
                return;
            }
            try (OutputStream out = Files.newOutputStream(filePath)) {
                ImageIO.write(image, "jpg", out);
            }
            log.debug("EXIF metadata stripped from: {}", filePath);
        } catch (IOException e) {
            log.warn("Failed to strip EXIF metadata from {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * Validate file content against magic bytes to prevent content-type spoofing.
     */
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
}
