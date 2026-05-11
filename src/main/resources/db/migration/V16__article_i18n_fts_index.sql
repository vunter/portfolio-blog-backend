-- ============================================
-- V16: Full-text search index on article_i18n translations
-- ============================================

-- Q4.2: Multi-language search — the search query now matches against BOTH the
-- base article columns AND any article_i18n translation via an EXISTS subquery.
-- This GIN index makes the translation match fast regardless of translation count.
-- Uses 'simple' config (no stemming / stop-words) for equal quality across languages.
CREATE INDEX IF NOT EXISTS idx_article_i18n_search ON article_i18n USING GIN (
    to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(excerpt, '') || ' ' || coalesce(content, ''))
);
