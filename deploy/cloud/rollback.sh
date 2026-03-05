#!/bin/bash
# Rollback to a previous deploy by commit SHA
# Usage: ./rollback.sh <commit-sha>
# Images must exist in GHCR (any previous successful deploy)
set -e

SHA=${1:?"Usage: ./rollback.sh <commit-sha>"}
source ~/.doppler_token

echo "🔄 Rolling back to SHA: $SHA"

# Pull the tagged images from GHCR
if ! docker pull ghcr.io/vunter/portfolio-blog-api:$SHA; then
  echo "❌ API image not found for SHA $SHA — was this SHA ever deployed?"
  exit 1
fi
if ! docker pull ghcr.io/vunter/portfolio-blog-frontend:$SHA; then
  echo "❌ Frontend image not found for SHA $SHA"
  exit 1
fi

# Retag as latest
docker tag ghcr.io/vunter/portfolio-blog-api:$SHA portfolio-blog-api:latest
docker tag ghcr.io/vunter/portfolio-blog-frontend:$SHA portfolio-blog-frontend:latest

# Recreate containers with the rolled-back images
cd ~/portfolio-blog/deploy/cloud
doppler run -- docker compose -f docker-compose.cloud.yml up -d --force-recreate --remove-orphans

# Health check
echo "Waiting for API health..."
for i in $(seq 1 30); do
  if docker exec portfolio-blog-api wget -q --spider http://localhost:8080/actuator/health/readiness 2>/dev/null; then
    echo "✅ API healthy after rollback"
    break
  fi
  if [ $i -eq 30 ]; then
    echo "❌ API unhealthy after rollback — check logs"
    docker logs portfolio-blog-api --tail 30
    exit 1
  fi
  sleep 3
done

echo "$SHA" > ~/portfolio-blog/.last-deploy-sha
echo "✅ Rollback complete — now running SHA: $SHA"
