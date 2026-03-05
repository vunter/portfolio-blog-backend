package dev.catananti.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class TranslationRepository {
    private final R2dbcEntityTemplate r2dbcTemplate;

    /**
     * Get all frontend translations for a locale filtered by visibility tiers.
     */
    public Flux<Map<String, String>> findByLocaleAndVisibility(String locale, List<String> visibilities) {
        return r2dbcTemplate.getDatabaseClient()
            .sql("SELECT translation_key, value FROM ui_translations WHERE locale = :locale AND namespace = 'frontend' AND visibility = ANY(:vis)")
            .bind("locale", locale)
            .bind("vis", visibilities.toArray(new String[0]))
            .map((row, meta) -> Map.of(
                "key", row.get("translation_key", String.class),
                "value", row.get("value", String.class)
            ))
            .all();
    }

    /**
     * Get all backend translations for a locale (all public).
     */
    public Flux<Map<String, String>> findBackendByLocale(String locale) {
        return r2dbcTemplate.getDatabaseClient()
            .sql("SELECT translation_key, value FROM ui_translations WHERE locale = :locale AND namespace = 'backend'")
            .bind("locale", locale)
            .map((row, meta) -> Map.of(
                "key", row.get("translation_key", String.class),
                "value", row.get("value", String.class)
            ))
            .all();
    }

    /**
     * Admin: paginated list with optional search filter.
     */
    public Flux<Map<String, Object>> findAllPaginated(String locale, String namespace, String search, int offset, int limit) {
        String sql;
        if (search != null && !search.isBlank()) {
            sql = "SELECT id, translation_key, locale, value, namespace, visibility, updated_at FROM ui_translations " +
                  "WHERE locale = :locale AND namespace = :ns AND (translation_key ILIKE :search OR value ILIKE :search) " +
                  "ORDER BY translation_key LIMIT :limit OFFSET :offset";
        } else {
            sql = "SELECT id, translation_key, locale, value, namespace, visibility, updated_at FROM ui_translations " +
                  "WHERE locale = :locale AND namespace = :ns ORDER BY translation_key LIMIT :limit OFFSET :offset";
        }
        var query = r2dbcTemplate.getDatabaseClient().sql(sql)
            .bind("locale", locale)
            .bind("ns", namespace)
            .bind("limit", limit)
            .bind("offset", offset);

        if (search != null && !search.isBlank()) {
            query = query.bind("search", "%" + search + "%");
        }

        return query.map((row, meta) -> {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", row.get("id", Long.class));
            map.put("translationKey", row.get("translation_key", String.class));
            map.put("locale", row.get("locale", String.class));
            map.put("value", row.get("value", String.class));
            map.put("namespace", row.get("namespace", String.class));
            map.put("visibility", row.get("visibility", String.class));
            map.put("updatedAt", row.get("updated_at", java.time.LocalDateTime.class));
            return (Map<String, Object>) map;
        }).all();
    }

    public Mono<Long> countAll(String locale, String namespace, String search) {
        String sql;
        if (search != null && !search.isBlank()) {
            sql = "SELECT COUNT(*) FROM ui_translations WHERE locale = :locale AND namespace = :ns AND (translation_key ILIKE :search OR value ILIKE :search)";
        } else {
            sql = "SELECT COUNT(*) FROM ui_translations WHERE locale = :locale AND namespace = :ns";
        }
        var query = r2dbcTemplate.getDatabaseClient().sql(sql)
            .bind("locale", locale)
            .bind("ns", namespace);

        if (search != null && !search.isBlank()) {
            query = query.bind("search", "%" + search + "%");
        }

        return query.map((row, meta) -> row.get(0, Long.class)).one();
    }

    public Mono<Integer> updateValue(Long id, String value) {
        return r2dbcTemplate.getDatabaseClient()
            .sql("UPDATE ui_translations SET value = :value, updated_at = NOW() WHERE id = :id")
            .bind("value", value)
            .bind("id", id)
            .fetch().rowsUpdated()
            .map(Long::intValue);
    }

    public Mono<Long> insert(String key, String locale, String value, String namespace, String visibility) {
        return r2dbcTemplate.getDatabaseClient()
            .sql("INSERT INTO ui_translations (translation_key, locale, value, namespace, visibility) VALUES (:key, :locale, :value, :ns, :vis) RETURNING id")
            .bind("key", key)
            .bind("locale", locale)
            .bind("value", value)
            .bind("ns", namespace)
            .bind("vis", visibility)
            .map((row, meta) -> row.get("id", Long.class))
            .one();
    }

    public Mono<Integer> deleteById(Long id) {
        return r2dbcTemplate.getDatabaseClient()
            .sql("DELETE FROM ui_translations WHERE id = :id")
            .bind("id", id)
            .fetch().rowsUpdated()
            .map(Long::intValue);
    }
}
