# dns-ha-agent — Implementation Phases

> Ordered implementation plan with per-phase acceptance criteria. Each phase is a coherent slice of work that can be completed and verified before starting the next. Status of each phase lives in [`PROGRESS.md`](PROGRESS.md). Design source: [`BLUEPRINT.md`](BLUEPRINT.md).

## Overview

| # | Phase | Outcome |
|---|---|---|
| 0 | Project scaffolding | Repo + build + container builds successfully, smoke test passes |
| 1 | Core probe + single-node advertise | Agent probes local DNS, advertises VIP when healthy, withdraws when not |
| 2 | HTTP server + observability + BackendProbe | `/health` and `/metrics` endpoints live; JSON logging; per-AGH-backend filter probes feeding metrics |
| 3 | Peer monitoring + split-brain | Cross-edge peer health probing; PARTITIONED detection |
| 4 | Liveness + reconciliation | WatchdogThread + startup reconciliation hardening |
| 5 | CI/CD + digest pinning | Forgejo Actions builds + multi-arch + digest published as artifact |
| 6 | Ansible role | Idempotent deploy role with digest assertion |
| 7 | v1.0 acceptance (gate to production) | Full v1.0 acceptance criteria passed |

The phases are designed so each one can be merged to main and yield a working (if incomplete) agent. No phase requires changes to a previous phase's contracts.

---

## Phase 0 — Project scaffolding

**Goal**: get the empty repo to "container builds, runs, prints 'hello' and exits 0."

### Scope

- Initialise Forgejo repo: `dns-ha-agent`.
- Add Gradle 8.x with Kotlin DSL, Java 21 toolchain, shadow plugin for fat jar.
- Add `App.java` stub that loads no config, prints a banner, exits.
- Add multi-stage Dockerfile (`BLUEPRINT.md` §11).
- Add `.forgejo/workflows/build.yml` skeleton: lint, build, smoke-test.
- Commit `README.md`, `AGENTS.md`, `BLUEPRINT.md`, `PHASES.md`, `PROGRESS.md` (this doc set).

### Acceptance criteria

- [ ] `./gradlew shadowJar` produces `build/libs/dns-ha-agent.jar`.
- [ ] `docker build .` produces a runnable image.
- [ ] `docker run --rm <image>` prints banner and exits 0.
- [ ] Forgejo Actions CI runs on push to main and passes.

---

## Phase 1 — Core probe + single-node advertise

**Goal**: minimum viable agent. Probes the local VIP, advertises when healthy, withdraws when not. No HTTP, no peers, no metrics.

### Scope

- `Config` record + `ConfigLoader` (Jackson YAML). Includes `tailscale.managed_routes`, `tailscale.preserved_routes`.
- `DnsProbe` — dual-check using dnsjava (resolve + filter).
- `HealthState` with FSM (STARTING, HEALTHY, UNHEALTHY).
- `FsmReducer` (pure state-transition function).
- `Decider` (pure decision function: FSM state + config → ADVERTISE / WITHDRAW).
- `TailscaleClient` interface + `CliTailscaleClient` (subprocess).
- `Reconciler` — the **only** component that calls `TailscaleClient` for mutation. Computes desired = `managed_routes ∪ preserved_routes` (or just `preserved_routes` on WITHDRAW), compares against `currentlyAdvertised()`, mutates only on drift. Sets `dns_ha_agent_fail_open` if WITHDRAW didn't take effect.
- **No `RouteStateStore` and no persisted route-intent file.** The config is the source of truth. A diagnostics-only "last observed" file may exist later but must be clearly non-authoritative.
- `ProbeLoop` running in a virtual thread.
- `App` wires it together: probe → FsmReducer → Decider → Reconciler.
- Logback config: JSON to stdout.

### Acceptance criteria

