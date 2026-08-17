package dev.catananti.controller;

import dev.catananti.dto.AnalyticsChallengeResponse;
import dev.catananti.dto.AnalyticsEventRequest;
import dev.catananti.dto.AnalyticsTokenResponse;
import dev.catananti.service.AnalyticsProofOfWorkService;
import dev.catananti.service.AnalyticsService;
import dev.catananti.service.AnalyticsTokenService;
import dev.catananti.service.RecaptchaService;
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
    private final AnalyticsProofOfWorkService powService;
    private final AnalyticsTokenService tokenService;
    private final RecaptchaService recaptchaService;

    /**
     * SEC-AH-01: Issue a proof-of-work challenge for analytics event submission.
     */
    @GetMapping("/challenge")
    public Mono<AnalyticsChallengeResponse> getChallenge() {
        return powService.issueChallenge();
    }

    /**
     * SEC-AH-02: Issue a session token proving the client loaded the site.
     */
    @GetMapping("/token")
    public Mono<AnalyticsTokenResponse> getToken() {
        return tokenService.issueToken();
    }

    @PostMapping("/event")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> trackEvent(
            @Valid @RequestBody AnalyticsEventRequest request,
            ServerHttpRequest httpRequest) {
        if (!hasAnalyticsConsent(httpRequest)) {
            log.debug("Analytics event rejected: no consent header");
            return Mono.empty();
        }

        // SEC-AH-02: Validate session token (proof-of-visit)
        String analyticsToken = httpRequest.getHeaders().getFirst("X-Analytics-Token");

        // SEC-AH-01: Verify proof-of-work solution first (cheapest check, consumes challenge)
        return powService.verifySolution(request.getChallengeId(), request.getSolution())
                .onErrorResume(IllegalArgumentException.class, e -> {
                    log.warn("PoW verification failed: {}", e.getMessage());
                    return Mono.error(new org.springframework.web.server.ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "error.pow_invalid_solution"));
                })
                // SEC-AH-04: Verify reCAPTCHA v3 token if present (non-destructive)
                .then(verifyRecaptchaIfPresent(request.getRecaptchaToken()))
                // SEC-AH-02: Validate the session token last (non-consuming — it is
                // reusable until TTL; the PoW challenge above is the single-use part)
                .then(tokenService.validate(analyticsToken))
                .onErrorResume(e -> {
                    if (e instanceof org.springframework.web.server.ResponseStatusException) return Mono.error(e);
                    log.warn("Analytics token validation failed: {}", e.getMessage());
                    return Mono.error(new org.springframework.web.server.ResponseStatusException(
                            HttpStatus.FORBIDDEN, "error.analytics_token_invalid"));
                })
                // All security checks passed — process the event
                .then(analyticsService.trackEvent(request, httpRequest))
                .onErrorResume(IllegalArgumentException.class, e -> {
                    log.warn("Analytics event rejected: {}", e.getMessage());
                    return Mono.error(new org.springframework.web.server.ResponseStatusException(
                            HttpStatus.BAD_REQUEST, e.getMessage()));
                });
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
        // View tracking is a simplified path — only consent + rate limiting apply
        // (no PoW/token/reCAPTCHA required for article view tracking via slug)
        log.debug("Tracking article view for slug={}", slug);
        return analyticsService.trackArticleView(slug, httpRequest);
    }

    /**
     * Verify reCAPTCHA only when a token is provided. Allows graceful degradation
     * when the frontend can't load the reCAPTCHA script (CSP, ad blockers, etc.).
     * The other 3 security layers (token, PoW, rate limiting) still apply.
     */
    private Mono<Void> verifyRecaptchaIfPresent(String recaptchaToken) {
        if (recaptchaToken == null || recaptchaToken.isBlank()) {
            log.debug("No reCAPTCHA token provided for analytics event — skipping (graceful degradation)");
            return Mono.empty();
        }
        return recaptchaService.verify(recaptchaToken, "analytics_event")
                .onErrorResume(RecaptchaService.RecaptchaException.class, e -> {
                    log.warn("reCAPTCHA verification failed for analytics event: {}", e.getMessage());
                    return Mono.error(new org.springframework.web.server.ResponseStatusException(
                            HttpStatus.FORBIDDEN, "error.recaptcha_failed"));
                });
    }

    private boolean hasAnalyticsConsent(ServerHttpRequest request) {
        String consent = request.getHeaders().getFirst("X-Analytics-Consent");
        return "granted".equals(consent);
    }
}
