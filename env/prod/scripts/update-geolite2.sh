#!/usr/bin/env bash
# Q13.12: Auto-update GeoLite2-Country database from MaxMind.
# Requires MAXMIND_LICENSE_KEY env variable (free account at maxmind.com).
# Install as cron: 0 3 * * 3 /path/to/update-geolite2.sh  (weekly on Wednesday at 3 AM)
set -euo pipefail

GEOLITE2_DIR="${GEOLITE2_DIR:-/home/vunter/geolite2}"
DB_FILE="$GEOLITE2_DIR/GeoLite2-Country.mmdb"
DOWNLOAD_URL="https://download.maxmind.com/app/geoip_download?edition_id=GeoLite2-Country&license_key=${MAXMIND_LICENSE_KEY}&suffix=tar.gz"
TEMP_DIR=$(mktemp -d)
LOG_FILE="/var/log/geolite2-update.log"

log() {
    echo "[$(date -Iseconds)] $1" | tee -a "$LOG_FILE"
}

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

if [ -z "${MAXMIND_LICENSE_KEY:-}" ]; then
    log "ERROR: MAXMIND_LICENSE_KEY not set. Get one at https://www.maxmind.com/en/geolite2/signup"
    exit 1
fi

mkdir -p "$GEOLITE2_DIR"

log "Downloading GeoLite2-Country database..."
if ! curl -sS -o "$TEMP_DIR/geolite2.tar.gz" "$DOWNLOAD_URL"; then
    log "ERROR: Download failed"
    exit 1
fi

log "Extracting..."
tar -xzf "$TEMP_DIR/geolite2.tar.gz" -C "$TEMP_DIR"

# Find the .mmdb file in the extracted directory
MMDB_FILE=$(find "$TEMP_DIR" -name "GeoLite2-Country.mmdb" -type f | head -1)
if [ -z "$MMDB_FILE" ]; then
    log "ERROR: GeoLite2-Country.mmdb not found in archive"
    exit 1
fi

# Only update if the file is different
if [ -f "$DB_FILE" ] && cmp -s "$MMDB_FILE" "$DB_FILE"; then
    log "Database is already up to date"
    exit 0
fi

# Atomic replace: copy to temp location, then move
cp "$MMDB_FILE" "$DB_FILE.new"
mv "$DB_FILE.new" "$DB_FILE"
log "Database updated: $(stat -c '%s bytes, modified %y' "$DB_FILE" 2>/dev/null || stat -f '%z bytes' "$DB_FILE")"

# Trigger reload in the running application (non-critical if it fails)
if curl -sS -f -X POST "http://localhost:8080/actuator/refresh" > /dev/null 2>&1; then
    log "Application notified to reload GeoIP database"
else
    log "WARN: Could not notify application (will pick up on next restart)"
fi

log "GeoLite2 update complete"
