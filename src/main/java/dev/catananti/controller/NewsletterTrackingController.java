package dev.catananti.controller;

import dev.catananti.service.NewsletterTrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/newsletter/track")
@RequiredArgsConstructor
@Slf4j
public class NewsletterTrackingController {

    private final NewsletterTrackingService trackingService;

    // 1x1 transparent GIF (43 bytes)
    private static final byte[] TRACKING_PIXEL = new byte[]{
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00,
            0x01, 0x00, (byte) 0x80, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            0x00, 0x00, 0x00, 0x21, (byte) 0xF9, 0x04, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44,
            0x01, 0x00, 0x3B
    };

    /**
     * Tracking pixel for email opens. Returns 1x1 transparent GIF.
     * Only records if subscriber has analytics_consent = true.
     */
    @GetMapping("/open/{token}")
    public Mono<ResponseEntity<byte[]>> trackOpen(
            @PathVariable String token,
            ServerHttpRequest request) {
        return trackingService.recordOpen(token, request)
                .then(Mono.just(ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_GIF)
                        .cacheControl(CacheControl.noCache())
                        .header(HttpHeaders.PRAGMA, "no-cache")
                        .body(TRACKING_PIXEL)))
                .onErrorReturn(ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_GIF)
                        .body(TRACKING_PIXEL));
    }

    /**
     * Click tracking redirect. Records click event and redirects to actual URL.
     * Only records if subscriber has analytics_consent = true.
     */
    @GetMapping("/click/{token}")
    public Mono<ResponseEntity<Void>> trackClick(
            @PathVariable String token,
            @RequestParam String url,
            ServerHttpRequest request) {
        // Validate URL to prevent open redirect attacks
        if (!isValidRedirectUrl(url)) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        return trackingService.recordClick(token, url, request)
                .then(Mono.just(ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(url))
                        .build()));
    }

    private boolean isValidRedirectUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return "http".equals(scheme) || "https".equals(scheme);
        } catch (Exception e) {
            return false;
        }
    }
}
