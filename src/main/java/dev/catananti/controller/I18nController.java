package dev.catananti.controller;

import dev.catananti.repository.TranslationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/i18n")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "I18n", description = "Internationalization translations")
public class I18nController {

    private final TranslationRepository translationRepository;

    // In-memory cache: key = "locale:tier" -> value = cached translations map
    private final ConcurrentHashMap<String, CachedTranslations> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 3600_000; // 1 hour

    @GetMapping("/{locale}")
    @Operation(summary = "Get translations for a locale", description = "Returns translations filtered by caller's role. Anonymous gets public keys only.")
    public Mono<ResponseEntity<Map<String, String>>> getTranslations(
            @PathVariable @jakarta.validation.constraints.Pattern(regexp = "^[a-z]{2}(-[a-zA-Z]{2,4})?$") String locale,
            Mono<Authentication> authMono) {

        return authMono
            .map(auth -> resolveVisibilities(auth))
            .defaultIfEmpty(List.of("public"))
            .flatMap(visibilities -> {
                String tier = visibilities.get(visibilities.size() - 1); // highest tier
                String cacheKey = locale + ":" + tier;

                // Check cache
                CachedTranslations cached = cache.get(cacheKey);
                if (cached != null && !cached.isExpired()) {
                    return Mono.just(ResponseEntity.ok(cached.translations));
                }

                // Fetch from DB
                return translationRepository.findByLocaleAndVisibility(locale, visibilities)
                    .collectList()
                    .map(rows -> {
                        Map<String, String> translations = new LinkedHashMap<>();
                        for (Map<String, String> row : rows) {
                            translations.put(row.get("key"), row.get("value"));
                        }
                        // Cache it
                        cache.put(cacheKey, new CachedTranslations(translations));
                        return ResponseEntity.ok(translations);
                    });
            });
    }

    /**
     * Invalidate translation cache (called internally by AdminTranslationController).
     */
    public Mono<ResponseEntity<Map<String, String>>> invalidateCache() {
        cache.clear();
        log.info("Translation cache invalidated");
        return Mono.just(ResponseEntity.ok(Map.of("status", "cache invalidated")));
    }

    private List<String> resolveVisibilities(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return List.of("public");
        }

        String role = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5))
            .findFirst()
            .orElse("VIEWER");

        return switch (role) {
            case "ADMIN" -> List.of("public", "viewer", "dev", "admin");
            case "DEV" -> List.of("public", "viewer", "dev");
            case "VIEWER" -> List.of("public", "viewer");
            default -> List.of("public");
        };
    }

    private record CachedTranslations(Map<String, String> translations, long timestamp) {
        CachedTranslations(Map<String, String> translations) {
            this(translations, System.currentTimeMillis());
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
