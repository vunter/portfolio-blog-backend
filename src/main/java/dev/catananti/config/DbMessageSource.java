package dev.catananti.config;

import dev.catananti.repository.TranslationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
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

    public DbMessageSource(TranslationRepository translationRepository) {
        this.translationRepository = translationRepository;
        this.fallback = new ReloadableResourceBundleMessageSource();
        this.fallback.setBasename("classpath:messages");
        this.fallback.setDefaultEncoding(StandardCharsets.UTF_8.name());
        this.fallback.setUseCodeAsDefaultMessage(true);
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
    }

    private Map<String, String> getTranslationsForLocale(String locale) {
        if (System.currentTimeMillis() - lastLoadTime > CACHE_TTL_MS) {
            cache.clear();
            lastLoadTime = System.currentTimeMillis();
        }

        return cache.computeIfAbsent(locale, loc -> {
            try {
                Map<String, String> result = new ConcurrentHashMap<>();
                translationRepository.findBackendByLocale(loc)
                    .doOnNext(row -> result.put(row.get("key"), row.get("value")))
                    .blockLast(java.time.Duration.ofSeconds(10));
                log.debug("Loaded {} backend translations for locale {}", result.size(), loc);
                return result;
            } catch (Exception e) {
                log.warn("Failed to load translations from DB for locale {}, using fallback", loc, e);
                return Map.of();
            }
        });
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
