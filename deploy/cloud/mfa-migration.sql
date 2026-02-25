-- MFA migration: run on production DB
-- Idempotent: safe to run multiple times

-- Add MFA columns to users table
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='users' AND column_name='mfa_enabled') THEN
        ALTER TABLE users ADD COLUMN mfa_enabled BOOLEAN DEFAULT FALSE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='users' AND column_name='mfa_preferred_method') THEN
        ALTER TABLE users ADD COLUMN mfa_preferred_method VARCHAR(20) DEFAULT 'TOTP';
    END IF;
END $$;

-- Create user_mfa_config table
CREATE TABLE IF NOT EXISTS user_mfa_config (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    method          VARCHAR(20) NOT NULL DEFAULT 'TOTP',
    secret_encrypted VARCHAR(512),
    verified        BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, method)
);

CREATE INDEX IF NOT EXISTS idx_user_mfa_config_user ON user_mfa_config(user_id);
CREATE INDEX IF NOT EXISTS idx_user_mfa_config_method ON user_mfa_config(user_id, method);
