package dev.catananti.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/languages")
@RequiredArgsConstructor
@Tag(name = "Languages", description = "Supported language options")
public class LanguagesController {

    private final R2dbcEntityTemplate r2dbcTemplate;

    @GetMapping
    @Operation(summary = "Get supported languages", description = "Returns all enabled languages for i18n")
    public Mono<List<Map<String, Object>>> getSupportedLanguages() {
        return r2dbcTemplate.getDatabaseClient()
            .sql("SELECT code, name, native_name, sort_order FROM languages WHERE is_active = true ORDER BY sort_order")
            .map((row, meta) -> {
                Map<String, Object> lang = new java.util.LinkedHashMap<>();
                lang.put("code", row.get("code", String.class));
                lang.put("name", row.get("name", String.class));
                lang.put("nativeName", row.get("native_name", String.class));
                lang.put("sortOrder", row.get("sort_order", Integer.class));
                return lang;
            })
            .all()
            .collectList();
    }
}
