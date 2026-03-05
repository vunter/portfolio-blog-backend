-- Migration: Add unsubscribe_token column to subscribers table
-- Date: 2026-02-10
-- Reason: Newsletter unsubscribe flow uses a dedicated token for confirmed subscribers.
--         Previously this column was missing from the schema, causing R2DBC mapping errors.

ALTER TABLE subscribers ADD COLUMN IF NOT EXISTS unsubscribe_token VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_subscribers_unsubscribe_token
    ON subscribers(unsubscribe_token)
    WHERE unsubscribe_token IS NOT NULL;

-- Backfill existing confirmed subscribers with unsubscribe tokens
-- Run this in application code or manually:
-- UPDATE subscribers SET unsubscribe_token = gen_random_uuid()::text WHERE status = 'CONFIRMED' AND unsubscribe_token IS NULL;
