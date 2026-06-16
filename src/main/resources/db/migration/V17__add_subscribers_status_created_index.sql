-- ============================================
-- V17: Composite index on subscribers(status, created_at)
-- ============================================

-- be-data-perf-4: subscribers list queries (findAllConfirmed / findAllPaginated /
-- findByStatusPaginated) ORDER BY created_at DESC, optionally filtered by status,
-- but the subscribers table has no index on created_at. This composite index
-- supports both the status filter and the descending created_at ordering.
CREATE INDEX IF NOT EXISTS idx_subscribers_status_created
    ON subscribers(status, created_at DESC);
