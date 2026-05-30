# Agent Conventions — dns-ha-agent

Read this before making any changes to this codebase. These are the invariants and conventions specific to this service. Violating them is more likely to break production DNS than help.

## Critical context

This service is **the sole authority for advertising/withdrawing the HA DNS VIP route on Tailscale**. A bug here can:
- Leave a sick node advertising the VIP (clients route to a broken edge).
- Cause flapping advertisements (Tailscale failover thrashes; clients see intermittent DNS).
- Silently degrade filtering (the agent reports HEALTHY but the filter check is broken).

Treat the route-mutation path with the care you would give a hand-rolled distributed lock.

## Where to read first

| Task | Read |
|---|---|
| "Why does the design look like this?" | `BLUEPRINT.md` §Purpose, §Goals, §High-Level Architecture |
| "What state am I supposed to be in?" | `BLUEPRINT.md` §State Machine |
| "What does the YAML config look like?" | `BLUEPRINT.md` §Configuration Schema |
| "What does /health return?" | `BLUEPRINT.md` §API Surface |
| "I'm starting Phase N" | `PHASES.md` (find phase) |
| "What's been done already?" | `PROGRESS.md` |
| "How does this fit into the wider HA DNS?" | `../BLUEPRINT.md` Appendix C |

## Coding invariants — never violate

1. **All Tailscale mutations go through `TailscaleClient`.** Do not call `tailscale set` from anywhere else. Do not call it from a shell script. Do not call it from a separate sidecar. The interface is the choke point — every mutation must traverse it so it can be mocked in tests, logged consistently, and reasoned about.
2. **`TailscaleClient.advertiseRoutes()` and `withdrawRoutes()` are idempotent.** Calling either twice in a row with the same target state must be a no-op the second time. This is the contract callers rely on.
3. **The state file (`/var/lib/dns-ha-agent/state.json`) is the source of truth for intended route advertisements**, not `tailscale status` output. We never derive intent from observed state. We reconcile observed state toward intent.
4. **Startup MUST perform reconciliation** (`BLUEPRINT.md` §Operational Behaviour). A new instance never inherits "we were advertising" from a dead predecessor without going through STARTING → HEALTHY first.
5. **The agent's own DNS lookups never go through the VIP it manages.** Bootstrap circularity. Use the local Unbound on `127.0.0.1:5335` or explicit resolver IPs in probe code.
6. **`HealthState` mutations use the atomic-ref-swap pattern.** Build an immutable snapshot, swap the reference, broadcast. Do not add `synchronized` to read paths — they must be lock-free.
7. **Probes are virtual threads.** No platform-thread pools for I/O. Concurrency is one virtual thread per probe target.
8. **Image tags published to production are pinned by digest** (`@sha256:...`), not by name (`:latest`). CI publishes `:latest` as a browse pointer; ansible deploys by digest. Asserted in the ansible role.
9. **Logs are JSON to stdout** with UTC timestamps, structured fields. No `System.out.println`. No `printStackTrace` in production code paths.
10. **Config is read once, at startup, from `/etc/dns-ha-agent/config.yaml`.** No hot reload. No environment-variable overrides. Restart the container to apply changes.

## Things that look like good ideas but break the contract

- **"Use Tailscale LocalAPI over the unix socket instead of subprocessing the CLI"** — Better in principle, but requires HTTP-over-unix-socket in Java which is non-trivial. v1 uses subprocess; revisit in v2 only if subprocess error handling becomes a real pain point.
- **"Add config hot-reload via WatchService"** — Adds a class of subtle bugs (mid-flight state during reload, atomic swap of complex config). Restart is fine.
- **"Skip startup reconciliation; if we're advertising, we're healthy"** — Wrong. A previous instance may have advertised based on a now-stale view; the new instance must re-validate from scratch.
- **"Run the agent under k3s for consistency with other homelab services"** — DNS must not depend on k3s availability. The agent runs as plain Docker (compose) on the edge host.
- **"Add a /metrics endpoint that also runs probes on demand"** — `/metrics` reads from `HealthState`, never triggers probes. Probes happen on the loop; metrics expose snapshots.
- **"Auto-withdraw a peer's route if we think it's unhealthy"** — No. Peers observe each other and surface alerts. They never mutate each other's Tailscale state. (See `BLUEPRINT.md` §Cross-Node Probing.)

## Build / test / push discipline

- `./gradlew check` must pass before any push to main. CI runs the same.
- Integration tests use Testcontainers to stand up a `FakeAgh` container; do not stub at the Java layer — exercise the real DNS path.
- `TailscaleClient` is the one production interface that is mocked in tests. Do not write tests that subprocess real `tailscale` on developer machines.
- New configuration fields require: (a) schema bump or default value, (b) updates to `BLUEPRINT.md` §Configuration Schema, (c) updates to the ansible role's `defaults/main.yml` and `config.yaml.j2` template.

## Logging conventions

- Levels: DEBUG (per-probe, high frequency), INFO (state transitions, route mutations), WARN (probe failures below threshold, peer unreachable, startup-reconciliation findings), ERROR (route mutation failures), FATAL (forced exit triggered by `WatchdogThread`).
- Fields: every line carries `node` (from config) and `state` (current FSM state).
- Never log full configs at INFO (may leak peer URLs / tokens in future). DEBUG only.

## Versioning and release discipline

- Source `:latest` after CI is a pointer for browsing the registry — not a deploy target.
- Production deploys pin `dns_ha_agent_image_digest: sha256:...` in ansible group_vars.
- Bumping the digest is the deploy. Rolling back = reverting the digest var. See `BLUEPRINT.md` §Rolling out a new agent version.
- Registry retention keeps the last 20 digests. Anything older requires a rebuild from source.

## When in doubt

If a change would touch the `TailscaleClient` interface, the FSM transitions, the state-file format, or the `/health` JSON schema — **stop and discuss**. These are the load-bearing contracts. Everything else is internal.
