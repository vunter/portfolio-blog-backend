#!/usr/bin/env bash
# Q5.14: Certbot deploy hook — reload nginx after certificate renewal.
# Install on production server:
#   sudo cp certbot-deploy-hook.sh /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
#   sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
#
# Certbot runs this ONLY when a certificate is actually renewed (not on every timer tick).
set -euo pipefail

LOG_TAG="certbot-deploy"

log() {
    logger -t "$LOG_TAG" "$1"
    echo "[$(date -Iseconds)] $1"
}

log "Certificate renewed for: ${RENEWED_DOMAINS:-unknown}"
log "Lineage: ${RENEWED_LINEAGE:-unknown}"

# Reload nginx inside the Docker container (picks up new certs from bind mount)
if docker exec blog-nginx nginx -t 2>/dev/null; then
    docker exec blog-nginx nginx -s reload
    log "nginx reloaded successfully"
else
    log "ERROR: nginx config test failed — NOT reloading"
    exit 1
fi
