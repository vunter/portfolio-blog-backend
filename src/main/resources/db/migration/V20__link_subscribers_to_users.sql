-- ============================================
-- V20: Link newsletter subscribers to user accounts
-- ============================================
-- The link only exists when BOTH sides proved ownership of the address:
-- users.email_verified = true AND subscribers.status = 'CONFIRMED'.
-- unlinked_by records who undid a link because only the holder's own refusal
-- ('USER') blocks automatic re-linking; admin intervention or account deletion
-- ('ADMIN', 'ACCOUNT_DELETED') must not.

ALTER TABLE subscribers ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE subscribers ADD COLUMN IF NOT EXISTS linked_at TIMESTAMP;
ALTER TABLE subscribers ADD COLUMN IF NOT EXISTS link_origin VARCHAR(32);
ALTER TABLE subscribers ADD COLUMN IF NOT EXISTS unlinked_at TIMESTAMP;
ALTER TABLE subscribers ADD COLUMN IF NOT EXISTS unlinked_by VARCHAR(16);

-- Partial unique index: enforces the 1:1 without limiting how many subscribers
-- have no account at all.
CREATE UNIQUE INDEX IF NOT EXISTS uq_subscribers_user_id
    ON subscribers (user_id) WHERE user_id IS NOT NULL;

-- Site-navigation analytics consent, persisted on the account so the choice
-- survives device changes. NULL means the holder never decided — distinct from
-- FALSE (refused), because a refusal must not be re-asked.
ALTER TABLE users ADD COLUMN IF NOT EXISTS analytics_consent BOOLEAN;
ALTER TABLE users ADD COLUMN IF NOT EXISTS analytics_consent_at TIMESTAMP;

-- Backfill for pairs that already exist on both sides. Idempotent by
-- construction: the user_id IS NULL guard makes a re-run (e.g. on a restore)
-- a no-op. Matching by LOWER(email) is unambiguous because V18 enforces
-- case-insensitive uniqueness on users.email.
UPDATE subscribers s
   SET user_id = u.id,
       linked_at = NOW(),
       link_origin = 'AUTO_BACKFILL',
       unlinked_at = NULL,
       unlinked_by = NULL
  FROM users u
 WHERE s.user_id IS NULL
   AND s.status = 'CONFIRMED'
   AND u.email_verified = true
   AND LOWER(u.email) = s.email
   AND s.unlinked_by IS DISTINCT FROM 'USER';
