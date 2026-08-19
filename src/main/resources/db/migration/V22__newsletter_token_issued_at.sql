-- ============================================
-- V22: Separate token issuance time from subscriber creation time (AUD18-L6)
-- ============================================
-- created_at doubled as the confirmation-token issue time: isTokenExpired read
-- it, so re-subscribing overwrote created_at with NOW() and destroyed the real
-- signup date. token_issued_at carries the token lifecycle on its own column;
-- created_at goes back to meaning "when this subscriber first appeared" and is
-- never rewritten again.

ALTER TABLE subscribers ADD COLUMN IF NOT EXISTS token_issued_at TIMESTAMP;

-- Backfill: until now the token was always issued at whatever created_at says
-- (re-subscribes rewrote created_at, so the two were kept equal by construction).
-- Idempotent by construction: the IS NULL guard makes a re-run a no-op.
UPDATE subscribers
   SET token_issued_at = created_at
 WHERE token_issued_at IS NULL;
