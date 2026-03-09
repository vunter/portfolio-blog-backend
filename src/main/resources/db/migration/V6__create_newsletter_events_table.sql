-- Newsletter analytics tracking (LGPD/CAN-SPAM compliant)
-- Table may already exist with a different schema from manual creation.
-- This migration ensures the table and indexes exist.
CREATE TABLE IF NOT EXISTS newsletter_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscriber_id BIGINT,
    event_type VARCHAR(30) NOT NULL,
    email_subject VARCHAR(500),
    link_url VARCHAR(2000),
    user_agent VARCHAR(500),
    ip_address VARCHAR(45),
    created_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT fk_ne_subscriber FOREIGN KEY (subscriber_id) REFERENCES subscribers(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_newsletter_events_subscriber ON newsletter_events(subscriber_id);
CREATE INDEX IF NOT EXISTS idx_newsletter_events_type ON newsletter_events(event_type);
CREATE INDEX IF NOT EXISTS idx_newsletter_events_created ON newsletter_events(created_at DESC);
