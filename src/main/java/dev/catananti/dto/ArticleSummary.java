package dev.catananti.dto;

import java.time.LocalDateTime;

/**
 * Lightweight summary projection for article list views.
 * Use instead of full Article entity when only summary fields are needed.
 */
public record ArticleSummary(
        Long id,
        String slug,
        String title,
        String status,
        LocalDateTime publishedAt
) {}
