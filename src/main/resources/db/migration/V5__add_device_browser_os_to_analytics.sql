-- Add device/browser/OS parsing columns to analytics_events
ALTER TABLE analytics_events ADD COLUMN IF NOT EXISTS device_type VARCHAR(50);
ALTER TABLE analytics_events ADD COLUMN IF NOT EXISTS browser_family VARCHAR(100);
ALTER TABLE analytics_events ADD COLUMN IF NOT EXISTS os_family VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_analytics_device_type ON analytics_events(device_type) WHERE device_type IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_analytics_browser ON analytics_events(browser_family) WHERE browser_family IS NOT NULL;
