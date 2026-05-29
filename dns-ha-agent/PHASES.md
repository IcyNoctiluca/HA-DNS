# dns-ha-agent — Implementation Phases

> Ordered implementation plan with per-phase acceptance criteria. Each phase is a coherent slice of work that can be completed and verified before starting the next. Status of each phase lives in [`PROGRESS.md`](PROGRESS.md). Design source: [`BLUEPRINT.md`](BLUEPRINT.md).

## Overview

| # | Phase | Outcome |
|---|---|---|
| 0 | Project scaffolding | Repo + build + container builds successfully, smoke test passes |
| 1 | Core probe + single-node advertise | Agent probes local DNS, advertises VIP when healthy, withdraws when not |
| 2 | HTTP server + observability | `/health` and `/metrics` endpoints live; JSON logging |
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

- `Config` record + `ConfigLoader` (Jackson YAML).
- `DnsProbe` — dual-check using dnsjava (resolve + filter).
- `HealthState` with FSM (STARTING, HEALTHY, UNHEALTHY).
- `Decider` (pure function).
- `TailscaleClient` interface + `CliTailscaleClient` (subprocess).
- `RouteStateStore` (intended routes in `/var/lib/dns-ha-agent/state.json`).
- `ProbeLoop` running in a virtual thread.
- `App` wires it together.
- Logback config: JSON to stdout.

### Acceptance criteria

- [ ] Unit tests for `ConfigLoader` (valid YAML parses; invalid → exit 78).
- [ ] Unit tests for `Decider` (every FSM state → correct decision).
- [ ] Unit tests for `HealthState` (counters, threshold transitions).
- [ ] Unit tests for `CliTailscaleClient` (subprocess args correct; non-zero exit → exception).
- [ ] Integration test (Testcontainers + FakeAgh): agent transitions STARTING → HEALTHY and calls `advertise` within 10s.
- [ ] Integration test: FakeAgh returns real IP for blocked name → agent transitions to UNHEALTHY and calls `withdraw`.
- [ ] No HTTP server, no metrics — these are Phase 2. Agent is fully functional via logs alone.

---

## Phase 2 — HTTP server + observability

**Goal**: peers and humans can see what the agent thinks. JSON `/health`, Prometheus `/metrics`.

### Scope

- Jersey + Grizzly server, bound to `http.bind_address:http.port`.
- `HealthResource` (`/health`) returning `HealthSnapshot` JSON.
- `MetricsResource` (`/metrics`) returning Prometheus format.
- Prometheus simpleclient instrumentation throughout existing code.
- `HealthSnapshot` is an immutable record built from `HealthState.snapshot()`.
- JSON schema matches `BLUEPRINT.md` §6 exactly.

### Acceptance criteria

- [ ] `curl http://localhost:8091/health` returns 200 + valid JSON matching schema.
- [ ] `curl http://localhost:8091/metrics` returns 200 + valid Prometheus format.
- [ ] All metrics listed in `BLUEPRINT.md` §6 are present and update correctly.
- [ ] Integration test: state transitions are reflected in subsequent `/health` responses within one probe interval.
- [ ] JVM metrics (heap, GC, threads) are auto-exported.
- [ ] `/health` returns HEALTHY/UNHEALTHY/STARTING correctly in tests.

---

## Phase 3 — Peer monitoring + split-brain

**Goal**: peer awareness and partition self-withdrawal.

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
- [ ] Integration test (two-agent Testcontainers setup): network-partition one container; after `peer_unreachable_threshold` + grace, the partitioned agent transitions to PARTITIONED and calls `withdraw`.
- [ ] Restoring network: partitioned agent transitions back to HEALTHY and re-advertises within 30s.
- [ ] Peer config update (add/remove peers) requires container restart — verified, not hot-reloaded.

---

## Phase 4 — Liveness + reconciliation

**Goal**: harden the safety properties. Startup reconciliation prevents stale advertisements; `WatchdogThread` prevents silent hangs.

