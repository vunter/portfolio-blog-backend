-- ============================================
-- V21: Account deactivation and erasure support (Fase 3)
-- ============================================
-- comments kept authorship only as denormalized text (author_name/author_email):
-- the sole link to the account was the email string — PII in plain text on a
-- public-content table, and no structural trace of who commented. user_id is what
-- makes it possible to preserve "which user commented" AND null the email on
-- erasure (LGPD art. 18, VI): after erasure it points at a row that no longer
-- re-identifies anyone (art. 16, IV).

ALTER TABLE comments ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_comments_user_id ON comments(user_id) WHERE user_id IS NOT NULL;

-- Erasure nulls the author's email; the column has been NOT NULL since V1.
ALTER TABLE comments ALTER COLUMN author_email DROP NOT NULL;

-- Backfill existing comments to their authors. Idempotent by construction: the
-- user_id IS NULL guard makes a re-run (e.g. on a restore) a no-op. LOWER on
-- both sides because author_email is free text while users.email is lowercase
-- (case-insensitively unique since V18). Visitor comments stay NULL.
UPDATE comments c
   SET user_id = u.id
  FROM users u
 WHERE c.user_id IS NULL
   AND LOWER(c.author_email) = LOWER(u.email);

-- status carries the distinction active/deactivated/erased that the boolean
-- cannot express; `active` stays as what the login checks (critical path
-- untouched). deleted_at records when the account went off the air.
ALTER TABLE users ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
