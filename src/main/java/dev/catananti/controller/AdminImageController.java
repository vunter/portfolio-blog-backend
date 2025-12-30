package dev.catananti.controller;

import dev.catananti.service.MediaService;
import io.swagger.v3.oas.annotations.Operation;
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

import java.util.Map;

/**
 * Legacy image upload endpoint — delegates to MediaService.
 * Kept for backward compatibility with existing frontend code.
 * New code should use /api/v1/admin/media/* endpoints instead.
 */
@RestController
@RequestMapping("/api/v1/admin/images")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV', 'EDITOR')")
@Tag(name = "Admin - Images (Legacy)", description = "Legacy image upload — use /api/v1/admin/media/* for new code")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminImageController {

    private final MediaService mediaService;
    private final dev.catananti.repository.UserRepository userRepository;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload image (legacy)", description = "Upload an image file — delegates to MediaService")
    public Mono<Map<String, String>> uploadImage(
            @RequestPart("file") FilePart file,
            @AuthenticationPrincipal UserDetails userDetails) {
        log.info("Legacy image upload: filename={}", file.filename());

        return resolveUploaderId(userDetails)
                .flatMap(uploaderId -> mediaService.upload(file, "GENERAL", null, uploaderId))
                .map(asset -> Map.of(
                        "url", asset.getUrl(),
                        "filename", file.filename(),
                        "message", "Image uploaded successfully"
                ));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete image (legacy)", description = "Delete an uploaded image by URL")
    public Mono<Void> deleteImage(@RequestParam String url) {
        log.info("Legacy image delete: url={}", url);
        return mediaService.deleteByUrl(url);
    }

    private Mono<Long> resolveUploaderId(UserDetails userDetails) {
        if (userDetails == null) return Mono.just(0L);
        return userRepository.findByEmail(userDetails.getUsername())
                .map(user -> user.getId())
                .defaultIfEmpty(0L);
    }
}