### Scope

- Startup reconciliation (`BLUEPRINT.md` §8 Startup step 4): on boot, call `currentlyAdvertised()`; if VIP present, explicitly withdraw before entering STARTING.
- `WatchdogThread` — wakes every 10s, verifies probe loop reported within 30s, forces `System.exit(70)` on hang.
- `Thread.setDefaultUncaughtExceptionHandler` routes virtual-thread exceptions to logging + exit.
- HTTP server self-monitor: if loopback `/health` GET fails within 5s, same self-exit path.
- Graceful SIGTERM handler: transition to SHUTTING_DOWN, withdraw, drain HTTP, flush, exit 0.

### Acceptance criteria

- [ ] Integration test: pre-advertise VIP manually (`tailscale set --advertise-routes=10.253.53.53/32`), then start agent. Agent's first action is to withdraw; it then goes through STARTING → HEALTHY before re-advertising. Log line `found stale VIP advertisement at startup` is present.
- [ ] Integration test: inject `Thread.sleep(Long.MAX_VALUE)` into probe loop in a test build. Within 30s the `WatchdogThread` detects and the process exits non-zero. Restart policy then restarts.
- [ ] Integration test: send SIGTERM. Agent withdraws within 5s and exits 0.
- [ ] Integration test: SIGKILL the agent. Restart policy restarts it. New instance performs reconciliation (withdraws stale VIP) before re-validating.
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

**Goal**: prove the agent is ready to manage real production DNS. This is the gate before [`../ha-dns/PHASES.md`](../ha-dns/PHASES.md) Phase 8 (production cutover) can be advanced.

### Acceptance criteria (full v1.0 set)

- [ ] All unit and integration tests pass in CI.
- [ ] Smoke test in CI pulls (by digest) and runs the image successfully.
- [ ] Multi-arch image (`amd64`, `arm64`) published to `forgejo.homelab.internal/homelab/dns-ha-agent`; digest captured as CI build output.
- [ ] Ansible role asserts digest is set; refuses to deploy without one.
- [ ] Ansible role deploys cleanly to `dns-ams-01` (first canary).
- [ ] Agent transitions STARTING → HEALTHY within 10s of container start.
- [ ] **Startup reconciliation**: if the VIP is already advertised when the agent starts (simulate by manually `tailscale set --advertise-routes=10.253.53.53/32` before starting the agent), the agent withdraws it at startup and re-advertises only after passing local probes.
- [ ] `/health` returns valid JSON matching the schema in `BLUEPRINT.md` §6.
- [ ] `/metrics` returns valid Prometheus format.
- [ ] SIGTERM withdraws route within 5s and exits 0.
- [ ] **Self-exit on hang**: forcibly hang the probe loop (e.g., inject `Thread.sleep(Long.MAX_VALUE)` in a test build). Within 30s, the `WatchdogThread` detects and the process exits non-zero. Docker `restart: unless-stopped` restarts the container.
- [ ] **Idempotent route mutation**: calling `TailscaleClient.advertiseRoutes()` twice with the same input is a no-op the second time; calling with a different input produces the expected mutation.
- [ ] **Preserved-routes handling**: a non-DNS route added externally to `preserved_routes` config is never wiped by the agent's withdraw operation.
- [ ] Inducing a filter break (stop AGH on dns-ams-01) causes the agent to withdraw within `(fail_threshold × interval) + tailscale convergence` ≈ 4s + 15s.
- [ ] Recovery after re-starting AGH: agent re-advertises within `success_threshold × interval` ≈ 6s.
- [ ] Two agents probing each other correctly detect partition when network-partitioned in a test.
- [ ] **Digest rollback** demonstrated: deploy digest A, deploy digest B, change ansible var back to A, redeploy, confirm image hash matches A exactly.
- [ ] Replaces the bash watchdog (which was historically referenced in `../ha-dns/`) on every edge node without regressions.
