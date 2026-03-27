-- V12: Add missing indexes for common query patterns.
-- Only adds indexes not already created in V1.

-- analytics_events: IP-based deduplication/rate limiting
CREATE INDEX IF NOT EXISTS idx_analytics_events_ip_type
    ON analytics_events(user_ip, event_type, created_at DESC);

-- newsletter_events: subscriber + type composite for tracking queries
CREATE INDEX IF NOT EXISTS idx_newsletter_events_sub_type
    ON newsletter_events(subscriber_id, event_type);

-- email_change_tokens: rate limiting by target email (used by EmailChangeService)
CREATE INDEX IF NOT EXISTS idx_email_change_tokens_new_email
    ON email_change_tokens(new_email, created_at DESC);

-- article_reviews: lookup by article + status
CREATE INDEX IF NOT EXISTS idx_article_reviews_article_status
    ON article_reviews(article_id, status);

-- reading_history: article-level queries (cascade deletes, popularity)
CREATE INDEX IF NOT EXISTS idx_reading_history_article
    ON reading_history(article_id);

-- ui_translations: key lookup (the most common query pattern)
CREATE INDEX IF NOT EXISTS idx_ui_translations_key
    ON ui_translations(translation_key);

-- password_reset_tokens: cleanup of expired tokens
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_expires_used
    ON password_reset_tokens(expires_at) WHERE used = FALSE;

-- media_assets: content type filtering
CREATE INDEX IF NOT EXISTS idx_media_assets_content_type
    ON media_assets(content_type);

-- user_social_accounts: lookup by user for profile display
CREATE INDEX IF NOT EXISTS idx_user_social_accounts_user_provider
    ON user_social_accounts(user_id, provider);
