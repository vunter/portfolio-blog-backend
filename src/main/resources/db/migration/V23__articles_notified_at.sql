-- ============================================
-- V23: Once-only subscriber notification claim (AUD19C-2)
-- ============================================
-- Publish side-effects used to be duplicated across PATCH publish, approveReview
-- and the publish scheduler, each with its own subscriber e-mail fan-out, while
-- PUT/bulk/create-published sent none. The fan-out is now centralized and gated
-- by an atomic compare-and-swap on notified_at:
--   UPDATE articles SET notified_at = NOW() WHERE id = :id AND notified_at IS NULL
-- Exactly one publish path per article ever wins the claim, so republishing an
-- article (unpublish -> publish, re-approve, bulk, scheduler overlap) can never
-- e-mail the subscriber list twice.

ALTER TABLE articles ADD COLUMN IF NOT EXISTS notified_at TIMESTAMP;

-- Backfill: every already-published article has (by definition of the old code
-- paths) either been announced already or predates announcements entirely.
-- Marking them notified prevents a full e-mail blast on their next republish.
-- Idempotent by construction: the IS NULL guard makes a re-run a no-op.
UPDATE articles
   SET notified_at = published_at
 WHERE notified_at IS NULL
   AND published_at IS NOT NULL;
