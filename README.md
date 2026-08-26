# HA DNS for Homelab Tailnet

Design and rollout documentation for the highly-available DNS layer of the homelab tailnet. The goal is DNS that survives any single node failure and any single-region power outage, with AdGuard Home filtering active on all failover paths.

## Status

The design is complete. Amsterdam's Unbound + AdGuard Home backend plane is deployed and verified; the edge/VIP half remains gated on a controlled filter-sentinel domain and a canary edge deployment. `dns-ha-agent` is implemented through its local CI/CD pipeline, with live Ansible deployment and the final production gate still pending.

Forgejo at `git.battlenet.internal` is the working Git server and Woodpecker at `ci.battlenet.internal` is the CI system. GitHub is a periodic mirror only.

## Document Map

| Doc | Purpose |
|---|---|
| [`BLUEPRINT.md`](BLUEPRINT.md) | The design itself: goals, constraints, components, regional topology, failure matrix, config files. Read first if you need to understand or change the design. |
| [`PHASES.md`](PHASES.md) | Ordered implementation phases with per-phase acceptance criteria. Read first if you're rolling this out or picking up unfinished work. |
| [`PROGRESS.md`](PROGRESS.md) | Append-only execution log. Read this to see what's been done. |
| [`AGENTS.md`](AGENTS.md) | Conventions, invariants, and "do not touch" rules for agents (human or otherwise) working on this stack. Read first before making any changes. |

## Dependent service

The HA layer depends on a containerised health agent that probes the local DNS stack and mutates Tailscale route advertisements. That service has its own document set:

- [`dns-ha-agent/`](dns-ha-agent/) — service blueprint, implementation phases, agent conventions.

## Out of scope

- Per-LAN Pi-hole DNS (existing, unchanged by this work)
- k3s cluster networking (DNS must not depend on k3s)
- Public-internet DNS edges (deliberately excluded)
