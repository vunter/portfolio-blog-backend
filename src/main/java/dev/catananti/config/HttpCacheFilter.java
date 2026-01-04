package dev.catananti.config;

import dev.catananti.util.DigestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * HTTP caching filter that adds ETag and Cache-Control headers.
 * Optimizes bandwidth by enabling conditional requests (304 Not Modified).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class HttpCacheFilter implements WebFilter {

    @Value("${cache.http.enabled:true}")
    private boolean enabled;

    @Value("${cache.http.public-max-age-seconds:300}")
    private int publicMaxAgeSeconds;

    @Value("${cache.http.private-max-age-seconds:60}")
    private int privateMaxAgeSeconds;

    private static final Set<String> CACHEABLE_PATHS = Set.of(
            "/api/v1/articles",
            "/api/v1/tags",
            "/rss.xml",
            "/feed.xml",
            "/sitemap.xml"
    );

    private static final Set<String> PRIVATE_PATHS = Set.of(
            "/api/v1/auth",
            "/api/v1/admin"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        // Only cache GET requests - non-GET (DELETE/POST/PUT) don't need cache headers
        if (!"GET".equals(method)) {
            return chain.filter(exchange);
        }

        // Register beforeCommit callback to add cache headers BEFORE response is flushed.
        // Using doOnSuccess is incorrect because the response body is already committed by then,
        // causing UnsupportedOperationException and ERR_INCOMPLETE_CHUNKED_ENCODING on the client.
        exchange.getResponse().beforeCommit(() -> {
            try {
                addCacheHeaders(exchange, path);
            } catch (Exception e) {
                log.trace("Could not add cache headers: {}", e.getMessage());
            }
            return Mono.empty();
        });

        return chain.filter(exchange);
    }

    private void addCacheHeaders(ServerWebExchange exchange, String path) {
        if (exchange.getResponse().isCommitted()) {
            return;
        }
        HttpHeaders headers = exchange.getResponse().getHeaders();

        // Determine cache policy based on path
        CachePolicy policy = determineCachePolicy(path);

        switch (policy) {
            case PUBLIC -> {
                headers.setCacheControl("public, max-age=" + publicMaxAgeSeconds + ", stale-while-revalidate=60");
                headers.set("Vary", "Accept-Encoding, Accept");
            }
            case PRIVATE -> {
                headers.setCacheControl("private, max-age=" + privateMaxAgeSeconds + ", must-revalidate");
            }
            case NO_CACHE -> {
                addNoCacheHeaders(exchange);
            }
        }

        // Generate and add ETag based on response characteristics
        String etag = generateETag(exchange, path);
        if (etag != null) {
            headers.setETag("\"" + etag + "\"");
        }
    }

    private void addNoCacheHeaders(ServerWebExchange exchange) {
        if (exchange.getResponse().isCommitted()) {
            return;
        }
        try {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.setCacheControl("no-store, no-cache, must-revalidate, max-age=0");
            headers.setPragma("no-cache");
            headers.setExpires(0);
        } catch (UnsupportedOperationException e) {
            log.trace("Could not set no-cache headers - response already committed");
        }
    }

    // F-028: Use iteration instead of Stream creation per request for prefix matching
    private CachePolicy determineCachePolicy(String path) {
        // Static assets and public endpoints can be cached publicly
        for (String cacheable : CACHEABLE_PATHS) {
            if (path.startsWith(cacheable)) return CachePolicy.PUBLIC;
        }

        // Private paths need private caching
        for (String privatePath : PRIVATE_PATHS) {
            if (path.startsWith(privatePath)) return CachePolicy.PRIVATE;
        }

        // Default to no caching for unknown paths
        return CachePolicy.NO_CACHE;
    }

    // NOTE F-029: ETag is based on response metadata, not body hash (acceptable for immutable assets)
    // NOTE F-030: max-age values are configurable via cache.http.public-max-age-seconds / private-max-age-seconds
    private String generateETag(ServerWebExchange exchange, String path) {
        try {
            // IMP-07: Strengthened ETag includes content-length, last-modified, content-type,
            // and content-encoding to differentiate responses that share the same path/status.
            // Content-Length may be absent for chunked responses, so we include all available
            // response metadata to maximize differentiation.
            String contentLength = exchange.getResponse().getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH);
            String lastModified = exchange.getResponse().getHeaders().getFirst(HttpHeaders.LAST_MODIFIED);
            String contentType = exchange.getResponse().getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            String contentEncoding = exchange.getResponse().getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
            String content = path + ":" +
                    exchange.getResponse().getStatusCode() + ":" +
                    (contentLength != null ? contentLength : "chunked") + ":" +
                    (lastModified != null ? lastModified : "none") + ":" +
                    (contentType != null ? contentType : "unknown") + ":" +
                    (contentEncoding != null ? contentEncoding : "identity");

            return DigestUtils.sha256Hex(content, 16);
        } catch (Exception e) {
            log.warn("Could not generate ETag: {}", e.getMessage());
            return null;
        }
    }

    private enum CachePolicy {
        PUBLIC,
        PRIVATE,
        NO_CACHE
    }
}
