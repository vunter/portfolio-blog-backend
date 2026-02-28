package dev.catananti.dto;

import java.time.LocalDateTime;

/**
 * Lightweight DTO for RSS feed items, decoupled from the Article entity.
 */
public record RssFeedItem(
        String title,
        String slug,
        String excerpt,
        String seoDescription,
        LocalDateTime publishedAt
) {}
