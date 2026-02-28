package dev.catananti.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Wrapper type for JSONB-stored localized text fields.
 * Stored in PostgreSQL as JSONB: {"en": "Java", "pt-br": "Java", "es": "Java"}
 */
@Slf4j
public class LocalizedText {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE_REF = new TypeReference<>(){};
    private static final String DEFAULT_LOCALE = "en";

    private final Map<String, String> translations;

    public LocalizedText() {
        this.translations = new LinkedHashMap<>();
    }

    public LocalizedText(Map<String, String> translations) {
        this.translations = translations != null ? new LinkedHashMap<>(translations) : new LinkedHashMap<>();
    }

    /**
     * Create from a single locale value.
     */
    public static LocalizedText of(String locale, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        if (value != null) {
            map.put(locale, value);
        }
        return new LocalizedText(map);
    }

    /**
     * Create from English-only value.
     */
    public static LocalizedText ofEnglish(String value) {
        return of(DEFAULT_LOCALE, value);
    }

    /**
     * Get value for a specific locale, with fallback to English, then any available.
     */
    public String get(String locale) {
        if (locale != null && translations.containsKey(locale)) {
            return translations.get(locale);
        }
        if (translations.containsKey(DEFAULT_LOCALE)) {
            log.trace("Locale '{}' not found, falling back to default '{}'", locale, DEFAULT_LOCALE);
            return translations.get(DEFAULT_LOCALE);
        }
        log.trace("Locale '{}' and default '{}' not found, using first available", locale, DEFAULT_LOCALE);
        return translations.values().stream().findFirst().orElse(null);
    }

    /**
     * Get English value (or fallback).
     */
    public String getDefault() {
        return get(DEFAULT_LOCALE);
    }

    /**
     * Set value for a locale.
     */
    public void put(String locale, String value) {
        translations.put(locale, value);
    }

    /**
     * Get all translations as an unmodifiable map.
     */
    public Map<String, String> getTranslations() {
        return Collections.unmodifiableMap(translations);
    }

    /**
     * Check if translations are empty.
     */
    public boolean isEmpty() {
        return translations.isEmpty();
    }

    /**
     * Serialize to JSON string.
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(translations);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize LocalizedText", e);
        }
    }

    /**
     * Deserialize from JSON string.
     */
    public static LocalizedText fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LocalizedText();
        }
        try {
            Map<String, String> map = MAPPER.readValue(json, MAP_TYPE_REF);
            return new LocalizedText(map);
        } catch (JsonProcessingException e) {
            // Fallback: treat as plain text (English)
            return ofEnglish(json);
        }
    }

    @Override
    public String toString() {
        return getDefault();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LocalizedText that = (LocalizedText) o;
        return Objects.equals(translations, that.translations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(translations);
    }
}
