package dev.catananti.config;

import dev.catananti.repository.TranslationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB-backed MessageSource with file-based fallback.
 * Loads backend namespace translations from ui_translations table.
 * Falls back to messages*.properties if DB is empty or unavailable.
 */
@Slf4j
public class DbMessageSource implements MessageSource {

    private final TranslationRepository translationRepository;
    private final ReloadableResourceBundleMessageSource fallback;

    // Cache: locale -> (key -> value)
    private final ConcurrentHashMap<String, Map<String, String>> cache = new ConcurrentHashMap<>();
    private volatile long lastLoadTime = 0;
    private static final long CACHE_TTL_MS = 3600_000; // 1 hour
    private static final List<String> SUPPORTED_LOCALES = List.of("en", "pt", "es", "it");

    public DbMessageSource(TranslationRepository translationRepository) {
        this.translationRepository = translationRepository;
        this.fallback = new ReloadableResourceBundleMessageSource();
        this.fallback.setBasename("classpath:messages");
        this.fallback.setDefaultEncoding(StandardCharsets.UTF_8.name());
        this.fallback.setUseCodeAsDefaultMessage(true);
        // Eagerly pre-load translations on construction so no request ever blocks
        preloadCache();
    }

    @Override
    public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        String localeStr = resolveLocaleString(locale);
        Map<String, String> translations = getTranslationsForLocale(localeStr);

        String value = translations.get(code);
        if (value != null) {
            return args != null ? new MessageFormat(value, locale).format(args) : value;
        }

        // Fallback to properties files
        return fallback.getMessage(code, args, defaultMessage, locale);
    }

    @Override
    public String getMessage(String code, Object[] args, Locale locale) throws NoSuchMessageException {
        String result = getMessage(code, args, null, locale);
        if (result == null) {
            throw new NoSuchMessageException(code, locale);
        }
        return result;
    }

    @Override
    public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
        String[] codes = resolvable.getCodes();
        if (codes != null) {
            for (String code : codes) {
                String msg = getMessage(code, resolvable.getArguments(), null, locale);
                if (msg != null) {
                    return msg;
                }
            }
        }
        String defaultMessage = resolvable.getDefaultMessage();
        if (defaultMessage != null) {
            return defaultMessage;
        }
        throw new NoSuchMessageException(codes != null && codes.length > 0 ? codes[0] : "", locale);
    }

    public void invalidateCache() {
        cache.clear();
        lastLoadTime = 0;
        log.info("DbMessageSource cache invalidated");
        // Re-populate asynchronously so next request doesn't block
        reactor.core.publisher.Flux.fromIterable(SUPPORTED_LOCALES)
            .flatMap(this::loadLocaleAsync)
            .subscribe(
                    null,
                    error -> log.error("Failed to reload translations after cache invalidation: {}", error.getMessage(), error)
            );
    }

    /**
     * Eagerly pre-load all supported locales into cache (non-blocking).
     * Falls back to properties files until async load completes.
     */
    private void preloadCache() {
        lastLoadTime = System.currentTimeMillis();
        reactor.core.publisher.Flux.fromIterable(SUPPORTED_LOCALES)
                .flatMap(this::loadLocaleAsync)
                .subscribe(
                        null,
                        e -> log.warn("Failed to pre-load translations from DB, will use fallback", e),
                        () -> log.info("Pre-loaded backend translations for {} locales", SUPPORTED_LOCALES.size())
                );
    }

    /**
     * Asynchronously reload a single locale into cache (non-blocking).
     */
    private reactor.core.publisher.Mono<Void> loadLocaleAsync(String locale) {
        return translationRepository.findBackendByLocale(locale)
            .collectList()
            .doOnNext(rows -> {
                Map<String, String> result = new ConcurrentHashMap<>();
                for (var row : rows) {
                    result.put(row.get("key"), row.get("value"));
                }
                cache.put(locale, result);
                log.debug("Async-loaded {} backend translations for locale {}", result.size(), locale);
            })
            .doOnError(e -> log.warn("Failed to async-load translations for locale {}", locale, e))
            .onErrorComplete()
            .then();
    }

    private Map<String, String> getTranslationsForLocale(String locale) {
        if (System.currentTimeMillis() - lastLoadTime > CACHE_TTL_MS) {
            // TTL expired — trigger async refresh, return stale cache (never block a request)
            lastLoadTime = System.currentTimeMillis();
            reactor.core.publisher.Flux.fromIterable(SUPPORTED_LOCALES)
                .flatMap(this::loadLocaleAsync)
                .subscribe(
                        null,
                        error -> log.error("Failed to refresh translation cache: {}", error.getMessage(), error)
                );
        }

        // Return from cache — always non-blocking. Empty map if not yet loaded.
        return cache.getOrDefault(locale, Map.of());
    }

    private String resolveLocaleString(Locale locale) {
        String lang = locale.getLanguage();
        return switch (lang) {
            case "pt" -> "pt";
            case "es" -> "es";
            case "it" -> "it";
            default -> "en";
        };
    }
}
