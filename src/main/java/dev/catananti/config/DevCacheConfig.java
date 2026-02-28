package dev.catananti.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.TimeUnit;

/**
 * Cache configuration for dev profile using in-memory cache.
 * Redis is not required in development mode.
 * Uses Caffeine for caches with TTL (like resumeHtml).
 */
@Configuration(proxyBeanMethods = false)
@Profile({"dev", "local", "e2e"})
public class DevCacheConfig {

    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)  // 5-minute TTL
            .maximumSize(500));
        cacheManager.setCacheNames(java.util.List.of(
            "articles",
            "articlesBySlug",
            "tags",
            "comments",
            "users",
            "github-repos",
            "resumeHtml",
            "templates"
        ));
        // Enable async cache mode for reactive types (Mono, Flux)
        cacheManager.setAsyncCacheMode(true);
        return cacheManager;
    }
}