- [ ] Unit tests for `ConfigLoader` (valid YAML parses; invalid → exit 78; missing `managed_routes` → exit 78).
- [ ] Unit tests for `FsmReducer` (every (previous FSM, probe result) input → correct next FSM state; threshold counters; empty peer list short-circuits partition transitions).
- [ ] Unit tests for `Decider` (every FSM state → correct decision; force-withdraw file overrides everything except SHUTTING_DOWN).
- [ ] Unit tests for `HealthState` (counters, threshold transitions).
- [ ] Unit tests for `CliTailscaleClient` (subprocess args correct; non-zero exit → exception).
- [ ] Unit test for `Reconciler`: ADVERTISE → calls `TailscaleClient.advertiseRoutes(managed_routes ∪ preserved_routes)`.
- [ ] Unit test for `Reconciler`: WITHDRAW → calls `TailscaleClient.advertiseRoutes(preserved_routes)` only.
- [ ] Unit test for `Reconciler`: actual already matches desired → no `TailscaleClient` call (idempotent).
- [ ] Unit test for `Reconciler`: observed Tailscale routes are NEVER treated as desired state; they're only used to decide whether a mutation is needed.
- [ ] Unit test for `Reconciler`: changing `managed_routes` in config changes the desired route set without requiring any persisted-state migration.
- [ ] Unit test for `Reconciler`: WITHDRAW + observed managed-route still advertised after mutation → `dns_ha_agent_fail_open = 1`.
- [ ] Integration test (Testcontainers + FakeAgh): agent transitions STARTING → HEALTHY and calls `advertise` within 10s.
- [ ] Integration test: FakeAgh returns real IP for blocked name → agent transitions to UNHEALTHY and calls `withdraw`.
- [ ] No HTTP server, no metrics — these are Phase 2. Agent is fully functional via logs alone.

---

## Phase 2 — HTTP server + observability + BackendProbe

**Goal**: peers and humans can see what the agent thinks. JSON `/health`, Prometheus `/metrics`. Per-AGH-backend filter probes are wired in here because their results live in `/health` and `/metrics`.

### Scope

- Jersey + Grizzly server, bound to `http.bind_address:http.port` (the node's Tailscale IP in production — NOT loopback).
- `HealthResource` (`/health`) returning `HealthSnapshot` JSON.
- `MetricsResource` (`/metrics`) returning Prometheus format.
- Prometheus simpleclient instrumentation throughout existing code.
- `HealthSnapshot` is an immutable record built from `HealthState.snapshot()`.
- JSON schema matches `BLUEPRINT.md` §6 exactly, **including the `backends` array** populated by `BackendProbe`.
- `BackendProbe` — same dual probe (resolve + filter sentinel) as `DnsProbe`, but targeted directly at each configured AGH backend (`agh-ams-01`, `agh-iom-01`, etc.) on the slower `backend_probe.interval` cadence (default 30s). One virtual thread per backend.
- Backend probe results are written into `HealthState` so they appear in `/health.backends[].resolve_ok / filter_ok` and as `dns_ha_agent_backend_resolve_ok{backend=…}` / `dns_ha_agent_backend_filter_ok{backend=…}` / `dns_ha_agent_backend_probe_total{backend=…,result=…}` in `/metrics`.
- **Important non-effect**: BackendProbe failures DO NOT influence the FSM or the route-mutation decision. They only emit metrics. Only `DnsProbe` (the active-path probe via the VIP) drives state transitions. This keeps "a backup backend has broken filtering" as an alert, not a withdraw trigger.

### Acceptance criteria

Tests run against the node's Tailscale IP (the agent's configured `http.bind_address`), NOT `localhost` — the agent does not bind to the loopback in production. Local development can override `http.bind_address` to `127.0.0.1` if needed.

