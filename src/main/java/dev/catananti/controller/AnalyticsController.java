package dev.catananti.controller;

import dev.catananti.dto.AnalyticsEventRequest;
import dev.catananti.service.AnalyticsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Validated
@Slf4j
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @PostMapping("/event")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> trackEvent(
            @Valid @RequestBody AnalyticsEventRequest request,
            ServerHttpRequest httpRequest) {
        if (!hasAnalyticsConsent(httpRequest)) {
            log.debug("Analytics event rejected: no consent header");
            return Mono.empty();
        }
        log.debug("Tracking analytics event");
        return analyticsService.trackEvent(request, httpRequest);
    }

    @PostMapping("/view/{slug}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> trackView(
            @PathVariable @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String slug,
            ServerHttpRequest httpRequest) {
        if (!hasAnalyticsConsent(httpRequest)) {
            log.debug("Article view rejected: no consent header");
            return Mono.empty();
        }
        log.debug("Tracking article view for slug={}", slug);
        return analyticsService.trackArticleView(slug, httpRequest);
    }

    private boolean hasAnalyticsConsent(ServerHttpRequest request) {
        String consent = request.getHeaders().getFirst("X-Analytics-Consent");
        return "granted".equals(consent);
    }
}
