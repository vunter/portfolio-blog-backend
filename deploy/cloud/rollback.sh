#!/bin/bash
# Rollback to a previous deploy by commit SHA
# Usage: ./rollback.sh <commit-sha>
# Images must exist in GHCR (any previous successful deploy).
# Config bundle is pulled from GHCR (OCI) first, Nexus as fallback.
set -e

SHA=${1:?"Usage: ./rollback.sh <commit-sha>"}
source ~/.doppler_token

echo "Rolling back to SHA: $SHA"

# Pull the tagged images from GHCR
if ! docker pull ghcr.io/vunter/portfolio-blog-api:$SHA; then
  echo "API image not found for SHA $SHA — was this SHA ever deployed?"
  exit 1
fi
if ! docker pull ghcr.io/vunter/portfolio-blog-frontend:$SHA; then
  echo "Frontend image not found for SHA $SHA"
  exit 1
fi

# Verify image signatures if cosign is available (best-effort during rollback)
if command -v cosign >/dev/null 2>&1; then
  COSIGN_IDENTITY_REGEX="^https://github.com/vunter/portfolio-blog-backend/.github/workflows/deploy.yml@refs/(tags/v[0-9]+\\.[0-9]+\\.[0-9]+|heads/main)$"
  for IMG in \
    "ghcr.io/vunter/portfolio-blog-api:${SHA}" \
    "ghcr.io/vunter/portfolio-blog-frontend:${SHA}"; do
    if ! cosign verify \
        --certificate-identity-regexp "$COSIGN_IDENTITY_REGEX" \
        --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
        "$IMG" >/dev/null 2>&1; then
      echo "ERROR: signature verification failed for $IMG — refusing rollback"
      exit 1
    fi
  done
  echo "Image signatures verified"
fi

# Retag as latest
docker tag ghcr.io/vunter/portfolio-blog-api:$SHA portfolio-blog-api:latest
docker tag ghcr.io/vunter/portfolio-blog-frontend:$SHA portfolio-blog-frontend:latest

# Pull config bundle: GHCR first, Nexus fallback
CONFIG_OCI="ghcr.io/vunter/portfolio-blog-config"
NEXUS_URL="${NEXUS_URL:-https://vunter.tplinkdns.com/nexus}"
BUNDLE_NAME="portfolio-blog-config-${SHA}.tar.gz"
BUNDLE_URL="${NEXUS_URL}/repository/builds/portfolio-blog/config/${BUNDLE_NAME}"
BUNDLE_FILE="/tmp/config-rollback-${SHA}.tar.gz"
BUNDLE_FETCHED=0

if command -v oras >/dev/null 2>&1; then
  TMPDIR=$(mktemp -d)
  if (cd "$TMPDIR" && oras pull "${CONFIG_OCI}:${SHA}" >/dev/null 2>&1) && [ -f "$TMPDIR/$BUNDLE_NAME" ]; then
    mv "$TMPDIR/$BUNDLE_NAME" "$BUNDLE_FILE"
    BUNDLE_FETCHED=1
    echo "Pulled rollback config from GHCR"
  fi
  rm -rf "$TMPDIR"
fi

if [ "$BUNDLE_FETCHED" = "0" ]; then
  HTTP_CODE=$(curl -s -o "$BUNDLE_FILE" -w "%{http_code}" "$BUNDLE_URL" 2>/dev/null || echo "000")
  if [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 300 ]; then
    BUNDLE_FETCHED=1
    echo "Pulled rollback config from Nexus"
  fi
fi

if [ "$BUNDLE_FETCHED" = "1" ]; then
  echo "Restoring config bundle for SHA $SHA"
  cd ~/portfolio-blog
  tar xzf "$BUNDLE_FILE" --overwrite
  rm -f "$BUNDLE_FILE"
else
  echo "No config bundle found for SHA $SHA (GHCR + Nexus both failed) — using current config"
  rm -f "$BUNDLE_FILE"
fi

# Recreate containers with the rolled-back images
cd ~/portfolio-blog/deploy/cloud
doppler run -- docker compose -f docker-compose.cloud.yml up -d --force-recreate --remove-orphans

# Health check
echo "Waiting for API health..."
for i in $(seq 1 30); do
  if docker exec portfolio-blog-api wget -q --spider http://localhost:8080/actuator/health/readiness 2>/dev/null; then
    echo "API healthy after rollback"
    break
  fi
  if [ $i -eq 30 ]; then
    echo "API unhealthy after rollback — check logs"
    docker logs portfolio-blog-api --tail 30
    exit 1
  fi
  sleep 3
done

echo "$SHA" > ~/portfolio-blog/.last-deploy-sha
echo "Rollback complete — now running SHA: $SHA"
