#!/usr/bin/env bash
# Builds and runs the unit-test gate (./gradlew check) in a container that
# mirrors the Forgejo CI image, on your own machine (needs Docker Desktop
# or equivalent — this cannot run inside the agent's sandbox, see PROGRESS.md).
#
# Output is written to build/local-check.log so it can be read back after
# the run finishes, including by the agent (the DNS folder is shared).
#
# Usage:
#   ./run-local-check.sh              # unit tests only (fast, no docker socket needed)
#   ./run-local-check.sh integration  # Testcontainers-backed IT suite (needs Docker socket)
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "integration" ]; then
  docker compose -f docker-compose.local.yml run --rm integration-test
else
  docker compose -f docker-compose.local.yml run --rm check
fi
