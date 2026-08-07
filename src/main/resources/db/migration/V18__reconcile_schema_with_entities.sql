-- ============================================
-- V18: Reconcile live schemas with the entity model
-- ============================================
-- Environments provisioned before this release got parts of their schema from
-- schema.sql (applied manually via psql) rather than from these migrations, so
-- columns the entities rely on may exist there but not in Flyway-provisioned
-- databases. V1 now creates them for virgin databases; these idempotent ALTERs
-- converge every already-baselined environment to the same shape.

-- comments.likes_count is read/written by CommentRepository (incrementLikes,
-- decrementLikes, getLikesCount, ORDER BY likes_count) but only existed in
-- schema.sql before this migration.
ALTER TABLE comments ADD COLUMN IF NOT EXISTS likes_count INTEGER DEFAULT 0;

-- article_reviews.updated_at is mapped by the ArticleReview entity and written
-- on every review transition.
ALTER TABLE article_reviews ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW();

-- CC-08: the application assumes at most one PENDING role upgrade request per
-- user (check-then-act on submit). Enforce it in the database; the service maps
-- the unique violation to the same 409 the pre-check produces. Older duplicates
-- (same user, multiple PENDING) are resolved keeping the newest request.
DELETE FROM role_upgrade_requests r
USING role_upgrade_requests newer
WHERE r.status = 'PENDING'
  AND newer.status = 'PENDING'
  AND r.user_id = newer.user_id
  AND r.id < newer.id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_role_upgrade_requests_pending
    ON role_upgrade_requests(user_id) WHERE status = 'PENDING';

-- CC-05: optimistic locking for articles. Spring Data R2DBC issues
-- UPDATE ... WHERE version = :loaded and raises OptimisticLockingFailureException
-- (HTTP 409) for the writer holding a stale copy.
ALTER TABLE articles ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- ============================================
-- SI-3: subscriber token lookups
-- ============================================
-- findByConfirmationToken/findByUnsubscribeToken query WITHOUT a status filter, so
-- the old partial indexes (WHERE status = 'PENDING' / token IS NOT NULL) could not
-- serve them. Full unique indexes cover the lookup AND enforce the uniqueness the
-- single-row Monos assume. Tokens are 256-bit randoms — collision-free in practice.
DROP INDEX IF EXISTS idx_subscribers_confirmation_token;
CREATE UNIQUE INDEX IF NOT EXISTS uq_subscribers_confirmation_token
    ON subscribers(confirmation_token) WHERE confirmation_token IS NOT NULL;
DROP INDEX IF EXISTS idx_subscribers_unsubscribe_token;
CREATE UNIQUE INDEX IF NOT EXISTS uq_subscribers_unsubscribe_token
    ON subscribers(unsubscribe_token) WHERE unsubscribe_token IS NOT NULL;

-- ============================================
-- SI-4 / RQ-04: case-insensitive uniqueness for users
-- ============================================
-- The application looks users up with LOWER(email)/LOWER(username) and assumes
-- case-insensitive uniqueness, but the plain UNIQUE constraints are case-sensitive
-- (and unusable by those predicates — every login did a seq scan on users).
UPDATE users SET email = LOWER(TRIM(email)) WHERE email <> LOWER(TRIM(email));
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower ON users (LOWER(email));
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username_lower
    ON users (LOWER(username)) WHERE username IS NOT NULL;

-- ============================================
-- SI-7: cleanup-job coverage
-- ============================================
-- The token cleanup DELETEs filter on expires_at (all rows) and used_at (used rows),
-- which the existing PARTIAL indexes (WHERE NOT used / NOT revoked) cannot serve —
-- every nightly job seq-scanned the token tables.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_full ON refresh_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_expires_full ON password_reset_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_used_at ON password_reset_tokens(used_at) WHERE used;
CREATE INDEX IF NOT EXISTS idx_email_change_tokens_expires_full ON email_change_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_email_change_tokens_used_at ON email_change_tokens(used_at) WHERE used;

-- ============================================
-- SI-8: drop indexes that duplicate UNIQUE constraints
-- ============================================
-- Each UNIQUE constraint already provides an identical index; the duplicates only
-- amplified write cost.
DROP INDEX IF EXISTS idx_articles_slug;
DROP INDEX IF EXISTS idx_refresh_tokens_token;
DROP INDEX IF EXISTS idx_subscribers_email;
DROP INDEX IF EXISTS idx_password_reset_token;
DROP INDEX IF EXISTS idx_resume_templates_slug;
DROP INDEX IF EXISTS idx_resume_templates_alias;
DROP INDEX IF EXISTS idx_contacts_public_id;
DROP INDEX IF EXISTS idx_site_settings_key;

-- ============================================
-- SI-9: media_assets lookups
-- ============================================
-- deleteByUrl resolves assets by url with no index; storage keys are unique by
-- construction (snowflake-based), so enforce it.
CREATE INDEX IF NOT EXISTS idx_media_assets_url ON media_assets(url);
DROP INDEX IF EXISTS idx_media_assets_storage_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_media_assets_storage_key ON media_assets(storage_key);

-- ============================================
-- RQ-08: admin search uses LOWER(col) LIKE '%…%' across all statuses
-- ============================================
-- pg_trgm lives in V1, which production never executed (it baselined at V1),
-- so a V17->V18 upgrade lands on a database without the extension. pg_trgm is
-- trusted since PostgreSQL 13, so the database owner may create it directly.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- pg_trgm expression indexes make the case-insensitive LIKE predicates indexable
-- (the F-285 indexes cover the raw columns only, not the LOWER() expressions).
CREATE INDEX IF NOT EXISTS idx_articles_title_lower_trgm ON articles USING gin (LOWER(title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_articles_excerpt_lower_trgm ON articles USING gin (LOWER(excerpt) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_articles_content_lower_trgm ON articles USING gin (LOWER(content) gin_trgm_ops);
