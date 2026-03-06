package dev.catananti.controller;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.service.LinkedInPortabilityService;
import dev.catananti.service.OAuth2Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/resume/import/linkedin")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class LinkedInImportController {

    private final LinkedInPortabilityService portabilityService;
    private final OAuth2Service oAuth2Service;

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        boolean enabled = portabilityService.isPortabilityEnabled();
        String note = enabled
                ? "LinkedIn DMA portability import is available."
                : "LinkedIn DMA portability import is not configured or disabled.";
        return Map.of("enabled", enabled, "note", note);
    }

    @GetMapping("/authorize")
    public Mono<ResponseEntity<Void>> authorize() {
        if (!portabilityService.isPortabilityEnabled()) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "LinkedIn portability import is not enabled."));
        }

        String state = UUID.randomUUID().toString();
        String authUrl = portabilityService.getPortabilityAuthUrl(state);

        return oAuth2Service.storeState(state)
                .thenReturn(ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(authUrl))
                        .<Void>build());
    }

    @GetMapping("/callback")
    public Mono<ResponseEntity<Void>> callback(@RequestParam String code,
                                                @RequestParam String state) {
        return oAuth2Service.validateAndConsumeState(state)
                .flatMap(valid -> {
                    if (!valid) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Invalid or expired state. Please try again."));
                    }

                    return portabilityService.exchangeCodeForToken(code)
                            .flatMap(tokenData -> {
                                String accessToken = (String) tokenData.get("access_token");
                                if (accessToken == null || accessToken.isBlank()) {
                                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                            "LinkedIn did not return an access token."));
                                }
                                return portabilityService.importProfile(accessToken);
                            })
                            .flatMap(profileRequest -> {
                                try {
                                    var mapper = new tools.jackson.databind.ObjectMapper();
                                    String json = mapper.writeValueAsString(profileRequest);
                                    return portabilityService.storeImportResult(json);
                                } catch (Exception e) {
                                    return Mono.error(new RuntimeException("Failed to serialize import result", e));
                                }
                            })
                            .map(key -> ResponseEntity.status(HttpStatus.FOUND)
                                    .location(URI.create("/admin/profile?linkedin-import=" + key))
                                    .<Void>build());
                })
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException rse) {
                        return Mono.error(rse);
                    }
                    log.error("LinkedIn import callback error: {}", e.getMessage(), e);
                    String encodedMsg = URLEncoder.encode(e.getMessage() != null ? e.getMessage() : "Unknown error",
                            StandardCharsets.UTF_8);
                    return Mono.just(ResponseEntity.status(HttpStatus.FOUND)
                            .location(URI.create("/admin/profile?linkedin-import-error=" + encodedMsg))
                            .<Void>build());
                });
    }

    @GetMapping("/result/{key}")
    public Mono<ResponseEntity<ResumeProfileRequest>> getResult(@PathVariable String key) {
        return portabilityService.retrieveImportResult(key)
                .flatMap(json -> {
                    try {
                        var mapper = new tools.jackson.databind.ObjectMapper();
                        ResumeProfileRequest result = mapper.readValue(json, ResumeProfileRequest.class);
                        return Mono.just(ResponseEntity.ok(result));
                    } catch (Exception e) {
                        log.error("Failed to deserialize import result for key={}: {}", key, e.getMessage());
                        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .<ResumeProfileRequest>build());
                    }
                })
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