- [ ] `curl http://<tailscale_ip>:8091/health` returns 200 + valid JSON matching schema (run from the edge itself or from another tailnet member with the `tag:dns-edge` or `tag:dns-fencer` tag).
- [ ] `curl http://<tailscale_ip>:8091/metrics` returns 200 + valid Prometheus format.
- [ ] All metrics listed in `BLUEPRINT.md` §6 are present and update correctly.
- [ ] Integration test: state transitions are reflected in subsequent `/health` responses within one probe interval.
- [ ] JVM metrics (heap, GC, threads) are auto-exported.
- [ ] `/health` returns HEALTHY/UNHEALTHY/STARTING correctly in tests.
- [ ] `curl http://localhost:8091/health` from inside the agent container FAILS (the server is not bound to loopback in production). If you need loopback access for local dev, set `http.bind_address: 127.0.0.1` in the config override — this must not be the production deployment value.
- [ ] **BackendProbe runs**: with two configured backends (one healthy local FakeAGH and one healthy remote FakeAGH), `/health.backends[]` contains both entries with `resolve_ok: true` and `filter_ok: true` within one `backend_probe.interval`.
- [ ] **BackendProbe metrics**: `dns_ha_agent_backend_resolve_ok` and `dns_ha_agent_backend_filter_ok` gauges exist with one labelled series per configured backend.
- [ ] **BackendProbe does not drive FSM**: break filtering on a backup backend only (e.g., configure the FakeAGH to return a real IP for the sentinel); within one probe interval, `dns_ha_agent_backend_filter_ok{backend=…} == 0`, but the agent's FSM stays HEALTHY and the VIP remains advertised.

---

## Phase 3 — Peer monitoring + asymmetric-inbound partition detection

**Goal**: peer awareness and best-effort PARTITIONED self-withdrawal for the asymmetric-inbound case. PARTITIONED is NOT a full quorum/split-brain system — see `BLUEPRINT.md` §4. The load-bearing partition protection is Layer 0 (Tailscale node-level failover) plus Layer 2 (n8n external fencing).

### Scope

- `PeerMonitor` — one virtual thread per peer, polls `/health`.
- `PeerState` — per-peer tracking (last_seen, reachable, reported_state, reports_me_unreachable, first_reported_unreachable_ts).
- Add PARTITIONED state to FSM.
- Wire `PeerMonitor` observations into `HealthState`.
- Update `Decider` to apply partition logic (see `BLUEPRINT.md` §7).
- `unreachable_peers` field in `/health` response.

### Acceptance criteria

- [ ] Empty peer list disables peer monitoring entirely (single-node region behaviour). Verified by unit test.
- [ ] With ≥2 peers, agent's `/health` reflects peer reachability accurately.
- [ ] Integration test (two-agent Testcontainers setup): simulate **asymmetric inbound** failure on one container (block inbound to its `:8091` from the peer, leave outbound peer-poll intact); after `peer_unreachable_threshold` + grace, that agent reads itself in the peer's `unreachable_peers` list, transitions to PARTITIONED and calls `withdraw`.
- [ ] Integration test: simulate **fully isolated** node (block both inbound and outbound traffic to peer `:8091`). Confirm the agent stays HEALTHY (local probes pass, no quorum to declare partition). This is the explicit not-covered case, validated as not-covered.
- [ ] Restoring network: partitioned agent transitions back to HEALTHY and re-advertises within 30s.
- [ ] Peer config update (add/remove peers) requires container restart — verified, not hot-reloaded.

---

## Phase 4 — Liveness + reconciliation

**Goal**: harden the safety properties. Startup reconciliation prevents stale advertisements; `WatchdogThread` prevents silent hangs.

### Scope

