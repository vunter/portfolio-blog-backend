package dev.catananti.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized pagination configuration.
 * All page size defaults and safety limits live here instead of being scattered across controllers/services.
 */
@Configuration
@ConfigurationProperties(prefix = "app.pagination")
@Getter
@Setter
public class PaginationConfig {

    /** Default page size for paginated endpoints. */
    private int defaultPageSize = 20;

    /** Maximum allowed page size for any paginated endpoint. */
    private int maxPageSize = 100;

    /** Maximum items for RSS/Atom feed generation. */
    private int feedMaxItems = 100;

    /** Maximum root comments loaded for tree building (non-paginated). */
    private int commentTreeMax = 500;

    /** Maximum items for admin bulk queries (users by role, subscribers by status). */
    private int bulkQueryMax = 1000;

    /** Maximum contacts for non-paginated admin listing. */
    private int contactListMax = 100;

    /**
     * Clamp a requested page size to the allowed range [1, maxPageSize].
     */
    public int clampPageSize(int requested) {
        return Math.max(1, Math.min(requested, maxPageSize));
    }
}
