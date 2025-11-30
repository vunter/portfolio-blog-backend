package dev.catananti.config;

import java.util.Locale;
import java.util.List;
import java.util.Set;

/**
 * CQ-03: Centralized locale constants — single source of truth.
 * Used by controllers, services, and filters instead of duplicated Set definitions.
 */
public final class LocaleConstants {

    private LocaleConstants() {}

    /**
     * All supported locale codes as strings (for query parameter validation).
     */
    public static final Set<String> SUPPORTED_LOCALE_CODES = Set.of(
            "en", "pt", "pt-br", "es", "it", "fr", "de", "ja", "zh"
    );

    /**
     * Supported Locale objects (for LocaleContextFilter and i18n).
     */
    public static final List<Locale> SUPPORTED_LOCALES = List.of(
            Locale.ENGLISH,
            Locale.of("pt", "BR"),
            Locale.of("es"),
            Locale.of("it"),
            Locale.of("fr"),
            Locale.of("de"),
            Locale.of("ja"),
            Locale.of("zh")
    );

    /**
     * Default locale code.
     */
    public static final String DEFAULT_LOCALE = "en";

    /**
     * Check if a locale code is supported.
     */
    public static boolean isSupported(String locale) {
        return locale != null && SUPPORTED_LOCALE_CODES.contains(locale.toLowerCase());
    }
}
