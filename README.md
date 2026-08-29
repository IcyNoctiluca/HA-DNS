# HA DNS

Portable design and service code for fail-closed, highly available DNS over a
Tailscale network. The goal is DNS that survives an edge failure without ever
silently bypassing filtering.

## Status

`dns-ha-agent` is implemented and producer-CI tested. Source revision
`6d19438bf99bbff38692b41bdc0a866e3c124951` produced immutable image digest
`sha256:61bdf3387050f2cacf1bd4f57ef91c527d5eaa5020a0eb26bb963f794dbf94f1`
in green Woodpecker pipeline 26. It publishes an offline config-validation and
route-plan contract for infrastructure consumers.
Deployment status is intentionally not tracked here; Battlenet is the current
consumer and is authoritative for its live state.

GitHub (`IcyNoctiluca/HA-DNS`) is the canonical Git remote. Every push is
mirrored to Forgejo at `git.battlenet.internal`, where Woodpecker at
`ci.battlenet.internal` runs the producer CI pipeline.

## Document Map

| Doc | Purpose |
|---|---|
| [`BLUEPRINT.md`](BLUEPRINT.md) | Portable system behavior: goals, invariants, failure model and consumer acceptance contract. |
| [`PHASES.md`](PHASES.md) | Producer release sequence and the retirement boundary for the former Battlenet rollout runbook. |
| [`PROGRESS.md`](PROGRESS.md) | Append-only execution log. Read this to see what's been done. |
| [`AGENTS.md`](AGENTS.md) | Conventions, invariants, and "do not touch" rules for agents (human or otherwise) working on this stack. Read first before making any changes. |
| [`REPOSITORY_BOUNDARY.md`](REPOSITORY_BOUNDARY.md) | The versioned artifact/API boundary between this producer and infrastructure consumers. |

## Dependent service

The HA layer depends on a containerised health agent that probes the local DNS stack and mutates Tailscale route advertisements. That service has its own document set:

- [`dns-ha-agent/`](dns-ha-agent/) — service blueprint, implementation phases, agent conventions.

## Out of scope

- Physical host placement and site topology
- Production VIPs, peer addresses, Tailscale tags and route approvals
- Resolver deployment, firewall, credentials, rollout and live evidence
