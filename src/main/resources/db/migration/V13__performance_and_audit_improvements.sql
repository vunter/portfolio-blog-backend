-- ============================================
-- V13: Performance indexes and schema improvements
-- ============================================

-- 1. Composite indexes for article list queries
--    Existing single-column idx_articles_status and idx_articles_published_at are suboptimal
--    for the combined WHERE status = :status ORDER BY published_at DESC pattern.
CREATE INDEX IF NOT EXISTS idx_articles_status_published_at
    ON articles(status, published_at DESC);

CREATE INDEX IF NOT EXISTS idx_articles_status_views_count
    ON articles(status, views_count DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS idx_articles_status_created_at
    ON articles(status, created_at DESC);

-- 2. Composite index for author-scoped queries (DEV dashboard)
CREATE INDEX IF NOT EXISTS idx_articles_author_status_created
    ON articles(author_id, status, created_at DESC);
