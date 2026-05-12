#!/bin/bash
# =============================================================================
# Database Backup to Cloudflare R2 (PRIVATE bucket — no public access)
# Runs daily via cron, keeps 30 daily + weekly backups.
# Usage: crontab -e -> 0 3 * * * /home/vunter/portfolio-blog-backend/env/prod/scripts/backup-db.sh
# SECURITY:
#  - Dump file is created with mode 600 in a per-run temp dir.
#  - GPG-encrypted with BACKUP_GPG_RECIPIENT before leaving the box.
#  - Local copy is deleted immediately after upload completes.
#  - flock prevents overlapping runs from corrupting an upload.
# =============================================================================
set -euo pipefail

BACKUP_DIR="$(mktemp -d -t db-backup-XXXXXX)"
chmod 700 "$BACKUP_DIR"
DATE=$(date +%Y%m%d_%H%M%S)
WEEKDAY=$(date +%u)
BACKUP_FILE="blog_${DATE}.sql.gz.gpg"
PLAIN_FILE="blog_${DATE}.sql.gz"
LOG_FILE="/home/vunter/portfolio-blog/logs/backup.log"
LOCK_FILE="/var/lock/portfolio-blog-backup.lock"
R2_BACKUP_BUCKET="catananti-backups"

mkdir -p "$(dirname "$LOG_FILE")"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"; }

cleanup() {
    # Always remove the local temp directory, even on failure.
    rm -rf "$BACKUP_DIR"
}
trap cleanup EXIT

# Acquire an exclusive lock for the duration of this script. If another run is
# in progress (e.g. a slow upload still running when the next cron fires) we
# bail out rather than racing.
exec 200>"$LOCK_FILE"
if ! flock -n 200; then
    log "Another backup is already in progress (lock held). Exiting."
    exit 0
fi

log "Starting database backup..."

# Dump and gzip in a single pipe; pg_dump uses --no-owner so the restore
# target doesn't need a matching role inventory.
docker exec blog-postgres pg_dump -U blogadmin -d blog --no-owner --no-acl \
  | gzip -9 > "${BACKUP_DIR}/${PLAIN_FILE}"
chmod 600 "${BACKUP_DIR}/${PLAIN_FILE}"

FILESIZE=$(stat -c%s "${BACKUP_DIR}/${PLAIN_FILE}")
log "Dump complete: ${PLAIN_FILE} (${FILESIZE} bytes)"

# Client-side encryption. The recipient must be configured in the local gpg
# keyring; the script fails fast if BACKUP_GPG_RECIPIENT is empty so a
# misconfigured environment cannot silently ship plaintext to R2.
: "${BACKUP_GPG_RECIPIENT:?BACKUP_GPG_RECIPIENT must be set in the environment}"

gpg --batch --yes --trust-model always \
    --output "${BACKUP_DIR}/${BACKUP_FILE}" \
    --encrypt --recipient "${BACKUP_GPG_RECIPIENT}" \
    "${BACKUP_DIR}/${PLAIN_FILE}"
chmod 600 "${BACKUP_DIR}/${BACKUP_FILE}"
rm -f "${BACKUP_DIR}/${PLAIN_FILE}"

log "Encrypted dump created for recipient ${BACKUP_GPG_RECIPIENT}"

# Pull R2 credentials directly from Doppler. The previous `eval $(doppler run
# -- sh -c 'echo ...')` exposed secrets via `ps` and would surface in logs if
# `set -x` were ever enabled. `doppler run --` injects vars only for the child
# command, so the credentials never appear in the argv of any other process.
DOPPLER_PROJECT="${DOPPLER_PROJECT:-portfolio-blog}"
DOPPLER_CONFIG="${DOPPLER_CONFIG:-prod}"

doppler_curl() {
    doppler run --project "$DOPPLER_PROJECT" --config "$DOPPLER_CONFIG" -- \
        bash -c '
            curl -sSf --fail \
                --aws-sigv4 "aws:amz:auto:s3" \
                --user "$R2_ACCESS_KEY_ID:$R2_SECRET_ACCESS_KEY" \
                "$@"
        ' bash "$@"
}

# Upload daily backup to PRIVATE bucket
DAILY_OBJECT="backups/db/daily/${BACKUP_FILE}"
doppler_curl -T "${BACKUP_DIR}/${BACKUP_FILE}" "\${R2_ENDPOINT}/${R2_BACKUP_BUCKET}/${DAILY_OBJECT}"
log "Uploaded daily: ${DAILY_OBJECT} -> ${R2_BACKUP_BUCKET}"

# Upload weekly backup on Sundays
if [ "$WEEKDAY" -eq 7 ]; then
    WEEKLY_OBJECT="backups/db/weekly/${BACKUP_FILE}"
    doppler_curl -T "${BACKUP_DIR}/${BACKUP_FILE}" "\${R2_ENDPOINT}/${R2_BACKUP_BUCKET}/${WEEKLY_OBJECT}"
    log "Uploaded weekly: ${WEEKLY_OBJECT} -> ${R2_BACKUP_BUCKET}"
fi

# Local cleanup happens via the trap.

# Cleanup: remove daily R2 backups older than 30 days
CUTOFF_DATE=$(date -d "-30 days" +%Y%m%d)
DAILY_LIST=$(doppler_curl "\${R2_ENDPOINT}/${R2_BACKUP_BUCKET}?list-type=2&prefix=backups/db/daily/" \
              | grep -oP '<Key>[^<]+</Key>' | sed 's/<[^>]*>//g')

for OBJECT in $DAILY_LIST; do
    OBJECT_DATE=$(echo "$OBJECT" | grep -oP '\d{8}' | head -1)
    if [ -n "$OBJECT_DATE" ] && [ "$OBJECT_DATE" -lt "$CUTOFF_DATE" ]; then
        doppler_curl -X DELETE "\${R2_ENDPOINT}/${R2_BACKUP_BUCKET}/${OBJECT}" \
            && log "Deleted old daily: ${OBJECT}"
    fi
done

log "Backup complete!"
