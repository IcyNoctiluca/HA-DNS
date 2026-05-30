# dns-ha-agent

A self-contained, containerised Java service that decides whether a DNS edge node should advertise the HA DNS VIP to Tailscale, based on local stack health and peer reachability.

One instance runs on every edge node in the homelab's HA DNS topology. Same image everywhere; behaviour parameterised by a YAML config rendered per-node by ansible.

## Status

**Blueprint complete (v2). Implementation pending.**

The acceptance criteria for v1.0 are defined as the last phase in [`PHASES.md`](PHASES.md).

## Role in the larger system

This service is one component of the homelab HA DNS design. For the wider context — why the VIP exists, what fails closed means, how dnsdist / AGH / Unbound fit together — see [`../README.md`](../README.md).

Concretely, the agent is the **sole authority** for mutating Tailscale route advertisements of the HA VIP (`10.253.53.53/32`). Without it, the route would either stay advertised by dead nodes (causing client-visible DNS outages) or require manual intervention on every failure.

## Document Map

| Doc | Purpose |
|---|---|
| [`BLUEPRINT.md`](BLUEPRINT.md) | Design + spec: goals, state machine, config schema, API surface, implementation guidance, container & CI/CD strategy, ansible deployment, operational playbook. Read first if changing or implementing the service. |
| [`PHASES.md`](PHASES.md) | Implementation phases in dependency order, each with acceptance criteria. Read first if picking up unfinished implementation work. |
| [`PROGRESS.md`](PROGRESS.md) | Append-only execution log. Read this to see what's been done. |
| [`AGENTS.md`](AGENTS.md) | Coding conventions, invariants, and "do not touch" rules for agents (human or otherwise) working on this codebase. Read first before making any changes. |

## Quick start (when implementation is underway)

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
```

## Repo layout (planned)

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
├── .forgejo/workflows/build.yml
├── ansible/roles/dns-ha-agent/
├── config/example.yaml
└── src/{main,test}/java/io/homelab/dnsha/
```
