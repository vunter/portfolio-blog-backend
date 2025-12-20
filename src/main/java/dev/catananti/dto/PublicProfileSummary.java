package dev.catananti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight DTO for listing published developer profiles.
 * Used by the public profile selector (future feature).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicProfileSummary {
    /** The URL alias (e.g., "leonardo-catananti") */
    private String alias;
    /** Display name (e.g., "Leonardo Eifert Catananti") */
    private String name;
    /** Professional title (e.g., "Senior Software Engineer") */
    private String title;
    /** Avatar URL if available */
    private String avatarUrl;
}
