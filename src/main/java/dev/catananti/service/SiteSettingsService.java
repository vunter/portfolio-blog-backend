package dev.catananti.service;

import dev.catananti.entity.SiteSetting;
import dev.catananti.repository.SiteSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for managing site settings with database persistence.
 * Settings are stored as key-value pairs in the site_settings table.
 * TODO F-225: Consider caching settings in memory with short TTL to reduce DB queries
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteSettingsService {

    private final SiteSettingRepository repository;
    private final IdService idService;

    @Value("${app.site-url:https://catananti.dev}")
    private String siteUrl;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    private static final Map<String, String> DEFAULTS = Map.of(
            "siteName", "Leonardo Catananti - Blog",
            "siteDescription", "Backend Engineer & Software Architect",
            "postsPerPage", "10",
            "commentsEnabled", "true",
            "commentModeration", "true",
            "newsletterEnabled", "true",
            "analyticsEnabled", "true",
            "maintenanceMode", "false"
    );

    /**
     * Get all settings as a map, filling in defaults for any missing values.
     */
    public Mono<Map<String, Object>> getAllSettings() {
        log.debug("Loading all site settings");
        return repository.findAllByOrderBySettingKeyAsc()
                .collectMap(SiteSetting::getSettingKey, s -> (Object) parseValue(s.getSettingValue(), s.getSettingType()))
                .map(stored -> {
                    Map<String, Object> result = new HashMap<>(DEFAULTS.size() + 2);
                    // Add dynamic defaults
                    result.put("siteUrl", siteUrl);
                    result.put("cacheEnabled", !"dev".equals(activeProfile));
                    // Add static defaults
                    DEFAULTS.forEach((k, v) -> result.put(k, parseValue(v, inferType(v))));
                    // Override with stored values
                    result.putAll(stored);
                    return result;
                });
    }

    /**
     * Update multiple settings at once.
     */
    // TODO F-225: Batch setting updates instead of N+1 individual updates
    public Mono<Map<String, Object>> updateSettings(Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) {
            log.debug("No settings to update, returning current settings");
            return getAllSettings();
        }
        log.info("Updating {} site settings: {}", settings.size(), settings.keySet());

        return Mono.defer(() -> {
            var saves = settings.entrySet().stream()
                    .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                    .filter(e -> isAllowedKey(e.getKey()))
                    .map(entry -> repository.findBySettingKey(entry.getKey())
                            .flatMap(existing -> {
                                existing.setSettingValue(String.valueOf(entry.getValue()));
                                existing.setSettingType(inferType(String.valueOf(entry.getValue())));
                                existing.setUpdatedAt(LocalDateTime.now());
                                existing.setNewRecord(false);
                                return repository.save(existing);
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                SiteSetting newSetting = SiteSetting.builder()
                                        .id(idService.nextId())
                                        .settingKey(entry.getKey())
                                        .settingValue(String.valueOf(entry.getValue()))
                                        .settingType(inferType(String.valueOf(entry.getValue())))
                                        .createdAt(LocalDateTime.now())
                                        .updatedAt(LocalDateTime.now())
                                        .build();
                                return repository.save(newSetting);
                            })))
                    .toList();

            return Mono.when(saves).then(getAllSettings());
        });
    }

    /**
     * Get a single setting value, or the default.
     */
    public Mono<Object> getSetting(String key) {
        log.debug("Getting site setting: key='{}'", key);
        return repository.findBySettingKey(key)
                .map(s -> (Object) parseValue(s.getSettingValue(), s.getSettingType()))
                .switchIfEmpty(Mono.justOrEmpty(getDefault(key)));
    }

    private Object getDefault(String key) {
        String defaultVal = DEFAULTS.get(key);
        if (defaultVal != null) {
            return parseValue(defaultVal, inferType(defaultVal));
        }
        if ("siteUrl".equals(key)) return siteUrl;
        if ("cacheEnabled".equals(key)) return !"dev".equals(activeProfile);
        return null;
    }

    private boolean isAllowedKey(String key) {
        // Prevent setting system-computed keys
        return !"siteUrl".equals(key) && !"cacheEnabled".equals(key);
    }

    private Object parseValue(String value, String type) {
        if (value == null) return null;
        return switch (type) {
            case "BOOLEAN" -> Boolean.parseBoolean(value);
            case "INTEGER" -> {
                try { yield Integer.parseInt(value); }
                catch (NumberFormatException e) { yield value; }
            }
            case "LONG" -> {
                try { yield Long.parseLong(value); }
                catch (NumberFormatException e) { yield value; }
            }
            default -> value;
        };
    }

    private String inferType(String value) {
        if (value == null) return "STRING";
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return "BOOLEAN";
        try { Integer.parseInt(value); return "INTEGER"; } catch (NumberFormatException ignored) {}
        try { Long.parseLong(value); return "LONG"; } catch (NumberFormatException ignored) {}
        return "STRING";
    }
}
