#!/usr/bin/env bash
# Builds and runs the unit-test gate (./gradlew check) in a container that
# mirrors the Forgejo CI image, on your own machine (needs Docker installed
# — this cannot run inside the agent's sandbox, see PROGRESS.md).
#
# Uses plain `docker build`/`docker run` rather than `docker compose`, since
# `docker compose` isn't reliably present as a CLI subcommand across every
# Docker install (Compose v2 plugin missing, older CLI, non-Docker-Desktop
# setups, etc.) — this only depends on `docker` itself.
#
# Output is written to build/local-check.log (or local-integration-test.log)
# so it can be read back after the run finishes, including by the agent
# (the DNS folder is shared).
#
# Usage:
#   ./run-local-check.sh              # unit tests only (fast, no docker socket needed)
#   ./run-local-check.sh integration  # Testcontainers-backed IT suite (needs Docker socket)
set -euo pipefail
cd "$(dirname "$0")"

IMAGE_TAG="dns-ha-agent-build"
CACHE_VOLUME="dns-ha-agent-gradle-cache"

echo "--- building local dev image (Dockerfile.build) ---"
docker build -f Dockerfile.build -t "$IMAGE_TAG" .

docker volume create "$CACHE_VOLUME" >/dev/null

mkdir -p build

TASK="check"
LOGFILE="build/local-check.log"
EXTRA_MOUNTS=()

if [ "${1:-}" = "integration" ]; then
  TASK="integrationTest"
  LOGFILE="build/local-integration-test.log"
  EXTRA_MOUNTS=(-v /var/run/docker.sock:/var/run/docker.sock)
fi

echo "--- running ./gradlew --no-daemon ${TASK} ---"
docker run --rm \
  -v "$PWD":/workspace \
  -v "${CACHE_VOLUME}":/root/.gradle \
  "${EXTRA_MOUNTS[@]}" \
  -w /workspace \
  "$IMAGE_TAG" \
  bash -c "
    set -e
    if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
      echo '--- gradle-wrapper.jar missing, generating it ---'
      gradle wrapper --gradle-version 8.7 --no-daemon
    fi
    ./gradlew --no-daemon ${TASK}
  " 2>&1 | tee "$LOGFILE"
