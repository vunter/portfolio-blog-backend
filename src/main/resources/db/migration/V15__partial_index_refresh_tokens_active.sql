-- ============================================
-- V15: Partial index for active refresh tokens
-- ============================================

-- Q4.4: Partial index for hot-path queries on active refresh tokens.
-- findActiveByUserId queries WHERE user_id = ? AND revoked = false AND expires_at > NOW()
-- This partial index is much smaller than a full index (most tokens are revoked).
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_active
    ON refresh_tokens(user_id, expires_at DESC) WHERE NOT revoked;
