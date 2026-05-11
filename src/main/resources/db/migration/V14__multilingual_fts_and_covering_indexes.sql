-- ============================================
-- V14: Multilingual FTS and covering indexes
-- ============================================

-- Q4.2: Replace English-only FTS index with 'simple' config for multilingual support.
-- 'simple' config performs no stemming or stop-word removal, so it works equally
-- well across all supported languages (EN, PT, ES, IT).
DROP INDEX IF EXISTS idx_articles_search;
CREATE INDEX idx_articles_search ON articles USING GIN (
    to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(excerpt, '') || ' ' || coalesce(content, ''))
);

-- Q4.3: Covering index for article list queries.
-- The most common query (article list) fetches slug, title, excerpt, author_id, views_count.
-- INCLUDE avoids heap lookups by making the index cover all selected columns.
-- Drop the composite index from V13 and replace with a covering version.
DROP INDEX IF EXISTS idx_articles_status_published_at;
CREATE INDEX idx_articles_status_published_at ON articles(status, published_at DESC)
    INCLUDE (slug, title, excerpt, author_id, views_count);
