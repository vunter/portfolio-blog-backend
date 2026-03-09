-- GeoIP country-level geolocation for analytics (MaxMind GeoLite2)
ALTER TABLE analytics_events ADD COLUMN IF NOT EXISTS country_code VARCHAR(2);

CREATE INDEX IF NOT EXISTS idx_analytics_country ON analytics_events(country_code) WHERE country_code IS NOT NULL;
