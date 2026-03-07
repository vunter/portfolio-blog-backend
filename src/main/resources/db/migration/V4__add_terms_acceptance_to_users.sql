-- V4: Add terms/privacy acceptance tracking to users table.
-- Required for LGPD (Art. 8) and GDPR (Art. 7) compliance.
-- Tracks explicit consent at registration with version and timestamp.

ALTER TABLE users ADD COLUMN IF NOT EXISTS terms_accepted BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS terms_version VARCHAR(20) DEFAULT '1.0';
