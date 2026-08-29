# dns-ha-agent

A self-contained, containerised Java service that decides whether a DNS edge node should advertise the HA DNS VIP to Tailscale, based on local stack health and peer reachability.

One instance runs on each consumer-declared edge. The same image runs
everywhere; behavior is parameterized by a versioned YAML configuration.

## Status

**Implementation and producer CI are complete.** The image exposes offline
config validation and route planning, embeds versioned machine-readable
contracts, and identifies its source revision. Production admission and canary
evidence belong to the consuming infrastructure repository.

The acceptance criteria for v1.0 are defined as the last phase in [`PHASES.md`](PHASES.md).

## Role in the larger system

This service is one component of the wider portable HA DNS design. For the
reason the shared route exists, what fail-closed means, and the consumer
acceptance contract, see the root [`README.md`](../README.md#system-contract).

Concretely, the agent is the **sole authority** for mutating Tailscale route
advertisements of the consumer-configured HA VIP. It never owns preserved
routes supplied by the deployment environment.

## Document Map

| Doc | Purpose |
|---|---|
| [`BLUEPRINT.md`](BLUEPRINT.md) | Design + spec: goals, state machine, config schema, API surface, implementation guidance and container contract. |
| [`PHASES.md`](PHASES.md) | Implementation phases in dependency order, each with acceptance criteria. Read first if picking up unfinished implementation work. |
| [`PROGRESS.md`](PROGRESS.md) | Append-only execution log. Read this to see what's been done. |
| [`AGENTS.md`](AGENTS.md) | Coding conventions, invariants, and "do not touch" rules for agents (human or otherwise) working on this codebase. Read first before making any changes. |

## Quick start

```bash
# Build
./gradlew shadowJar

# Build container image (multi-arch)
docker buildx build --platform linux/amd64,linux/arm64 -t dns-ha-agent:dev .

# Run locally with a test config
docker run --rm --network host \
  -v $(pwd)/config/example.yaml:/etc/dns-ha-agent/config.yaml \
  dns-ha-agent:dev

# Run tests
./gradlew check

# Run the Testcontainers integration suite (requires Docker)
./gradlew integrationTest

# Validate a consumer-rendered config without network or Tailscale access
java -jar build/libs/dns-ha-agent.jar --config config/example.yaml --check-config
java -jar build/libs/dns-ha-agent.jar --config config/example.yaml --plan-routes
```

## Repo layout

```
dns-ha-agent/
├── README.md          ← this file
├── AGENTS.md
├── BLUEPRINT.md
├── PHASES.md
├── PROGRESS.md
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
├── contract/          ← versioned producer/consumer schemas
├── config/example.yaml
└── src/{main,test}/java/io/homelab/dnsha/
```
