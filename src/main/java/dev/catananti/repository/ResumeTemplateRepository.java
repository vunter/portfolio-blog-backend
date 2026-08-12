package dev.catananti.repository;

import dev.catananti.entity.ResumeTemplate;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for ResumeTemplate entity.
 */
@Repository
public interface ResumeTemplateRepository extends R2dbcRepository<ResumeTemplate, Long> {

    /**
     * Find template by slug.
     */
    @Query("SELECT * FROM resume_templates WHERE slug = :slug")
    Mono<ResumeTemplate> findBySlug(String slug);

    /**
     * Find template by alias (public URL path).
     */
    @Query("SELECT * FROM resume_templates WHERE url_alias = :alias")
    Mono<ResumeTemplate> findByAlias(String alias);

    /**
     * Check if alias exists.
     */
    @Query("SELECT COUNT(*) > 0 FROM resume_templates WHERE url_alias = :alias")
    Mono<Boolean> existsByAlias(String alias);

    /**
     * Check if slug exists.
     */
    @Query("SELECT COUNT(*) > 0 FROM resume_templates WHERE slug = :slug")
    Mono<Boolean> existsBySlug(String slug);

    /**
     * Find all templates by owner.
     */
    @Query("SELECT * FROM resume_templates WHERE owner_id = :ownerId")
    Flux<ResumeTemplate> findByOwnerId(Long ownerId);

    /**
     * Find all templates by owner and status.
     */
    @Query("SELECT * FROM resume_templates WHERE owner_id = :ownerId AND status = :status")
    Flux<ResumeTemplate> findByOwnerIdAndStatus(Long ownerId, String status);

    /**
     * Find the default template for an owner.
     */
    @Query("SELECT * FROM resume_templates WHERE owner_id = :ownerId AND is_default = true LIMIT 1")
    Mono<ResumeTemplate> findByOwnerIdAndIsDefaultTrue(Long ownerId);

    /**
     * Find all active templates.
     */
    @Query("SELECT * FROM resume_templates WHERE status = :status")
    Flux<ResumeTemplate> findByStatus(String status);

    /**
     * Find templates by owner with pagination.
     */
    @Query("SELECT * FROM resume_templates WHERE owner_id = :ownerId ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    Flux<ResumeTemplate> findByOwnerIdPaginated(Long ownerId, int limit, int offset);

    /**
     * Count templates by owner.
     */
    @Query("SELECT COUNT(*) FROM resume_templates WHERE owner_id = :ownerId")
    Mono<Long> countByOwnerId(Long ownerId);

    /**
     * Count templates by owner and status.
     */
    @Query("SELECT COUNT(*) FROM resume_templates WHERE owner_id = :ownerId AND status = :status")
    Mono<Long> countByOwnerIdAndStatus(Long ownerId, String status);

    /**
     * Find most downloaded templates.
     */
    @Query("SELECT * FROM resume_templates WHERE status = 'ACTIVE' ORDER BY download_count DESC LIMIT :limit")
    Flux<ResumeTemplate> findMostDownloaded(int limit);

    /**
     * Find recently updated templates.
     */
    @Query("SELECT * FROM resume_templates WHERE status = 'ACTIVE' ORDER BY updated_at DESC LIMIT :limit")
    Flux<ResumeTemplate> findRecentlyUpdated(int limit);

    /**
     * Increment download count.
     */
    @Query("UPDATE resume_templates SET download_count = download_count + 1 WHERE id = :id")
    Mono<Void> incrementDownloadCount(Long id);

    /**
     * Reset default flag for all templates of an owner.
     */
    @Query("UPDATE resume_templates SET is_default = false WHERE owner_id = :ownerId AND is_default = true")
    Mono<Void> resetDefaultForOwner(Long ownerId);

    /**
     * NP-1: single-query projection for the public profile selector. The previous
     * implementation loaded the FULL profile (11 child tables + template html/css)
     * per active template just to render alias/name/title/avatar — ~14 queries per
     * profile per request. The correlated subquery picks the best-matching profile
     * locale (exact → language prefix → en → any).
     */
    @Query("""
            SELECT t.url_alias AS alias,
                   COALESCE(p.full_name, u.name) AS name,
                   p.title AS title,
                   u.avatar_url AS avatar_url
            FROM resume_templates t
            JOIN users u ON u.id = t.owner_id
            LEFT JOIN resume_profiles p ON p.owner_id = t.owner_id
              AND p.id = (
                  SELECT rp.id FROM resume_profiles rp
                  WHERE rp.owner_id = t.owner_id
                  ORDER BY CASE WHEN rp.locale = :lang THEN 0
                                WHEN rp.locale LIKE :langPrefix THEN 1
                                WHEN rp.locale = 'en' THEN 2
                                ELSE 3 END, rp.id
                  LIMIT 1
              )
            WHERE t.url_alias IS NOT NULL AND t.url_alias != '' AND t.status = 'ACTIVE'
            ORDER BY t.url_alias
            """)
    Flux<dev.catananti.dto.PublicProfileSummary> findPublicProfileSummaries(String lang, String langPrefix);

    /**
     * Search templates by name (searches across all locale values in JSONB).
     */
    @Query("SELECT * FROM resume_templates WHERE owner_id = :ownerId AND EXISTS (SELECT 1 FROM jsonb_each_text(name) jt WHERE LOWER(jt.value) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    Flux<ResumeTemplate> searchByName(Long ownerId, String searchTerm);
}
