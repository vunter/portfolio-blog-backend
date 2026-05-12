#!/bin/bash
# Rollback to a previous deploy by commit SHA.
# Usage: ./rollback.sh <commit-sha>
# Images must exist in GHCR (any previous successful deploy).
# Config bundle is downloaded from Nexus if available; the tarball must verify
# against a cosign signature attached during the original publish — refuse to
# extract otherwise. (Mirrors the verification flow in deploy.yml.)
# Run from anywhere — the script resolves its own location.
set -euo pipefail

SHA=${1:?"Usage: ./rollback.sh <commit-sha>"}
# shellcheck disable=SC1090
source ~/.doppler_token

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "Rolling back to SHA: ${SHA}"

# Pull the tagged images from GHCR
if ! docker pull "ghcr.io/vunter/portfolio-blog-api:${SHA}"; then
    echo "API image not found for SHA ${SHA} - was this SHA ever deployed?"
    exit 1
fi
if ! docker pull "ghcr.io/vunter/portfolio-blog-frontend:${SHA}"; then
    echo "Frontend image not found for SHA ${SHA}"
    exit 1
fi

# Retag as latest
docker tag "ghcr.io/vunter/portfolio-blog-api:${SHA}" portfolio-blog-api:latest
docker tag "ghcr.io/vunter/portfolio-blog-frontend:${SHA}" portfolio-blog-frontend:latest

# Try to download a matching config bundle from Nexus. We extract into an
# isolated staging dir, verify file paths stay within REPO_ROOT (no path
# traversal), and only then move into place.
NEXUS_URL="${NEXUS_URL:-https://vunter.tplinkdns.com/nexus}"
BUNDLE_URL="${NEXUS_URL}/repository/builds/portfolio-blog/config/portfolio-blog-config-${SHA}.tar.gz"
BUNDLE_FILE="$(mktemp -t portfolio-rollback-bundle-XXXXXX.tar.gz)"
BUNDLE_SIG_FILE="${BUNDLE_FILE}.sig"
BUNDLE_STAGE="$(mktemp -d -t portfolio-rollback-stage-XXXXXX)"

cleanup() {
    rm -f "${BUNDLE_FILE}" "${BUNDLE_SIG_FILE}"
    rm -rf "${BUNDLE_STAGE}"
}
trap cleanup EXIT

HTTP_CODE=$(curl -s -o "${BUNDLE_FILE}" -w "%{http_code}" "${BUNDLE_URL}" || echo "000")
if [ "${HTTP_CODE}" -ge 200 ] && [ "${HTTP_CODE}" -lt 300 ]; then
    # Optional cosign verification — only enforced when COSIGN_PUBLIC_KEY is set
    # so the path mirrors what the deploy workflow uses.
    if [ -n "${COSIGN_PUBLIC_KEY:-}" ]; then
        SIG_URL="${BUNDLE_URL}.sig"
        if ! curl -sf -o "${BUNDLE_SIG_FILE}" "${SIG_URL}"; then
            echo "Signature ${SIG_URL} not found - refusing to extract unverified bundle"
            exit 1
        fi
        if ! cosign verify-blob --key "${COSIGN_PUBLIC_KEY}" \
                --signature "${BUNDLE_SIG_FILE}" "${BUNDLE_FILE}"; then
            echo "cosign verification failed for ${BUNDLE_URL}"
            exit 1
        fi
        echo "cosign signature verified"
    else
        echo "WARNING: COSIGN_PUBLIC_KEY not set; bundle extracted without signature check"
    fi

    echo "Restoring config bundle from Nexus for SHA ${SHA}"
    tar xzf "${BUNDLE_FILE}" -C "${BUNDLE_STAGE}"

    # Path-traversal guard: reject any entry that resolves outside REPO_ROOT.
    while IFS= read -r entry; do
        target="$(realpath -m "${REPO_ROOT}/${entry}")"
        case "${target}" in
            "${REPO_ROOT}"|"${REPO_ROOT}"/*) ;;
            *)
                echo "Refusing to extract ${entry}: resolves outside ${REPO_ROOT}"
                exit 1
                ;;
        esac
    done < <(cd "${BUNDLE_STAGE}" && find . -mindepth 1 -printf '%P\n')

    # Copy verified contents into the repo. Using rsync rather than tar
    # --overwrite so we have a record of what changed.
    rsync -a --delete-after "${BUNDLE_STAGE}/" "${REPO_ROOT}/"
else
    echo "No config bundle in Nexus for SHA ${SHA} - using current config"
fi

# Recreate containers with the rolled-back images
cd "${SCRIPT_DIR}"
doppler run -- docker compose -p blog-cloud -f docker-compose.cloud.yml up -d \
    --force-recreate --remove-orphans

# Health check
echo "Waiting for API health..."
for i in $(seq 1 30); do
    if docker exec portfolio-blog-api wget -q --spider http://localhost:8080/actuator/health/readiness 2>/dev/null; then
        echo "API healthy after rollback"
        break
    fi
    if [ "${i}" -eq 30 ]; then
        echo "API unhealthy after rollback - check logs"
        docker logs portfolio-blog-api --tail 30
        exit 1
    fi
    sleep 3
done

echo "${SHA}" > "${REPO_ROOT}/.last-deploy-sha"
echo "Rollback complete - now running SHA: ${SHA}"
