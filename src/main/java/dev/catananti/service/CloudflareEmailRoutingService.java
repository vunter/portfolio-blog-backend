package dev.catananti.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Service to manage Cloudflare Email Routing rules.
 * <p>
 * When a user is promoted to DEV or ADMIN, a forwarding rule is created:
 *   {@code username@catananti.dev → user-personal-email}
 * <p>
 * When demoted below DEV, the rule is deleted.
 * <p>
 * Cloudflare API docs: https://developers.cloudflare.com/api/resources/email_routing/subresources/rules/
 */
@Service
@Slf4j
public class CloudflareEmailRoutingService {

    private static final String CF_API_BASE = "https://api.cloudflare.com/client/v4";

    private final WebClient webClient;
    private final String zoneId;
    private final String domain;
    private final boolean enabled;

    public CloudflareEmailRoutingService(
            WebClient.Builder webClientBuilder,
            @Value("${cloudflare.api-token:}") String apiToken,
            @Value("${cloudflare.zone-id:}") String zoneId,
            @Value("${cloudflare.email-routing.domain:catananti.dev}") String domain,
            @Value("${cloudflare.email-routing.enabled:false}") boolean enabled) {
        this.zoneId = zoneId;
        this.domain = domain;
        this.enabled = enabled;
        this.webClient = webClientBuilder
                .baseUrl(CF_API_BASE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        if (enabled) {
            log.info("Cloudflare Email Routing enabled for domain={}, zoneId={}", domain, maskId(zoneId));
        } else {
            log.info("Cloudflare Email Routing is DISABLED");
        }
    }

    /**
     * Create a forwarding rule: {@code username@domain} → {@code destinationEmail}.
     * Returns the Cloudflare rule ID for storage.
     */
    public Mono<String> createForwardingRule(String username, String destinationEmail) {
        if (!enabled) {
            log.debug("CF Email Routing disabled — skipping rule creation for {}", username);
            return Mono.empty();
        }

        String forwardAddress = username + "@" + domain;
        log.info("Creating CF email routing rule: {} → {}", forwardAddress, destinationEmail);

        Map<String, Object> body = Map.of(
                "actions", List.of(Map.of(
                        "type", "forward",
                        "value", List.of(destinationEmail)
                )),
                "matchers", List.of(Map.of(
                        "type", "literal",
                        "field", "to",
                        "value", forwardAddress
                )),
                "enabled", true,
                "name", "Auto: " + forwardAddress + " → " + destinationEmail
        );

        return webClient.post()
                .uri("/zones/{zoneId}/email/routing/rules", zoneId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(CfRuleResponse.class)
                .flatMap(resp -> {
                    if (resp.success() && resp.result() != null) {
                        String ruleId = resp.result().id();
                        log.info("CF email routing rule created: id={}, {} → {}", ruleId, forwardAddress, destinationEmail);
                        return Mono.just(ruleId);
                    }
                    String errors = resp.errors() != null ? resp.errors().toString() : "unknown";
                    log.error("CF email routing rule creation failed: {}", errors);
                    return Mono.error(new RuntimeException("Cloudflare Email Routing rule creation failed: " + errors));
                })
                .onErrorResume(ex -> {
                    if (ex instanceof RuntimeException && ex.getMessage().startsWith("Cloudflare")) {
                        return Mono.error(ex);
                    }
                    log.error("CF email routing API error for {}: {}", forwardAddress, ex.getMessage());
                    return Mono.empty(); // Non-blocking — don't fail promotion if CF API is down
                });
    }

    /**
     * Delete a forwarding rule by its Cloudflare rule ID.
     */
    public Mono<Void> deleteForwardingRule(String ruleId) {
        if (!enabled || ruleId == null || ruleId.isBlank()) {
            return Mono.empty();
        }

        log.info("Deleting CF email routing rule: {}", ruleId);

        return webClient.delete()
                .uri("/zones/{zoneId}/email/routing/rules/{ruleId}", zoneId, ruleId)
                .retrieve()
                .bodyToMono(CfRuleResponse.class)
                .doOnSuccess(resp -> {
                    if (resp != null && resp.success()) {
                        log.info("CF email routing rule deleted: {}", ruleId);
                    } else {
                        String errors = resp != null && resp.errors() != null ? resp.errors().toString() : "unknown";
                        log.warn("CF email routing rule deletion response: {}", errors);
                    }
                })
                .onErrorResume(ex -> {
                    log.error("CF email routing API error deleting rule {}: {}", ruleId, ex.getMessage());
                    return Mono.empty(); // Non-blocking — don't fail demotion if CF API is down
                })
                .then();
    }

    private String maskId(String id) {
        if (id == null || id.length() < 8) return "***";
        return id.substring(0, 4) + "..." + id.substring(id.length() - 4);
    }

    // ---------- Cloudflare API response DTOs ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CfRuleResponse(
            boolean success,
            CfRuleResult result,
            List<CfError> errors,
            List<Object> messages
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CfRuleResult(
            String id,
            String name,
            boolean enabled,
            @JsonProperty("tag") String tag
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CfError(
            int code,
            String message
    ) {}
}
