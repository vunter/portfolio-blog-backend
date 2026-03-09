-- Track newsletter analytics consent per subscriber (LGPD Art. 7-I)
ALTER TABLE subscribers ADD COLUMN IF NOT EXISTS analytics_consent BOOLEAN DEFAULT FALSE;