- Startup reconciliation (`BLUEPRINT.md` §8 Startup step 4): on boot, call `currentlyAdvertised()`; if VIP present, explicitly withdraw before entering STARTING.
- `WatchdogThread` — wakes every 10s, verifies probe loop reported within 30s, forces `System.exit(70)` on hang.
- `Thread.setDefaultUncaughtExceptionHandler` routes virtual-thread exceptions to logging + exit.
- HTTP server self-monitor: if a `/health` GET against the **configured `http.bind_address`** (i.e. the node's Tailscale IP in production, not `localhost`) fails within 5s, same self-exit path. Probing loopback would falsely report the server down because the agent does not bind to the loopback.
- Graceful SIGTERM handler: transition to SHUTTING_DOWN, withdraw, drain HTTP, flush, exit 0.

### Acceptance criteria

- [ ] Integration test: pre-advertise VIP manually (`tailscale set --advertise-routes=10.253.53.53/32`), then start agent. Agent's first action is to withdraw; it then goes through STARTING → HEALTHY before re-advertising. Log line `found stale VIP advertisement at startup` is present.
- [ ] Integration test: inject `Thread.sleep(Long.MAX_VALUE)` into probe loop in a test build. Within 30s the `WatchdogThread` detects and the process exits non-zero. Restart policy then restarts.
- [ ] Integration test: send SIGTERM. Agent withdraws within 5s and exits 0.
- [ ] Integration test: SIGKILL the agent. Restart policy restarts it. New instance performs reconciliation (withdraws stale VIP) before re-validating.
- [ ] **HTTP self-monitor uses `http.bind_address`**: unit/integration test that the self-monitor's `/health` probe is constructed against `config.http.bind_address`, not `localhost` / `127.0.0.1`. Pointing the test agent at a bind address that intentionally returns 5xx for `/health` triggers self-exit within ~5s; pointing it at a healthy bind address does not.
- [ ] No code path advertises without going through STARTING → HEALTHY.

---

## Phase 5 — CI/CD + digest pinning

**Goal**: production-grade build pipeline. Multi-arch images, smoke test by digest, digest exposed as build output.

### Scope

- Forgejo Actions workflow `BLUEPRINT.md` §12 fully implemented.
- Multi-arch via `docker buildx`: `linux/amd64`, `linux/arm64`.
- `:latest` tag published as browse pointer.
- Digest captured as job output (`outputs.digest`) AND as artifact (`digest.txt`).
- Smoke test job pulls **by digest**, not by `:latest`.
- Registry secrets configured in Forgejo.

### Acceptance criteria

- [ ] CI runs on every push to main; tests pass.
- [ ] Image is pushed to `forgejo.homelab.internal/homelab/dns-ha-agent` for both architectures.
- [ ] Build output includes the digest, accessible as `${{ needs.build-and-push.outputs.digest }}` in downstream jobs.
- [ ] Smoke test successfully pulls and runs the image by digest, asserts `/health` returns 200.
- [ ] `:latest` tag exists in registry but is treated as informational only.

---

## Phase 6 — Ansible role

**Goal**: declarative deploy. Idempotent. Refuses to run without a pinned digest.

### Scope

- `ansible/roles/dns-ha-agent/` per `BLUEPRINT.md` §14.
- `defaults/main.yml` with `dns_ha_agent_image_digest` empty by default.
- `tasks/main.yml` includes the digest assertion as first step.
- `templates/config.yaml.j2` renders the full config schema.
- `templates/docker-compose.yml.j2` renders the image as `repo@digest`.
- `handlers/main.yml` restarts the container on config or compose changes.
- Example `host_vars/dns-ams-01.yml` committed to repo as documentation.

### Acceptance criteria

- [ ] Running the role with `dns_ha_agent_image_digest=""` fails with the assertion message.
- [ ] Running with a valid digest deploys the container; `/health` returns 200 within 30s.
- [ ] Re-running the role with the same digest is a no-op (idempotent).
- [ ] Changing config (e.g., a peer's IP) and re-running triggers a restart of the container.
- [ ] Changing the digest and re-running pulls the new image and restarts.

---

## Phase 7 — v1.0 acceptance (gate to production)

**Goal**: prove the agent is ready to manage real production DNS. This is the gate before [`../PHASES.md`](../PHASES.md) Phase 8 (production cutover) can be advanced.

### Acceptance criteria (full v1.0 set)

- [ ] All unit and integration tests pass in CI.
- [ ] Smoke test in CI pulls (by digest) and runs the image successfully.
- [ ] Multi-arch image (`amd64`, `arm64`) published to `forgejo.homelab.internal/homelab/dns-ha-agent`; digest captured as CI build output.
- [ ] Ansible role asserts digest is set; refuses to deploy without one.
- [ ] Ansible role deploys cleanly to `dns-ams-01` (first canary).
- [ ] **Tailscale socket reachable from container**: after the container starts, `docker exec dns-ha-agent tailscale status` succeeds (proves the LocalAPI socket bind mount works). The compose file must explicitly mount `/var/run/tailscale/tailscaled.sock` — host networking alone does not expose it.
- [ ] Agent transitions STARTING → HEALTHY within 10s of container start.
- [ ] **Startup reconciliation**: if the VIP is already advertised when the agent starts (simulate by manually `tailscale set --advertise-routes=10.253.53.53/32` before starting the agent), the agent withdraws it at startup and re-advertises only after passing local probes.
- [ ] `/health` returns valid JSON matching the schema in `BLUEPRINT.md` §6 — including `desired_vip_advertised`, `fail_open`, and `force_withdraw_active` fields.
- [ ] `/metrics` returns valid Prometheus format including `dns_ha_agent_fail_open` and `dns_ha_agent_fail_open_duration_seconds`.
- [ ] **Fail-open metric correctness**: if the effective decision is WITHDRAW but a managed route remains advertised after a mutation failure, `/metrics` exposes `dns_ha_agent_fail_open 1` and `/health` exposes `fail_open: true`. The `_duration_seconds` gauge tracks elapsed time correctly and resets when the route is withdrawn.
- [ ] **Force-withdraw override**: creating `/var/lib/dns-ha-agent/force-withdraw` causes withdrawal within one probe cycle and `/health` exposes `force_withdraw_active: true`; removing it resumes normal operation.
- [ ] **`tailscale.mode` enforcement**: starting the agent with `tailscale.mode: noop` in production (without `DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE=true`) exits non-zero with a clear error. Starting with `mode: cli` but no reachable Tailscale CLI/socket also exits non-zero.
- [ ] SIGTERM withdraws route within 5s and exits 0.
- [ ] **Self-exit on hang**: forcibly hang the probe loop (e.g., inject `Thread.sleep(Long.MAX_VALUE)` in a test build). Within 30s, the `WatchdogThread` detects and the process exits non-zero. Docker `restart: unless-stopped` restarts the container.
- [ ] **Idempotent route mutation**: calling `TailscaleClient.advertiseRoutes()` twice with the same input is a no-op the second time; calling with a different input produces the expected mutation.
- [ ] **Preserved-routes handling**: a non-DNS route added externally to `preserved_routes` config is never wiped by the agent's withdraw operation.
- [ ] Inducing a filter break (stop AGH on dns-ams-01) causes the agent to withdraw within `(fail_threshold × interval) + tailscale convergence` ≈ 4s + 15s.
- [ ] **Per-backend filter probe**: configure a backup backend with a broken filter (e.g., temporarily remove the sentinel block rule on `agh-iom-01` only). Within one backend-probe interval, `dns_ha_agent_backend_filter_ok{backend="agh-iom-01"} == 0` and `/health` flags `filter_ok: false` for that backend, but the edge stays HEALTHY and continues advertising the VIP (the active backend is still good). Restoring the block rule restores the metric to 1 within one interval.
- [ ] Recovery after re-starting AGH: agent re-advertises within `success_threshold × interval` ≈ 6s.
- [ ] Two agents probing each other correctly detect partition when network-partitioned in a test.
- [ ] **Digest rollback** demonstrated: deploy digest A, deploy digest B, change ansible var back to A, redeploy, confirm image hash matches A exactly.
- [ ] Replaces the bash watchdog (which was historically referenced in `../`) on every edge node without regressions.
