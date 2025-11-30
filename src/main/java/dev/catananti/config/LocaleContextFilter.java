package dev.catananti.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * WebFilter that extracts locale from Accept-Language header
 * and stores it in the reactive context for downstream services.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@Slf4j
public class LocaleContextFilter implements WebFilter {

    private static final String LOCALE_CONTEXT_KEY = "locale";
    // CQ-03: Use centralized locale constants
    private static final List<Locale> SUPPORTED_LOCALES = LocaleConstants.SUPPORTED_LOCALES;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Locale locale = resolveLocale(exchange);
        log.debug("Resolved locale: {}", locale);
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(LOCALE_CONTEXT_KEY, locale));
    }

    private Locale resolveLocale(ServerWebExchange exchange) {
        // Check query param first (explicit override)
        String langParam = exchange.getRequest().getQueryParams().getFirst("lang");
        if (langParam != null && !langParam.isBlank()) {
            return parseLocale(langParam);
        }

        // Fall back to Accept-Language header
        String acceptLanguage = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE);
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
            Locale matched = Locale.lookup(ranges, SUPPORTED_LOCALES);
            if (matched != null) {
                return matched;
            }
        }

        return Locale.ENGLISH;
    }

    private Locale parseLocale(String lang) {
        String normalized = lang.toLowerCase().trim();
        return switch (normalized) {
            case String s when s.startsWith("pt") -> Locale.of("pt", "BR");
            case String s when s.startsWith("es") -> Locale.of("es");
            case String s when s.startsWith("it") -> Locale.of("it");
            case String s when s.startsWith("fr") -> Locale.of("fr");
            case String s when s.startsWith("de") -> Locale.of("de");
            case String s when s.startsWith("ja") -> Locale.of("ja");
            case String s when s.startsWith("zh") -> Locale.of("zh");
            default -> Locale.ENGLISH;
        };
    }
}
