# Agent Conventions — dns-ha-agent

Read this before making any changes to this codebase. These are the invariants and conventions specific to this service. Violating them is more likely to break production DNS than help.

This directory is a service producer. Deployment roles, inventory, firewall
rules, real addresses and live rollout evidence belong to the consuming
infrastructure repository. The interface is the pinned image plus files in
`contract/`, not a shared Ansible checkout.

## Critical context

This service is **the sole authority for *local self-mutation* of this node's Tailscale route advertisement** (i.e., the only thing that runs `tailscale set --advertise-routes=...` on this host). External system-level fencing — the n8n workflow described in `../BLUEPRINT.md` Appendix E — may independently disable a node's *approved* route via the Tailscale API; that is a separate layer for fail-open remediation and does not violate this invariant. A bug in this service can:
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
3. **The YAML config is the source of truth for route ownership.** `tailscale.managed_routes` declares the routes this agent may add/remove. `tailscale.preserved_routes` declares routes that must remain advertised when the agent withdraws its managed routes. The agent never derives intent from `tailscale status`; it only observes actual state in order to reconcile it toward the config-derived desired state. **Do not introduce a persistent `RouteStateStore` for intended route ownership in v1.** Persisted route intent can become stale across config changes and crash/restart boundaries. The desired route set is recomputed from config and the current FSM decision on every reconciliation. A diagnostics-only "last observed" file is acceptable if it's clearly labelled non-authoritative.
4. **Startup MUST perform reconciliation** (`BLUEPRINT.md` §Operational Behaviour). A new instance never inherits "we were advertising" from a dead predecessor without going through STARTING → HEALTHY first.
5. **Only the explicit local-stack health probe queries the VIP it manages.** That probe intentionally tests the local `dnsdist → AGH → Unbound` path. No bootstrap, configuration, peer-monitoring, or ordinary resolver lookup may depend on the VIP; use local Unbound on `127.0.0.1:5335` or an explicit resolver IP for those instead.
6. **`HealthState` mutations use the atomic-ref-swap pattern.** Build an immutable snapshot, swap the reference, broadcast. Do not add `synchronized` to read paths — they must be lock-free.
7. **Probes are virtual threads.** No platform-thread pools for I/O. Concurrency is one virtual thread per probe target.
8. **Images identify their source and contract versions.** CI publishes a mutable browse pointer, while consumers pin an immutable digest and independently verify its OCI labels.
9. **Logs are JSON to stdout** with UTC timestamps, structured fields. No `System.out.println`. No `printStackTrace` in production code paths.
10. **Config is read once, at startup, from `/etc/dns-ha-agent/config.yaml`.** No hot reload. No environment-variable overrides. Restart the container to apply changes.

## Things that look like good ideas but break the contract

- **"Use Tailscale LocalAPI over the unix socket instead of subprocessing the CLI"** — Better in principle, but requires HTTP-over-unix-socket in Java which is non-trivial. v1 uses subprocess; revisit in v2 only if subprocess error handling becomes a real pain point.
- **"Add config hot-reload via WatchService"** — Adds a class of subtle bugs (mid-flight state during reload, atomic swap of complex config). Restart is fine.
- **"Skip startup reconciliation; if we're advertising, we're healthy"** — Wrong. A previous instance may have advertised based on a now-stale view; the new instance must re-validate from scratch.
- **"Make the agent depend on a cluster control plane"** — DNS must start and serve without an API server, scheduler, datastore, overlay or cluster DNS. The consumer may choose any supervisor that preserves those capabilities and mounts the host LocalAPI socket.
- **"Add a /metrics endpoint that also runs probes on demand"** — `/metrics` reads from `HealthState`, never triggers probes. Probes happen on the loop; metrics expose snapshots.
- **"Auto-withdraw a peer's route if we think it's unhealthy"** — No. Peers observe each other and surface alerts. They never mutate each other's Tailscale state. (See `BLUEPRINT.md` §Cross-Node Probing.)

## Build / test / push discipline

- `./gradlew check` must pass before any push to main. CI runs the same.
- Integration tests use Testcontainers to stand up a `FakeAgh` container; do not stub at the Java layer — exercise the real DNS path.
- `TailscaleClient` is the one production interface that is mocked in tests. Do not write tests that subprocess real `tailscale` on developer machines.
- New configuration fields require: (a) schema bump or compatible default, (b) updates to `contract/config.schema.v1.json`, the example and `BLUEPRINT.md`, and (c) producer tests. Consumer templates are updated separately after a release exists.

## Logging conventions

- Levels: DEBUG (per-probe, high frequency), INFO (state transitions, route mutations), WARN (probe failures below threshold, peer unreachable, startup-reconciliation findings), ERROR (route mutation failures), FATAL (forced exit triggered by `WatchdogThread`).
- Fields: every line carries `node` (from config) and `state` (current FSM state).
- Never log full configs at INFO (may leak peer URLs / tokens in future). DEBUG only.

## Versioning and release discipline

- Source `:latest` after CI is a pointer for browsing the registry — not a deploy target.
- Production consumers pin a `sha256:...` image digest and matching source revision in their own desired state.
- Bumping or reverting that consumer-owned pin performs rollout or rollback; this repository never carries the live value.
- Registry retention keeps the last 20 digests. Anything older requires a rebuild from source.

## When in doubt

If a change would touch the `TailscaleClient` interface, the FSM transitions, the state-file format, or the `/health` JSON schema — **stop and discuss**. These are the load-bearing contracts. Everything else is internal.
