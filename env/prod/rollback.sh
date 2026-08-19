#!/bin/bash
# Rollback to a previous deploy by commit SHA.
# Usage: ./rollback.sh <commit-sha> [--no-verify]
# Images must exist in GHCR (any previous successful deploy).
# Config bundle is downloaded from Nexus if available; the tarball must verify
# against a cosign signature attached during the original publish — refuse to
# extract otherwise. (Mirrors the verification flow in deploy.yml.)
# --no-verify: explicit escape hatch that skips bundle signature verification
#              (e.g. COSIGN_PUBLIC_KEY genuinely unavailable during an incident).
# Run from anywhere — the script resolves its own location.
set -euo pipefail

NO_VERIFY=false
POSITIONAL=()
for arg in "$@"; do
    case "$arg" in
        --no-verify) NO_VERIFY=true ;;
        *) POSITIONAL+=("$arg") ;;
    esac
done
SHA=${POSITIONAL[0]:?"Usage: ./rollback.sh <commit-sha> [--no-verify]"}
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
    # Bundle signature verification is MANDATORY by default. If it cannot run
    # (COSIGN_PUBLIC_KEY unset), fail loudly instead of silently extracting an
    # unverified bundle — the operator must pass --no-verify to accept the risk.
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
    elif [ "${NO_VERIFY}" = "true" ]; then
        echo "WARNING: --no-verify passed; bundle extracted WITHOUT signature check"
    else
        echo "ERROR: COSIGN_PUBLIC_KEY not set - cannot verify the config bundle signature."
        echo "       Set COSIGN_PUBLIC_KEY to the bundle signing public key, or re-run"
        echo "       with --no-verify to explicitly accept an unverified bundle."
        exit 1
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

    # Copy verified contents into the repo. Sync each of the bundle's
    # top-level entries individually so --delete-after only prunes inside
    # directories the bundle actually ships (deleting at REPO_ROOT level would
    # wipe every file not present in the bundle). Runtime-provisioned files
    # that live inside shipped directories but are NOT part of the bundle are
    # excluded from both copy and deletion:
    #   .htpasswd        — manually provisioned, mounted by docker-compose.cloud.yml
    #   .last-deploy-sha — written by deploy.yml and this script
    #   playwright/      — sidecar build context, built by CI, not in the bundle
    RSYNC_PROTECT=(
        --exclude='.htpasswd'
        --exclude='.last-deploy-sha'
        --exclude='playwright/'
    )
    while IFS= read -r entry; do
        rsync -a --delete-after "${RSYNC_PROTECT[@]}" \
            "${BUNDLE_STAGE}/${entry}" "${REPO_ROOT}/"
    done < <(cd "${BUNDLE_STAGE}" && find . -mindepth 1 -maxdepth 1 -printf '%P\n')
else
    echo "No config bundle in Nexus for SHA ${SHA} - using current config"
fi

# Recreate containers with the rolled-back images.
# Project name MUST match the deploy workflow (deploy.yml exports
# COMPOSE_PROJECT_NAME=cloud) so we adopt the running stack and its
# cloud_* volumes instead of spinning up a parallel "blog-cloud" project.
cd "${SCRIPT_DIR}"
doppler run -- docker compose -p cloud -f docker-compose.cloud.yml up -d \
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
