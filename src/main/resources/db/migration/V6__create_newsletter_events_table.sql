-- Newsletter analytics tracking (LGPD/CAN-SPAM compliant)
CREATE TABLE IF NOT EXISTS newsletter_events (
    id VARCHAR(36) PRIMARY KEY,
    subscriber_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    article_id VARCHAR(36),
    metadata TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT fk_newsletter_subscriber FOREIGN KEY (subscriber_id) REFERENCES subscribers(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ne_subscriber ON newsletter_events(subscriber_id);
CREATE INDEX IF NOT EXISTS idx_ne_event_type ON newsletter_events(event_type);
CREATE INDEX IF NOT EXISTS idx_ne_article ON newsletter_events(article_id) WHERE article_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ne_created ON newsletter_events(created_at);
