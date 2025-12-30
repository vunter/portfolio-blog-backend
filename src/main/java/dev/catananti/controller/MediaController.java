package dev.catananti.controller;

import dev.catananti.entity.MediaAsset;
import dev.catananti.service.MediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/media")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV', 'EDITOR')")
@Tag(name = "Admin - Media", description = "Media asset upload and management (reusable across avatars, blogs, comments)")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class MediaController {

    private final MediaService mediaService;
    private final dev.catananti.repository.UserRepository userRepository;

    /**
     * Upload a media file.
     * 
     * @param file    the file to upload (JPEG, PNG, GIF, WebP)
     * @param purpose the intended use: AVATAR, BLOG_COVER, BLOG_CONTENT, COMMENT, PROJECT, TESTIMONIAL, GENERAL
     * @param altText optional alt text for accessibility
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload media file", description = "Upload an image file and track it as a reusable media asset")
    public Mono<MediaAssetResponse> uploadMedia(
            @RequestPart("file") FilePart file,
            @RequestParam(value = "purpose", defaultValue = "GENERAL") String purpose,
            @RequestParam(value = "altText", required = false) String altText,
            @AuthenticationPrincipal UserDetails userDetails) {

        log.info("Media upload request: filename={}, purpose={}, user={}",
                file.filename(), purpose, userDetails != null ? userDetails.getUsername() : "unknown");

        return resolveUploaderId(userDetails)
                .flatMap(uploaderId -> mediaService.upload(file, purpose, altText, uploaderId))
                .map(MediaAssetResponse::from);
    }

    /**
     * List media assets with pagination.
     */
    @GetMapping
    @Operation(summary = "List media assets", description = "Browse uploaded media with pagination and optional purpose filter")
    public Mono<MediaListResponse> listMedia(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "purpose", required = false) String purpose) {

        int clampedSize = Math.min(Math.max(size, 1), 100);

        Mono<List<MediaAssetResponse>> assetsMono;
        Mono<Long> countMono;

        if (purpose != null && !purpose.isBlank()) {
            assetsMono = mediaService.findByPurpose(purpose, page, clampedSize)
                    .map(MediaAssetResponse::from)
                    .collectList();
            countMono = mediaService.countByPurpose(purpose);
        } else {
            assetsMono = mediaService.findAll(page, clampedSize)
                    .map(MediaAssetResponse::from)
                    .collectList();
            countMono = mediaService.countAll();
        }

        return Mono.zip(assetsMono, countMono)
                .map(tuple -> new MediaListResponse(
                        tuple.getT1(),
                        tuple.getT2(),
                        page,
                        clampedSize,
                        (int) Math.ceil((double) tuple.getT2() / clampedSize)
                ));
    }

    /**
     * Get a single media asset by ID.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get media asset", description = "Retrieve a media asset by its ID")
    public Mono<MediaAssetResponse> getMedia(@PathVariable Long id) {
        return mediaService.findById(id)
                .map(MediaAssetResponse::from)
                .switchIfEmpty(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Media asset not found")));
    }

    /**
     * Delete a media asset by ID.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete media asset", description = "Delete a media asset by its ID")
    public Mono<Void> deleteMedia(@PathVariable Long id) {
        log.info("Deleting media asset: id={}", id);
        return mediaService.delete(id);
    }

    // ============================
    // Response DTOs
    // ============================

    public record MediaAssetResponse(
            Long id,
            String originalFilename,
            String contentType,
            Long fileSize,
            String purpose,
            String altText,
            String url,
            String createdAt
    ) {
        static MediaAssetResponse from(MediaAsset asset) {
            return new MediaAssetResponse(
                    asset.getId(),
                    asset.getOriginalFilename(),
                    asset.getContentType(),
                    asset.getFileSize(),
                    asset.getPurpose(),
                    asset.getAltText(),
                    asset.getUrl(),
                    asset.getCreatedAt() != null ? asset.getCreatedAt().toString() : null
            );
        }
    }

    public record MediaListResponse(
            List<MediaAssetResponse> items,
            Long totalItems,
            int page,
            int size,
            int totalPages
    ) {}

    // ============================
    // Helpers
    // ============================

    private Mono<Long> resolveUploaderId(UserDetails userDetails) {
        if (userDetails == null) return Mono.just(0L);
        return userRepository.findByEmail(userDetails.getUsername())
                .map(user -> user.getId())
                .defaultIfEmpty(0L);
    }
}
