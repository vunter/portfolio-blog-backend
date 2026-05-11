package dev.catananti.controller;

import dev.catananti.config.PaginationConfig;
import dev.catananti.repository.TranslationRepository;
import dev.catananti.service.IdService;
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
    private final IdService idService;
    private final PaginationConfig paginationConfig;

    @GetMapping
    @Operation(summary = "List translations", description = "Paginated list with optional search filter")
    public Mono<ResponseEntity<Map<String, Object>>> listTranslations(
            @RequestParam(defaultValue = "en") String locale,
            @RequestParam(defaultValue = "frontend") String namespace,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        final int clampedSize = paginationConfig.clampPageSize(size);
        int offset = page * clampedSize;
        return Mono.zip(
            translationRepository.findAllPaginated(locale, namespace, search, offset, clampedSize).collectList(),
            translationRepository.countAll(locale, namespace, search)
        ).map(tuple -> {
            var items = tuple.getT1();
            var total = tuple.getT2();
            return ResponseEntity.ok(Map.of(
                "items", items,
                "total", total,
                "page", page,
                "size", clampedSize,
                "totalPages", (int) Math.ceil((double) total / clampedSize)
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
            throw new IllegalArgumentException("error.invalid_request_data");
        }
        return translationRepository.updateValue(id, value)
            .flatMap(rows -> {
                if (rows > 0) {
                    log.info("Translation {} updated", id);
                    return i18nController.invalidateCache()
                            .onErrorResume(e -> { log.warn("Cache invalidation failed after update: {}", e.getMessage()); return Mono.empty(); })
                            .thenReturn(ResponseEntity.ok(Map.<String, String>of("status", "updated")));
                }
                return Mono.just(ResponseEntity.<Map<String, String>>notFound().build());
            });
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
            throw new IllegalArgumentException("error.invalid_request_data");
        }

        return translationRepository.insert(idService.nextId(), key, locale, value, namespace, visibility)
            .flatMap(id -> {
                log.info("Translation created: {} (locale={}, id={})", key, locale, id);
                return i18nController.invalidateCache()
                        .onErrorResume(e -> { log.warn("Cache invalidation failed after create: {}", e.getMessage()); return Mono.empty(); })
                        .thenReturn(ResponseEntity.ok(Map.of("id", (Object) id, "status", (Object) "created")));
            });
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete translation")
    public Mono<ResponseEntity<Map<String, String>>> deleteTranslation(@PathVariable Long id) {
        return translationRepository.deleteById(id)
            .flatMap(rows -> {
                if (rows > 0) {
                    log.info("Translation {} deleted", id);
                    return i18nController.invalidateCache()
                            .onErrorResume(e -> { log.warn("Cache invalidation failed after delete: {}", e.getMessage()); return Mono.empty(); })
                            .thenReturn(ResponseEntity.ok(Map.of("status", "deleted")));
                }
                return Mono.just(ResponseEntity.<Map<String, String>>notFound().build());
            });
    }

    @PostMapping("/cache/invalidate")
    @Operation(summary = "Force cache refresh")
    public Mono<ResponseEntity<Map<String, String>>> invalidateCache() {
        return i18nController.invalidateCache();
    }
}
