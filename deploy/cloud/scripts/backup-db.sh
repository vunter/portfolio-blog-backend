#!/bin/bash
# =============================================================================
# Database Backup to Cloudflare R2 (PRIVATE bucket — no public access)
# Runs daily via cron, keeps 30 daily + weekly backups
# Usage: crontab -e → 0 3 * * * /home/vunter/portfolio-blog/scripts/backup-db.sh
# SECURITY:
#   - Backups go to 'catananti-backups' (private, S3 API auth only)
#     NOT 'catananti-assets' (public via cdn.catananti.dev)
#   - Dump is encrypted with gpg AES256 *before* upload, so R2 stores ciphertext.
#     The passphrase is BACKUP_ENCRYPTION_KEY in Doppler. Without it the
#     backups are unreadable even if R2 credentials leak.
# Restore:
#   doppler run -- sh -c 'gpg --batch --yes --passphrase "$BACKUP_ENCRYPTION_KEY" \
#     --decrypt blog_YYYYMMDD_HHMMSS.sql.gz.gpg | gunzip | psql -U blogadmin -d blog'
# =============================================================================
set -euo pipefail

BACKUP_DIR="/tmp/db-backups"
DATE=$(date +%Y%m%d_%H%M%S)
WEEKDAY=$(date +%u)
DUMP_FILE="blog_${DATE}.sql.gz"
BACKUP_FILE="${DUMP_FILE}.gpg"
LOG_FILE="/home/vunter/portfolio-blog/logs/backup.log"
R2_BACKUP_BUCKET="catananti-backups"

mkdir -p "$BACKUP_DIR" "$(dirname $LOG_FILE)"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"; }

log "Starting database backup..."

# Load secrets from Doppler (R2 creds + backup encryption passphrase)
source /home/vunter/.doppler_token
eval $(doppler run -- sh -c 'printf "R2_AK=%s R2_SK=%s R2_EP=%s BK_KEY=%s" \
  "$R2_ACCESS_KEY_ID" "$R2_SECRET_ACCESS_KEY" "$R2_ENDPOINT" "$BACKUP_ENCRYPTION_KEY"')

if [ -z "${BK_KEY:-}" ]; then
  log "ERROR: BACKUP_ENCRYPTION_KEY not set in Doppler — refusing to upload plaintext backup"
  exit 1
fi

# Dump → gzip → gpg AES256 (single pipeline; plaintext never lands on disk)
docker exec blog-postgres pg_dump -U blogadmin -d blog --no-owner --no-acl \
  | gzip \
  | gpg --batch --yes --symmetric --cipher-algo AES256 \
        --passphrase-fd 3 --pinentry-mode loopback \
        --output "${BACKUP_DIR}/${BACKUP_FILE}" \
        3<<<"$BK_KEY"

unset BK_KEY

FILESIZE=$(stat -c%s "${BACKUP_DIR}/${BACKUP_FILE}")
log "Dump+encrypt complete: ${BACKUP_FILE} (${FILESIZE} bytes)"

# Upload daily backup to PRIVATE bucket
R2_KEY="backups/db/daily/${BACKUP_FILE}"
curl -sf --aws-sigv4 "aws:amz:auto:s3" \
  --user "${R2_AK}:${R2_SK}" \
  -T "${BACKUP_DIR}/${BACKUP_FILE}" \
  "${R2_EP}/${R2_BACKUP_BUCKET}/${R2_KEY}"
log "Uploaded daily: ${R2_KEY} -> ${R2_BACKUP_BUCKET}"

# Upload weekly backup on Sundays
if [ "$WEEKDAY" -eq 7 ]; then
  WEEKLY_KEY="backups/db/weekly/${BACKUP_FILE}"
  curl -sf --aws-sigv4 "aws:amz:auto:s3" \
    --user "${R2_AK}:${R2_SK}" \
    -T "${BACKUP_DIR}/${BACKUP_FILE}" \
    "${R2_EP}/${R2_BACKUP_BUCKET}/${WEEKLY_KEY}"
  log "Uploaded weekly: ${WEEKLY_KEY} -> ${R2_BACKUP_BUCKET}"
fi

# Cleanup: remove local encrypted dumps older than 3 days
find "$BACKUP_DIR" -name "blog_*.sql.gz.gpg" -mtime +3 -delete 2>/dev/null || true
# Sweep any legacy plaintext .sql.gz files left from before encryption was enabled
find "$BACKUP_DIR" -name "blog_*.sql.gz" -mtime +3 -delete 2>/dev/null || true

# Cleanup: remove daily R2 backups older than 30 days
CUTOFF_DATE=$(date -d "-30 days" +%Y%m%d)
DAILY_KEYS=$(curl -sf --aws-sigv4 "aws:amz:auto:s3" \
  --user "${R2_AK}:${R2_SK}" \
  "${R2_EP}/${R2_BACKUP_BUCKET}?list-type=2&prefix=backups/db/daily/" \
  | grep -oP '<Key>[^<]+</Key>' | sed 's/<[^>]*>//g')

for KEY in $DAILY_KEYS; do
  KEY_DATE=$(echo "$KEY" | grep -oP '\d{8}' | head -1)
  if [ -n "$KEY_DATE" ] && [ "$KEY_DATE" -lt "$CUTOFF_DATE" ]; then
    curl -sf --aws-sigv4 "aws:amz:auto:s3" \
      --user "${R2_AK}:${R2_SK}" \
      -X DELETE "${R2_EP}/${R2_BACKUP_BUCKET}/${KEY}" && \
    log "Deleted old daily: ${KEY}"
  fi
done

log "Backup complete!"
