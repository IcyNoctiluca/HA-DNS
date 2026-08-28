# HA DNS

Portable design and service code for fail-closed, highly available DNS over a
Tailscale network. The goal is DNS that survives an edge failure without ever
silently bypassing filtering.

## Status

`dns-ha-agent` is implemented and locally CI-tested. It now publishes an
offline config-validation and route-plan contract for infrastructure consumers.
Deployment status is intentionally not tracked here; Battlenet is the current
consumer and is authoritative for its live state.

GitHub (`IcyNoctiluca/HA-DNS`) is the canonical Git remote. Every push is
mirrored to Forgejo at `git.battlenet.internal`, where Woodpecker at
`ci.battlenet.internal` runs the producer CI pipeline.

## Document Map

| Doc | Purpose |
|---|---|
| [`BLUEPRINT.md`](BLUEPRINT.md) | The design itself: goals, constraints, components, regional topology, failure matrix, config files. Read first if you need to understand or change the design. |
| [`PHASES.md`](PHASES.md) | Ordered implementation phases with per-phase acceptance criteria. Read first if you're rolling this out or picking up unfinished work. |
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
