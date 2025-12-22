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
     * Find all active templates with aliases (for public profile listing).
     * Returns templates that have an alias set and are ACTIVE status.
     */
    @Query("SELECT * FROM resume_templates WHERE url_alias IS NOT NULL AND url_alias != '' AND status = 'ACTIVE' ORDER BY url_alias")
    Flux<ResumeTemplate> findActiveWithAlias();

    /**
     * Search templates by name (searches across all locale values in JSONB).
     */
    // TODO F-285: Add pg_trgm GIN index for LIKE queries
    // TODO F-291: Sanitize LIKE pattern to escape %, _, \ characters
    @Query("SELECT * FROM resume_templates WHERE owner_id = :ownerId AND EXISTS (SELECT 1 FROM jsonb_each_text(name) jt WHERE LOWER(jt.value) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    Flux<ResumeTemplate> searchByName(Long ownerId, String searchTerm);
}
