#!/bin/sh
# Q5.10: GeoIP init — downloads MaxMind GeoLite2-Country into a shared volume.
# Replaces the hardcoded host path mount. Cached for 7 days.
#
# Entry point for the geoip-init compose service. Designed to be idempotent
# and fail-soft: if the download fails or the license key is missing, the app
# still starts (GeoIPService degrades gracefully without a database).
#
# Exit codes: always 0 — the app must start regardless of GeoIP availability.
set -u

DB_DIR="/data/geolite2"
DB_FILE="${DB_DIR}/GeoLite2-Country.mmdb"
STALE_SECONDS="${GEOIP_STALE_SECONDS:-604800}"  # 7 days
MAX_RETRIES=3

log() {
    printf '[geoip-init] %s\n' "$*"
}

if [ -z "${MAXMIND_LICENSE_KEY:-}" ]; then
    log "MAXMIND_LICENSE_KEY not set — GeoIP disabled (app starts without geolocation)"
    exit 0
fi

if [ -f "$DB_FILE" ]; then
    AGE=$(( $(date +%s) - $(stat -c %Y "$DB_FILE" 2>/dev/null || echo 0) ))
    if [ "$AGE" -lt "$STALE_SECONDS" ]; then
        log "database is fresh ($((AGE / 86400))d old), skipping download"
        exit 0
    fi
    log "database is stale ($((AGE / 86400))d old), re-downloading..."
fi

command -v curl >/dev/null 2>&1 || apk add --no-cache curl >/dev/null 2>&1

mkdir -p "$DB_DIR"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

BASE_URL="https://download.maxmind.com/app/geoip_download"
ARCHIVE="${TMP}/geolite2.tar.gz"
SHA_FILE="${TMP}/geolite2.tar.gz.sha256"

download() {
    local url="$1" out="$2"
    i=0
    while [ "$i" -lt "$MAX_RETRIES" ]; do
        i=$((i + 1))
        if curl -sS -f -o "$out" "$url"; then
            return 0
        fi
        log "download attempt $i/$MAX_RETRIES failed, retrying..."
        sleep 2
    done
    return 1
}

if ! download "${BASE_URL}?edition_id=GeoLite2-Country&license_key=${MAXMIND_LICENSE_KEY}&suffix=tar.gz" "$ARCHIVE"; then
    log "WARNING: archive download failed after ${MAX_RETRIES} attempts — app starts without geolocation"
    exit 0
fi

if ! download "${BASE_URL}?edition_id=GeoLite2-Country&license_key=${MAXMIND_LICENSE_KEY}&suffix=tar.gz.sha256" "$SHA_FILE"; then
    log "WARNING: SHA256 download failed — refusing to install unverified archive, app starts without geolocation"
    exit 0
fi
# MaxMind SHA file format: "<sha256>  GeoLite2-Country_YYYYMMDD.tar.gz"
EXPECTED=$(awk '{print $1}' "$SHA_FILE")
ACTUAL=$(sha256sum "$ARCHIVE" | awk '{print $1}')
if [ "$EXPECTED" != "$ACTUAL" ]; then
    log "WARNING: SHA256 mismatch (expected=$EXPECTED actual=$ACTUAL) — refusing to install, app starts without geolocation"
    exit 0
fi
log "SHA256 verified"

tar -xzf "$ARCHIVE" -C "$TMP"
MMDB=$(find "$TMP" -name 'GeoLite2-Country.mmdb' -type f | head -1)
if [ -z "$MMDB" ]; then
    log "WARNING: GeoLite2-Country.mmdb not found in archive"
    exit 0
fi

# Atomic replace so readers never see a half-written file
cp "$MMDB" "${DB_FILE}.tmp"
mv "${DB_FILE}.tmp" "$DB_FILE"
log "database updated ($(stat -c %s "$DB_FILE") bytes)"
