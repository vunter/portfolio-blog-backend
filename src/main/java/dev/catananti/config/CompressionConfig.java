package dev.catananti.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.WebFilter;

/**
 * Configuration for public cacheable endpoints cache-control headers.
 * Note: For production, consider using server-level compression (nginx, etc.)
 */
// I-15: @Configuration removed — dead code. etagFilter was a no-op pass-through.
class CompressionConfig {

    // @Bean removed — dead code
    WebFilter etagFilter() {
        return (exchange, chain) -> {
            // Add ETag header for cacheable GET requests
            if ("GET".equals(exchange.getRequest().getMethod().name())) {
                String path = exchange.getRequest().getPath().value();
                
                // Check if client sent If-None-Match header
                String ifNoneMatch = exchange.getRequest().getHeaders().getFirst(HttpHeaders.IF_NONE_MATCH);
                
                // Cache headers are handled by HttpCacheFilter — removed duplicate headers here
            }
            
            return chain.filter(exchange);
        };
    }

    private boolean isPublicCacheablePath(String path) {
        return (path.startsWith("/api/v1/articles") && !path.contains("/admin")) ||
               path.startsWith("/api/v1/tags") ||
               path.startsWith("/api/v1/rss") ||
               path.startsWith("/api/v1/sitemap") ||
               path.startsWith("/images/");
    }
}
