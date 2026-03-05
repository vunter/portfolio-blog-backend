#!/bin/bash
# =============================================================================
# Database Backup to Cloudflare R2
# Runs daily via cron, keeps 30 daily + 12 weekly backups
# Usage: crontab -e → 0 3 * * * /home/vunter/portfolio-blog/scripts/backup-db.sh
# =============================================================================
set -euo pipefail

BACKUP_DIR="/tmp/db-backups"
DATE=$(date +%Y%m%d_%H%M%S)
WEEKDAY=$(date +%u)
BACKUP_FILE="blog_${DATE}.sql.gz"
LOG_FILE="/home/vunter/portfolio-blog/logs/backup.log"

mkdir -p "$BACKUP_DIR" "$(dirname $LOG_FILE)"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"; }

log "Starting database backup..."

# Dump database from postgres container
docker exec blog-postgres pg_dump -U blogadmin -d blog --no-owner --no-acl \
  | gzip > "${BACKUP_DIR}/${BACKUP_FILE}"

FILESIZE=$(stat -c%s "${BACKUP_DIR}/${BACKUP_FILE}")
log "Dump complete: ${BACKUP_FILE} (${FILESIZE} bytes)"

# Load R2 credentials from Doppler
source /home/vunter/.doppler_token
eval $(doppler run -- sh -c 'echo "R2_AK=$R2_ACCESS_KEY_ID R2_SK=$R2_SECRET_ACCESS_KEY R2_EP=$R2_ENDPOINT R2_BK=$R2_BUCKET"')

# Upload daily backup
R2_KEY="backups/db/daily/${BACKUP_FILE}"
curl -sf --aws-sigv4 "aws:amz:auto:s3" \
  --user "${R2_AK}:${R2_SK}" \
  -T "${BACKUP_DIR}/${BACKUP_FILE}" \
  "${R2_EP}/${R2_BK}/${R2_KEY}"
log "Uploaded daily: ${R2_KEY}"

# Upload weekly backup on Sundays
if [ "$WEEKDAY" -eq 7 ]; then
  WEEKLY_KEY="backups/db/weekly/${BACKUP_FILE}"
  curl -sf --aws-sigv4 "aws:amz:auto:s3" \
    --user "${R2_AK}:${R2_SK}" \
    -T "${BACKUP_DIR}/${BACKUP_FILE}" \
    "${R2_EP}/${R2_BK}/${WEEKLY_KEY}"
  log "Uploaded weekly: ${WEEKLY_KEY}"
fi

# Cleanup: remove local dumps older than 3 days
find "$BACKUP_DIR" -name "blog_*.sql.gz" -mtime +3 -delete 2>/dev/null || true

# Cleanup: remove daily R2 backups older than 30 days
CUTOFF_DATE=$(date -d "-30 days" +%Y%m%d)
DAILY_KEYS=$(curl -sf --aws-sigv4 "aws:amz:auto:s3" \
  --user "${R2_AK}:${R2_SK}" \
  "${R2_EP}/${R2_BK}?list-type=2&prefix=backups/db/daily/" \
  | grep -oP '<Key>[^<]+</Key>' | sed 's/<[^>]*>//g')

for KEY in $DAILY_KEYS; do
  KEY_DATE=$(echo "$KEY" | grep -oP '\d{8}' | head -1)
  if [ -n "$KEY_DATE" ] && [ "$KEY_DATE" -lt "$CUTOFF_DATE" ]; then
    curl -sf --aws-sigv4 "aws:amz:auto:s3" \
      --user "${R2_AK}:${R2_SK}" \
      -X DELETE "${R2_EP}/${R2_BK}/${KEY}" && \
    log "Deleted old daily: ${KEY}"
  fi
done

log "Backup complete!"
