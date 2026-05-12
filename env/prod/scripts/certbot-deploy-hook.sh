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

# Sanity check: the new fullchain.pem must still be valid for at least an
# hour. If it isn't, certbot or the renewal pipeline produced something
# broken and we must NOT swap it in (an expired cert would lock users out).
if [ -n "${RENEWED_LINEAGE:-}" ] && [ -f "${RENEWED_LINEAGE}/fullchain.pem" ]; then
    if ! openssl x509 -in "${RENEWED_LINEAGE}/fullchain.pem" -noout -checkend 3600; then
        log "ERROR: renewed certificate expires within 1 hour — refusing to reload"
        exit 1
    fi
    log "Certificate validity check passed"
fi

# Reload nginx inside the Docker container (picks up new certs from bind mount)
if docker exec blog-nginx nginx -t 2>/dev/null; then
    docker exec blog-nginx nginx -s reload
    log "nginx reloaded successfully"
else
    log "ERROR: nginx config test failed — NOT reloading"
    exit 1
fi
