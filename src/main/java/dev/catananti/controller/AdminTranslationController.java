package dev.catananti.controller;

import dev.catananti.repository.TranslationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/settings/translations")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin Translations", description = "Translation management (admin only)")
public class AdminTranslationController {

    private final TranslationRepository translationRepository;
    private final I18nController i18nController;

    @GetMapping
    @Operation(summary = "List translations", description = "Paginated list with optional search filter")
    public Mono<ResponseEntity<Map<String, Object>>> listTranslations(
            @RequestParam(defaultValue = "en") String locale,
            @RequestParam(defaultValue = "frontend") String namespace,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        int offset = page * size;
        return Mono.zip(
            translationRepository.findAllPaginated(locale, namespace, search, offset, size).collectList(),
            translationRepository.countAll(locale, namespace, search)
        ).map(tuple -> {
            var items = tuple.getT1();
            var total = tuple.getT2();
            return ResponseEntity.ok(Map.of(
                "items", items,
                "total", total,
                "page", page,
                "size", size,
                "totalPages", (int) Math.ceil((double) total / size)
            ));
        });
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update translation value")
    public Mono<ResponseEntity<Map<String, String>>> updateTranslation(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String value = body.get("value");
        if (value == null) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "value is required")));
        }
        return translationRepository.updateValue(id, value)
            .doOnSuccess(rows -> {
                if (rows > 0) {
                    i18nController.invalidateCache()
                            .subscribe(null, e -> log.warn("Cache invalidation failed after update: {}", e.getMessage()));
                    log.info("Translation {} updated", id);
                }
            })
            .map(rows -> rows > 0
                ? ResponseEntity.ok(Map.of("status", "updated"))
                : ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Create new translation")
    public Mono<ResponseEntity<Map<String, Object>>> createTranslation(@RequestBody Map<String, String> body) {
        String key = body.get("translationKey");
        String locale = body.get("locale");
        String value = body.get("value");
        String namespace = body.getOrDefault("namespace", "frontend");
        String visibility = body.getOrDefault("visibility", "public");

        if (key == null || locale == null || value == null) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", (Object) "translationKey, locale, and value are required")));
        }

        return translationRepository.insert(key, locale, value, namespace, visibility)
            .doOnSuccess(id -> {
                i18nController.invalidateCache()
                        .subscribe(null, e -> log.warn("Cache invalidation failed after create: {}", e.getMessage()));
                log.info("Translation created: {} (locale={}, id={})", key, locale, id);
            })
            .map(id -> ResponseEntity.ok(Map.of("id", (Object) id, "status", (Object) "created")));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete translation")
    public Mono<ResponseEntity<Map<String, String>>> deleteTranslation(@PathVariable Long id) {
        return translationRepository.deleteById(id)
            .doOnSuccess(rows -> {
                if (rows > 0) {
                    i18nController.invalidateCache()
                            .subscribe(null, e -> log.warn("Cache invalidation failed after delete: {}", e.getMessage()));
                    log.info("Translation {} deleted", id);
                }
            })
            .map(rows -> rows > 0
                ? ResponseEntity.ok(Map.of("status", "deleted"))
                : ResponseEntity.notFound().build());
    }

    @PostMapping("/cache/invalidate")
    @Operation(summary = "Force cache refresh")
    public Mono<ResponseEntity<Map<String, String>>> invalidateCache() {
        return i18nController.invalidateCache();
    }
}
