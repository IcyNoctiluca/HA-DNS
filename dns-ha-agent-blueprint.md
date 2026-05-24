# dns-ha-agent — Service Blueprint

> **v2 — May 2026.** Companion document to `HA-DNS-Analysis.md`. Specifies the self-contained, containerised, peer-aware health agent that manages each DNS edge node's Tailscale VIP advertisement.
>
> Changes from v1 (incorporating external review feedback):
> - **Startup reconciliation** (§8): agent now explicitly reconciles actual Tailscale route state at boot, withdrawing stale advertisements from crashed predecessors before re-validating via probes.
> - **Hang detection via self-exit** (§8): explicit `WatchdogThread` forces `System.exit(70)` on internal deadlock, since Docker `HEALTHCHECK` does NOT auto-restart unhealthy containers in plain Compose. `HEALTHCHECK` remains for diagnostic visibility only.
> - **Image versioning changed from rolling `:latest` to digest pinning**: CI publishes `:latest` as a convenience pointer, but ansible deploys pin by `@sha256:...` digest, asserted non-empty by the role. Rollback is byte-identical, not "revert source and rebuild."
> - **Tailscale socket explicitly mounted** (§11): `network_mode: host` does not expose the host filesystem socket; `/var/run/tailscale/tailscaled.sock` must be volume-mounted. Prose corrected.
> - **Dockerfile fixed**: multi-arch Tailscale binary download uses `ARG TARGETARCH`; `HEALTHCHECK` switched to `wget` (curl is purged post-install).
> - **Smoke test fixed** (§12): now checks out repo, generates config inline, uses `--network host`, polls with retry — no external dependencies.
> - **Decision pipeline split into FsmReducer + Decider + Reconciler** (§7): `Decider` is now a true pure function (`FsmState → ADVERTISE/WITHDRAW`); state transitions live in `FsmReducer`; route mutation idempotency lives in `Reconciler`. `RouteStateStore` removed — source of truth is `managed_routes + preserved_routes` from config.
> - **`managed_routes` added to config schema** (§5): explicit field replacing the implicit "managed VIP = node.vip" assumption.
> - **`/health` LAN exposure documented** (§6): `0.0.0.0` with `network_mode: host` exposes on LAN. Mitigations (bind to Tailscale IP, or iptables rule) documented. `dns_ha_agent_bind_address` Ansible variable added.
> - **Tailscale admin policy section added** (§14): `autoApprovers` and ACL requirements for `tag:dns-edge` documented.
> - **DNS probe hardened** (§5): `resolve_names` (plural, at least one must pass), configurable `block_expectation` for different filter styles (NXDOMAIN vs 0.0.0.0).
> - **Host VIP binding prerequisite documented** (§5 probe config, §16 first-deploy, §19 acceptance criteria).

---

## 1. Purpose

`dns-ha-agent` is a small Java service deployed to every DNS edge node in the homelab HA DNS topology. Its sole responsibility is to decide whether *this* edge should currently advertise the shared VIP route (`10.253.53.53/32`) to Tailscale, based on:

1. **Local stack health** — does this node's dnsdist → AGH → Unbound chain resolve correctly AND apply filtering?
2. **Peer reachability** — can other DNS edges see this node? (Split-brain protection.)

When healthy, the agent ensures the VIP is advertised. When unhealthy (or partitioned), the agent withdraws the route. It exposes a `/health` endpoint for peer observation and a `/metrics` endpoint for Prometheus.

This service replaces the bash watchdog originally described in `HA-DNS-Analysis.md` Appendix C.

---

## 2. Goals & Non-Goals

### Goals

- **Self-contained**: each instance probes itself + peers, decides locally, mutates only its own Tailscale state. No external monitoring tool is required for HA to function.
- **Identical deployment**: same container image runs on every edge; behaviour parameterised entirely by a YAML config rendered per-node by ansible.
- **Declarative**: the only operator-facing surface is the YAML config and the ansible role.
- **Reproducible**: image is built deterministically in CI; ansible role is idempotent.
- **Observable**: structured JSON logs to stdout, `/health` endpoint for peers, `/metrics` endpoint for Prometheus.
- **Fail-safe**: agent crashes, hangs, or container restarts default to *withdrawing* the route, never to silently leaving stale advertisements.

### Non-Goals

- **Cross-node state mutation**: peers never mutate each other's Tailscale state. They observe only.
- **Consensus protocol**: no Raft, no Paxos, no leader election. Each agent decides for itself based on its own observations.
- **DNS filtering**: that's AGH's job. The agent only *checks* that filtering is working.
- **DNS resolution**: that's dnsdist/AGH/Unbound's job.
- **k3s / Kubernetes dependency**: this service must work standalone (DNS must not depend on k3s, per the parent design).
- **IPv6 support in v1**: matches the rest of the HA DNS design.
- **Hot config reload**: config changes require container restart.

---

## 3. High-Level Architecture

```
┌────────────────────── dns-edge node (e.g. dns-ams-01) ──────────────────────┐
│                                                                              │
│   ┌────────────────────┐                                                    │
│   │   dns-ha-agent     │  (this service, in a container)                    │
│   │                    │                                                    │
│   │  ┌──────────────┐  │   probes  ┌──────────────┐                         │
│   │  │  DnsProbe    │──┼──────────▶│  10.253.53.53 (local VIP via dnsdist)│ │
│   │  └──────────────┘  │           └──────────────┘                         │
│   │                    │                                                    │
│   │  ┌──────────────┐  │   probes  ┌──────────────────────────────────┐    │
│   │  │ PeerMonitor  │──┼──────────▶│ peer agents (TS IPs, /health)    │    │
│   │  └──────────────┘  │           └──────────────────────────────────┘    │
│   │                    │                                                    │
│   │  ┌──────────────┐  │                                                    │
│   │  │  Decider     │  │   reads local + peer state → advertise/withdraw    │
│   │  └──────────────┘  │                                                    │
│   │                    │                                                    │
│   │  ┌──────────────┐  │   subprocess                                       │
│   │  │ TS Client    │──┼──────────▶ tailscale set --advertise-routes=...   │
│   │  └──────────────┘  │                                                    │
│   │                    │                                                    │
│   │  ┌──────────────┐  │                                                    │
│   │  │  HTTP Server │──┼──◀──────── peer agents query /health, /metrics    │
│   │  └──────────────┘  │                                                    │
│   └────────────────────┘                                                    │
│                                                                              │
│   Container runs --network host. Uses host's tailscaled socket via CLI.     │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Component summary

| Component | Responsibility |
|---|---|
| `DnsProbe` | Runs dual probe (resolve one of `resolve_names`, confirm `block_name` matches `block_expectation`) against `10.253.53.53` on a configurable interval. |
| `PeerMonitor` | Concurrently polls each peer's `/health` endpoint, tracks reachability and reported state. One virtual thread per peer. |
| `HealthState` | Thread-safe holder of current state: latest probe result, fail/success counters, per-peer reachability, current advertised state. |
| `Decider` | Pure function: given `HealthState` + config, returns `ADVERTISE` or `WITHDRAW` decision. Easy to unit-test. |
| `TailscaleClient` (interface) | Abstracts the Tailscale mutation. Production impl uses subprocess to `tailscale` CLI. Tests use a fake. |
| `HealthServer` | Jersey-based HTTP server exposing `/health` (peer-facing) and `/metrics` (Prometheus). |
| `App` | Entry point: loads config, wires components, manages lifecycle (startup, graceful shutdown on SIGTERM). |

---

## 4. State Machine

The agent transitions through these states based on probe results:

```
                  ┌──────────────────────────────────────────────┐
                  │                                              │
                  ▼                                              │
            ┌──────────┐    N consecutive       ┌────────────┐   │
            │ STARTING │────success probes────▶ │ HEALTHY    │   │
            └──────────┘    (default N=3)       │ advertising│   │
                  │                             └────────────┘   │
                  │                                   │          │
                  │                                   │ M consec.│
                  │                                   │ fails    │
                  │                                   │ (default │
                  │                                   │  M=2)    │
                  │                                   ▼          │
                  │                             ┌────────────┐   │
                  └────────────────────────────▶│ UNHEALTHY  │───┘
                                                │ withdrawing│
                                                └────────────┘
                                                      │
                                                      │ peer-based:
                                                      │ if N peers
                                                      │ report me as
                                                      │ unreachable
                                                      ▼
                                                ┌────────────┐
                                                │ PARTITIONED│
                                                │ withdrawing│
                                                └────────────┘
```

### State definitions

- **STARTING**: agent just booted, has not yet completed `success_threshold` healthy probes. Route is *not* advertised.
- **HEALTHY**: probes are passing. Route *is* advertised.
- **UNHEALTHY**: local probes have failed `fail_threshold` consecutive times. Route is withdrawn. Continues probing.
- **PARTITIONED**: `min_peer_reports_to_self_withdraw` peers have reported this node as unreachable for `peer_unreachable_threshold`. Route is withdrawn even if local probes pass.
- **SHUTTING_DOWN**: SIGTERM received. Withdraws route, waits up to 5s for confirmation, exits.

### Transitions

| From | To | Trigger |
|---|---|---|
| STARTING | HEALTHY | `success_threshold` consecutive successful probes |
| HEALTHY | UNHEALTHY | `fail_threshold` consecutive failed probes |
| UNHEALTHY | HEALTHY | `success_threshold` consecutive successful probes |
| HEALTHY | PARTITIONED | `min_peer_reports_to_self_withdraw` peers report unreachable for `peer_unreachable_threshold` (only if peers are configured) |
| PARTITIONED | HEALTHY | peers can reach this node again AND local probes still passing |
| any | SHUTTING_DOWN | SIGTERM received |

### Important behavioural notes

1. **Single-node region (zero peers configured)**: PARTITIONED state is unreachable. Agent uses local probes only. This matches SHZ-01's role in the current topology.
2. **Cold start safety**: STARTING → HEALTHY requires `success_threshold` probes (default 3 × 2s = 6s), so a freshly-started agent on a node where the stack isn't ready won't briefly advertise.
3. **Idempotency**: every `tailscale set` call sends the full intended route list, computed by the `Reconciler` as `managed_routes ∪ preserved_routes` (both from config). The agent never sends "delta" mutations; it always reconciles against the full desired state. See §7 Reconciler for details.

---

## 5. Configuration Schema

YAML config rendered by ansible from a template per node. Mounted into the container at `/etc/dns-ha-agent/config.yaml`.

### Full schema (v1)

```yaml
# Schema version. Increment on breaking changes.
version: 1

# This node's identity. Used in logs, metrics, /health output.
node:
  name: dns-ams-01
  region: eu
  # The VIP this node manages. Must match across the regional cluster.
  vip: 10.253.53.53/32

# Local DNS probing.
probe:
  # The DNS target the agent probes (the VIP, served by local dnsdist).
  # PREREQUISITE: each DNS edge node must bind 10.253.53.53/32 locally on a
  # loopback or dummy interface, and dnsdist must listen on 10.253.53.53:53.
  # If traffic to 10.253.53.53 is routed via Tailscale instead of locally,
  # the probe will test the wrong node (or nothing). Verify with:
  #   ip route get 10.253.53.53   → must resolve to lo or a dummy interface
  #   dig @10.253.53.53 cloudflare.com   → must hit local dnsdist
  target: 10.253.53.53
  # Names that MUST resolve to real A records (resolve check).
  # At least one must succeed for the probe to pass. Multiple names reduce
  # brittleness from a single external domain being temporarily unreachable.
  resolve_names:
    - cloudflare.com
    - example.com
  resolve_type: A
  # A name that MUST be blocked by AGH (filter check). Custom AGH rewrite/blocklist entry.
  block_name: test.blocked.internal
  # Expected response for the blocked name. Different DNS filters return different
  # blocking styles (NXDOMAIN, REFUSED, NOERROR with 0.0.0.0, etc.).
  # Configure to match your AGH setup to avoid false-positive unhealthy states.
  block_expectation:
    # must_not_return_real_ip: pass if the response is NOT a routable IP
    # (i.e. any of allowed_rcodes, or an IP in allowed_ips, is acceptable)
    mode: must_not_return_real_ip
    allowed_rcodes:
      - NXDOMAIN
      - REFUSED
      - NOERROR      # NOERROR is acceptable if the IP is in allowed_ips
    allowed_ips:
      - "0.0.0.0"    # AGH default block response
  # Probe cadence and thresholds.
  interval: 2s
  query_timeout: 2s
  fail_threshold: 2
  success_threshold: 3

# Peer agents — for split-brain detection. Empty list disables peer monitoring.
peers:
  - name: dns-iom-01
    health_url: http://100.64.x.y:8091/health
  - name: dns-tor-01
    health_url: http://100.64.a.b:8091/health
  - name: dns-shz-01
    health_url: http://100.64.c.d:8091/health

# Split-brain (partition) detection.
split_brain:
  enabled: true
  peer_poll_interval: 15s
  peer_poll_timeout: 5s
  # Treat a peer as "unable to reach me" if its /health reports this node
  # in its unreachable_peers list for this long.
  peer_unreachable_threshold: 60s
  # This many peers must report me unreachable before I self-withdraw.
  min_peer_reports_to_self_withdraw: 2

# Tailscale interaction.
tailscale:
  # Path to tailscale CLI in the container (baked into image).
  cli_path: /usr/bin/tailscale
  # Routes this agent actively manages (advertise when HEALTHY, withdraw otherwise).
  # This is the authoritative source of truth for what gets toggled. Must match
  # node.vip. Defined explicitly so the Reconciler never has to infer intent.
  managed_routes:
    - 10.253.53.53/32
  # Routes to always preserve when mutating the advertise list — never wiped,
  # regardless of health state. Safety hatch for manually-added subnet routes
  # (e.g. a k3s test prefix) that should survive agent restarts.
  preserved_routes: []
  # Timeout for tailscale set invocations.
  command_timeout: 10s

# HTTP server config.
http:
  # Bind address for /health and /metrics.
  # WARNING: with network_mode: host, 0.0.0.0 exposes port 8091 on ALL
  # interfaces including LAN. Tailscale ACLs do NOT protect LAN-originated
  # traffic. Recommended: set to the node's Tailscale IP (100.x.x.x), or
  # keep 0.0.0.0 and add a host firewall rule allowing 8091 only on tailscale0.
  # See §6 for details.
  bind_address: 0.0.0.0   # override in host_vars with dns_ha_agent_bind_address
  port: 8091

# Observability.
logging:
  # JSON or PLAIN. JSON for production (machine-parsable).
  format: JSON
  level: INFO

metrics:
  enabled: true
  # Prometheus scrape path. Served on the same HTTP server as /health.
  path: /metrics
```

### Schema versioning

`version: 1` is required. If the agent reads a config with an unknown version, it logs an error and exits with code 78 (configuration error). Schema changes that break compatibility bump the version; the agent maintains parsers for the last two major versions.

---

## 6. API Surface

### `GET /health`

Peer-facing endpoint. Returns the agent's current self-assessment and view of its peers.

**Response (200 OK, application/json):**

```json
{
  "version": 1,
  "node": "dns-ams-01",
  "region": "eu",
  "state": "HEALTHY",
  "vip_advertised": true,
  "local_probe": {
    "last_probe_ts": "2026-05-23T14:33:21Z",
    "last_result": "HEALTHY",
    "consecutive_successes": 247,
    "consecutive_failures": 0,
    "resolve_check_ok": true,
    "filter_check_ok": true,
    "last_probe_duration_ms": 7
  },
  "peers": [
    {
      "name": "dns-iom-01",
      "last_seen_ts": "2026-05-23T14:33:18Z",
      "reachable": true,
      "reported_state": "HEALTHY",
      "reports_me_unreachable": false
    },
    {
      "name": "dns-tor-01",
      "last_seen_ts": "2026-05-23T14:33:15Z",
      "reachable": true,
      "reported_state": "HEALTHY",
      "reports_me_unreachable": false
    }
  ],
  "unreachable_peers": [],
  "uptime_seconds": 88143
}
```

The `unreachable_peers` field lists peer names this agent currently cannot reach — peers consult this field to determine if they're being reported unreachable.

**No authentication.** Access control is via Tailscale ACL (only `tag:dns-edge` nodes can reach port 8091 on edges). This is consistent with the homelab pattern of relying on tailnet boundary as the security perimeter.

**LAN exposure caveat.** With `network_mode: host` and `bind_address: 0.0.0.0`, port 8091 is reachable from the LAN as well as via Tailscale. Tailscale ACLs only govern tailnet-originated traffic — they do not protect LAN-originated traffic. Mitigate with one of:

1. **Bind to Tailscale IP** — set `http.bind_address` to the node's Tailscale IP (e.g. `100.x.x.x`) in `host_vars`. The HTTP server won't be reachable from LAN at all. This requires knowing the Tailscale IP at config time; use an Ansible variable `dns_ha_agent_bind_address` set per node.
2. **Host firewall rule** — keep `0.0.0.0` and add an iptables rule restricting port 8091 to the `tailscale0` interface: `iptables -A INPUT -p tcp --dport 8091 ! -i tailscale0 -j DROP`. Manage via the ansible role alongside the compose template.

Option 1 is preferred. The `/debug` endpoint (if added, §17 item 3) must not be exposed LAN-wide under any circumstances.

### `GET /metrics`

Prometheus-format metrics. Served by `simpleclient_httpserver` integration with Jersey, or via a direct `simpleclient_servlet` mount.

**Exported metrics:**

```
# HELP dns_ha_agent_probe_total Total local probes performed
# TYPE dns_ha_agent_probe_total counter
dns_ha_agent_probe_total{result="success"} 14823
dns_ha_agent_probe_total{result="failure"} 4

# HELP dns_ha_agent_probe_duration_seconds Local probe duration
# TYPE dns_ha_agent_probe_duration_seconds histogram
dns_ha_agent_probe_duration_seconds_bucket{le="0.01"} 14700
dns_ha_agent_probe_duration_seconds_bucket{le="0.05"} 14820
...

# HELP dns_ha_agent_vip_advertised Currently advertising VIP route (1=yes, 0=no)
# TYPE dns_ha_agent_vip_advertised gauge
dns_ha_agent_vip_advertised 1

# HELP dns_ha_agent_state Current FSM state
# TYPE dns_ha_agent_state gauge
dns_ha_agent_state{state="HEALTHY"} 1
dns_ha_agent_state{state="STARTING"} 0
dns_ha_agent_state{state="UNHEALTHY"} 0
dns_ha_agent_state{state="PARTITIONED"} 0

# HELP dns_ha_agent_peer_reachable Peer reachability (1=reachable, 0=unreachable)
# TYPE dns_ha_agent_peer_reachable gauge
dns_ha_agent_peer_reachable{peer="dns-iom-01"} 1
dns_ha_agent_peer_reachable{peer="dns-tor-01"} 1

# HELP dns_ha_agent_route_mutations_total Total tailscale set invocations
# TYPE dns_ha_agent_route_mutations_total counter
dns_ha_agent_route_mutations_total{action="advertise",result="success"} 14
dns_ha_agent_route_mutations_total{action="withdraw",result="success"} 13

# HELP dns_ha_agent_last_route_mutation_ts Unix timestamp of last tailscale set
# TYPE dns_ha_agent_last_route_mutation_ts gauge
dns_ha_agent_last_route_mutation_ts 1.768139601e+09

# Standard JVM metrics (heap, GC, threads) — auto-exported by simpleclient_hotspot.
```

---

## 7. Decision Logic

The decision pipeline is split into two distinct responsibilities to keep each piece testable and free of hidden side effects:

**`FsmReducer`** — computes the next FSM state from current state + new observations. This is the only place FSM transitions happen. It has no I/O.

**`Decider`** — a pure function that maps an `FsmState` + config to `ADVERTISE` or `WITHDRAW`. No side effects, no state mutation. Trivially unit-testable.

**`Reconciler`** — compares the `Decider`'s output against the actual Tailscale route state and invokes `TailscaleClient` only when a change is needed. Handles idempotency.

### FsmReducer

```
function reduceState(currentState, snapshot, config) -> FsmState:
    if currentState == SHUTTING_DOWN:
        return SHUTTING_DOWN   # terminal, nothing overrides it

    # Evaluate partition condition (only from HEALTHY)
    if currentState == HEALTHY and config.split_brain.enabled and len(config.peers) > 0:
        unreachable_count = count peers where
            snapshot.peer.reports_me_unreachable AND
            (now - snapshot.peer.first_reported_unreachable_ts) > config.split_brain.peer_unreachable_threshold

        if unreachable_count >= config.split_brain.min_peer_reports_to_self_withdraw:
            return PARTITIONED

    # Probe-driven transitions
    if currentState == PARTITIONED:
        if all_peers_reachable(snapshot) and snapshot.consecutive_successes >= 1:
            return HEALTHY   # recover from partition once visible again
        return PARTITIONED

    if currentState in (STARTING, UNHEALTHY):
        if snapshot.consecutive_successes >= config.probe.success_threshold:
            return HEALTHY
        return currentState

    if currentState == HEALTHY:
        if snapshot.consecutive_failures >= config.probe.fail_threshold:
            return UNHEALTHY
        return HEALTHY

    return currentState
```

### Decider (pure)

```
function decide(fsmState) -> Decision:
    return ADVERTISE if fsmState == HEALTHY else WITHDRAW
```

That's it. The `Decider` has no knowledge of peers, thresholds, or config. All the complex logic lives in `FsmReducer`, which is also pure and stateless. State is held in `HealthState`; `FsmReducer` returns a new state value, `HealthState` applies it atomically.

### Reconciler

```
function reconcile(decision, config, tailscaleClient):
    managed  = set(config.tailscale.managed_routes)   # e.g. {"10.253.53.53/32"}
    preserved = set(config.tailscale.preserved_routes) # routes never wiped

    actual = tailscaleClient.currentlyAdvertised()

    if decision == ADVERTISE:
        desired = managed ∪ preserved
    else:
        desired = preserved   # withdraw managed, keep preserved

    if actual != desired:
        tailscaleClient.advertiseRoutes(desired)
        # log route_mutation event
```

The source of truth for what to advertise is always `managed_routes + preserved_routes` from config — never a separate state file. The Reconciler calls `tailscaleClient.currentlyAdvertised()` on every cycle to detect and correct any drift (e.g., an external `tailscale set` by an operator), not only on state transitions.

---

## 8. Operational Behaviour

### Startup

1. Parse `/etc/dns-ha-agent/config.yaml`. Exit code 78 if invalid.
2. Validate config (peer URLs reachable in DNS sense, VIP format, etc.). Log warnings but don't exit on peer-unreachable.
3. Start HTTP server (Jersey) on configured bind address.
4. **Reconcile actual route state with desired state.** Call `TailscaleClient.currentlyAdvertised()` to see what Tailscale currently advertises. If the VIP `10.253.53.53/32` is already advertised (likely because a previous instance crashed without graceful shutdown), explicitly withdraw it now. The agent always re-derives the route advertisement from its own observed health; a stale advertisement from a dead predecessor must not be inherited as "we're healthy." Log this loudly: `WARN: found stale VIP advertisement at startup, withdrawing pending health verification`.
5. Start probe loop (one virtual thread).
6. Start peer monitor loops (one virtual thread per peer).
7. Begin in STARTING state. Route is NOT advertised.
8. After `success_threshold` consecutive successful probes, transition to HEALTHY and call `TailscaleClient.advertise()`. Reconciliation guarantees this is the *only* path by which the route becomes advertised — there is no "we were already advertised, just keep going" shortcut.

This reconciliation step is the answer to the question "what if the agent crashes mid-flight and a new instance starts up while the route is still advertised?" Without it, the new instance would inherit the stale advertisement even if its local stack is actually unhealthy. With it, the new instance always re-validates from scratch before advertising.

### Graceful shutdown (SIGTERM)

1. Transition FSM to SHUTTING_DOWN.
2. Call `TailscaleClient.withdraw()` synchronously. Wait up to 5s for confirmation.
3. Stop accepting new HTTP requests; allow in-flight to complete (with 2s deadline).
4. Stop probe and peer monitor threads.
5. Flush logs.
6. Exit code 0.

If SIGKILL is received (or process crashes), the route advertisement remains. Docker `restart: unless-stopped` restarts the container; the new instance performs the startup reconciliation (§Startup step 4) — explicitly withdrawing the stale advertisement before re-validating via probes. There is a brief window (typically 6–10s) between the crash and the new instance re-advertising. This is acceptable because Tailscale's own node-level failover would catch the dead container's node going offline anyway.

### Hang detection and self-exit

**Important**: Docker `HEALTHCHECK` marks a container `unhealthy` but does NOT auto-restart it in plain Compose. Relying on Docker health status alone is a footgun. The agent must therefore detect internal hangs and *exit non-zero* itself, letting Docker's `restart: unless-stopped` policy do the restart.

Implementation:

- A dedicated `WatchdogThread` (separate from the probe loop) wakes every 10s and verifies that the probe loop has reported a result (success or failure) within the last 30s. If not, the probe loop has hung — the watchdog logs `FATAL: probe loop hung, forcing exit` and calls `System.exit(70)` (EX_SOFTWARE).
- The HTTP server is also monitored: if it fails to respond to its own loopback `/health` GET within 5s, same self-exit path.
- Uncaught exceptions in any virtual thread are routed to `Thread.setDefaultUncaughtExceptionHandler` which logs and exits.

The Docker `HEALTHCHECK` directive remains in the Dockerfile but its role is **diagnostic only** (visible in `docker ps` for operator visibility, scraped by monitoring) — recovery comes from the agent self-exiting + Docker restart policy, not from the healthcheck triggering a restart.

### Error handling

| Condition | Behaviour |
|---|---|
| Local probe DNS query times out | Counts as a failure. |
| Local probe gets a real IP for the blocked name | Counts as a failure (filtering broken). |
| Tailscale CLI invocation exits non-zero | Log error, retry on next decision cycle. Counter `route_mutations_total{result="failure"}` incremented. |
| Tailscale CLI invocation times out | Kill process, log, retry on next cycle. |
| Peer `/health` returns non-200 or invalid JSON | Mark peer unreachable for this poll cycle. |
| Peer `/health` times out | Mark peer unreachable for this poll cycle. |
| All peers unreachable for >60s but local probes pass | Stay HEALTHY (no quorum to declare partition; you're alone, not partitioned from yourself). |
| Config file changes on disk | Ignored. Restart container to pick up changes. |

### Logging

All logs to stdout in JSON when `logging.format: JSON`:

```json
{"ts":"2026-05-23T14:33:21Z","level":"INFO","msg":"probe_result","node":"dns-ams-01","result":"success","duration_ms":7,"consecutive_successes":247}
{"ts":"2026-05-23T14:33:23Z","level":"INFO","msg":"peer_unreachable","node":"dns-ams-01","peer":"dns-iom-01","consecutive_failures":3}
{"ts":"2026-05-23T14:33:25Z","level":"WARN","msg":"state_transition","node":"dns-ams-01","from":"HEALTHY","to":"UNHEALTHY","reason":"probe_failures"}
{"ts":"2026-05-23T14:33:25Z","level":"WARN","msg":"route_mutation","node":"dns-ams-01","action":"withdraw","result":"success","duration_ms":243}
```

UTC timestamps with second precision. Higher-frequency events (per-probe in DEBUG) are kept at DEBUG level.

---

## 9. Implementation

### Tech stack

| Layer | Choice | Version (target) |
|---|---|---|
| JDK | Eclipse Temurin 21 LTS | 21.0.x |
| Build | Gradle | 8.x with Kotlin DSL |
| HTTP server | Jersey + Grizzly | jersey-container-grizzly2-http 3.1.x |
| HTTP client | JDK `java.net.http.HttpClient` | bundled |
| JSON | Jackson (jackson-databind, jackson-dataformat-yaml) | 2.17.x |
| DNS library | dnsjava | 3.6.x |
| Metrics | Prometheus simpleclient (simpleclient, simpleclient_common, simpleclient_hotspot) | 0.16.x |
| Logging | SLF4J + Logback with logstash-logback-encoder (for JSON) | logback 1.5.x |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers | latest stable |
| CI | Forgejo Actions | self-hosted runner |
| Container base | `eclipse-temurin:21-jre-jammy` | rolling |

### Project structure

```
dns-ha-agent/
├── .forgejo/
│   └── workflows/
│       └── build.yml                          # CI pipeline
├── ansible/
│   └── roles/
│       └── dns-ha-agent/
│           ├── defaults/main.yml              # Default vars
│           ├── tasks/main.yml                 # Deployment tasks
│           ├── templates/config.yaml.j2       # Config template
│           ├── templates/docker-compose.yml.j2
│           └── handlers/main.yml              # Restart handler
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/...
├── gradlew
├── Dockerfile
├── README.md
├── LICENSE
├── config/
│   └── example.yaml                           # Annotated example
└── src/
    ├── main/
    │   ├── java/io/homelab/dnsha/
    │   │   ├── App.java                       # Entry point
    │   │   ├── config/
    │   │   │   ├── Config.java                # YAML schema record
    │   │   │   ├── ConfigLoader.java
    │   │   │   └── ConfigValidator.java
    │   │   ├── probe/
    │   │   │   ├── DnsProbe.java
    │   │   │   ├── ProbeResult.java
    │   │   │   └── ProbeLoop.java
    │   │   ├── peer/
    │   │   │   ├── PeerMonitor.java
    │   │   │   ├── PeerState.java
    │   │   │   └── PeerHealthResponse.java
    │   │   ├── state/
    │   │   │   ├── HealthState.java           # Thread-safe state holder
    │   │   │   └── FsmState.java              # Enum
    │   │   ├── decide/
    │   │   │   ├── FsmReducer.java            # Pure: state + snapshot → nextState
    │   │   │   ├── Decider.java               # Pure: fsmState → ADVERTISE/WITHDRAW
    │   │   │   ├── Reconciler.java            # Compares decision vs actual TS state
    │   │   │   └── Decision.java              # ADVERTISE | WITHDRAW
    │   │   ├── tailscale/
    │   │   │   ├── TailscaleClient.java       # Interface
    │   │   │   └── CliTailscaleClient.java    # Subprocess impl
    │   │   ├── http/
    │   │   │   ├── HealthServer.java
    │   │   │   ├── HealthResource.java        # Jersey @Path("/health")
    │   │   │   └── MetricsResource.java
    │   │   └── lifecycle/
    │   │       ├── ShutdownHook.java
    │   │       └── HealthchecksScheduler.java
    │   └── resources/
    │       ├── logback.xml
    │       └── version.properties             # Filled at build time
    └── test/
        ├── java/io/homelab/dnsha/
        │   ├── probe/DnsProbeTest.java
        │   ├── decide/DeciderTest.java
        │   ├── peer/PeerMonitorTest.java
        │   ├── tailscale/CliTailscaleClientTest.java
        │   ├── http/HealthResourceTest.java
        │   ├── config/ConfigLoaderTest.java
        │   └── integration/
        │       ├── EndToEndIT.java            # Testcontainers-based
        │       └── FakeAghContainer.java
        └── resources/
            ├── configs/
            │   ├── valid.yaml
            │   ├── invalid-schema.yaml
            │   └── single-node.yaml
            └── logback-test.xml
```

### Key class signatures

```java
// Config — immutable record, parsed from YAML
public record Config(
    int version,
    NodeConfig node,
    ProbeConfig probe,
    List<PeerConfig> peers,
    SplitBrainConfig splitBrain,
    TailscaleConfig tailscale,
    HttpConfig http,
    LoggingConfig logging,
    MetricsConfig metrics
) { }

// HealthState — thread-safe, all mutations via synchronized methods
public final class HealthState {
    public synchronized void recordProbeSuccess(Duration duration);
    public synchronized void recordProbeFailure(String reason);
    public synchronized void recordPeerObservation(String peer, PeerHealthResponse response);
    public synchronized void recordPeerUnreachable(String peer);
    public synchronized FsmState currentState();
    public synchronized HealthSnapshot snapshot();   // immutable view for /health
}

// FsmReducer — pure: (currentState, HealthSnapshot, Config) → nextFsmState
public final class FsmReducer {
    public static FsmState reduce(FsmState current, HealthSnapshot snapshot, Config config);
}

// Decider — pure: fsmState → ADVERTISE | WITHDRAW (no config needed)
public final class Decider {
    public static Decision decide(FsmState state);
}

// Reconciler — compares decision against actual Tailscale state, mutates only on drift
public final class Reconciler {
    public void reconcile(Decision decision, Config config, TailscaleClient client)
        throws TailscaleException;
}

// TailscaleConfig — part of Config record
// managed_routes: routes toggled by health state (advertised when HEALTHY, withdrawn otherwise)
// preserved_routes: routes never wiped, regardless of state
public record TailscaleConfig(
    String cliPath,
    List<String> managedRoutes,
    List<String> preservedRoutes,
    Duration commandTimeout
) { }

// TailscaleClient — interface
public interface TailscaleClient {
    void advertiseRoutes(Set<String> routes) throws TailscaleException;
    Set<String> currentlyAdvertised() throws TailscaleException;
}

// ProbeLoop — runs in a virtual thread
public final class ProbeLoop implements Runnable {
    public ProbeLoop(DnsProbe probe, HealthState state, Config config);
    public void run();   // loops until interrupted
}

// HealthResource — Jersey
@Path("/health")
public class HealthResource {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HealthSnapshot get();
}
```

### Gradle build (build.gradle.kts excerpt)

```kotlin
plugins {
    java
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"   // Fat-jar
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "io.homelab.dnsha.App"
}

dependencies {
    // HTTP server
    implementation("org.glassfish.jersey.containers:jersey-container-grizzly2-http:3.1.5")
    implementation("org.glassfish.jersey.inject:jersey-hk2:3.1.5")
    implementation("org.glassfish.jersey.media:jersey-media-json-jackson:3.1.5")

    // YAML config
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")

    // DNS
    implementation("dnsjava:dnsjava:3.6.0")

    // Metrics
    implementation("io.prometheus:simpleclient:0.16.0")
    implementation("io.prometheus:simpleclient_hotspot:0.16.0")
    implementation("io.prometheus:simpleclient_servlet:0.16.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")
}

tasks.test {
    useJUnitPlatform()
}

// Fat jar named consistently for the Dockerfile to copy
tasks.shadowJar {
    archiveBaseName.set("dns-ha-agent")
    archiveClassifier.set("")
    archiveVersion.set("")
}
```

---

## 10. Testing Strategy

### Unit tests (fast, no I/O)

| Module | What to test |
|---|---|
| `ConfigLoader` | Valid YAML parses; invalid YAML rejected with clear error; unknown version exits with code 78; default values applied. |
| `FsmReducer` | Every (state, snapshot) combination → expected next state. Partition threshold respected. Single-node (zero peers) never reaches PARTITIONED. |
| `Decider` | HEALTHY → ADVERTISE; all other states → WITHDRAW. |
| `Reconciler` | Advertise + routes already correct → no tailscale call. Advertise + drift detected → correct call. Withdraw → only preserved_routes remain. |
| `HealthState` | Counters increment correctly; threshold transitions fire at right moments; snapshot is consistent under concurrent updates. |
| `DnsProbe` | Mocked dnsjava Resolver: success when at least one resolve_name returns a real IP; failure when all resolve_names fail; failure when block_name returns a routable IP not in allowed_ips; pass when block_name returns an allowed_rcode or allowed_ip; failure on timeout. |
| `CliTailscaleClient` | Subprocess invocation arguments correct; non-zero exit → exception; timeout → exception. (Uses an interface-mocked `ProcessBuilder`-equivalent.) |

### Integration tests (Testcontainers)

| Test | Setup |
|---|---|
| `EndToEndIT.healthyProbeAdvertises` | Spin up `FakeAghContainer` (Python script returning canned responses). Configure agent to probe it. Verify agent transitions STARTING → HEALTHY and calls `advertise`. |
| `EndToEndIT.filterBreakWithdraws` | FakeAGH returns real IP for blocked name. Agent transitions to UNHEALTHY, calls `withdraw`. |
| `EndToEndIT.peerPartitionDetected` | Two agent instances, each with the other as a peer. Network-partition one container. Confirm the partitioned one self-withdraws after threshold. |
| `EndToEndIT.gracefulShutdownWithdraws` | Send SIGTERM. Verify `withdraw` called before container exits with code 0. |
| `EndToEndIT.crashLeavesRouteStaleButRecovers` | SIGKILL one agent. Verify systemd/Docker restarts it. After restart, agent goes through STARTING → HEALTHY normally. |

`FakeAghContainer` is a tiny container (Python or Go) that responds to DNS queries with canned answers controllable via HTTP API. Lives in `src/test/resources/fake-agh/` and is built by the test setup.

### Smoke test (in CI)

After image build, CI runs the container with a minimal config that points peer health URLs at `httpbin` returning 200, runs for 30s, asserts `/health` returns 200 and JSON parses. Catches container packaging issues that unit tests miss.

### What NOT to test

- Actual Tailscale CLI integration. The `CliTailscaleClient` is mocked at the interface boundary. The real CLI is exercised in production deployments. A staging Tailscale tailnet for CI testing would be valuable but is out of scope for v1.
- Real AGH/Unbound integration. The agent doesn't care what's behind the probe target; FakeAghContainer is sufficient.

---

## 11. Container Strategy

### Dockerfile

```dockerfile
# Multi-stage: build with JDK, run with JRE
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY src src
RUN ./gradlew --no-daemon shadowJar

# Runtime
FROM eclipse-temurin:21-jre-jammy
# TARGETARCH is injected by Docker BuildKit (amd64 | arm64).
# Tailscale's archive names match Docker's TARGETARCH values on our platforms.
ARG TARGETARCH
ARG TAILSCALE_VERSION=1.74.0

# Install tailscale CLI (for subprocess invocations).
# Pinned version for reproducibility; bump deliberately.
RUN case "${TARGETARCH}" in \
      amd64) TS_ARCH="amd64" ;; \
      arm64) TS_ARCH="arm64" ;; \
      *) echo "Unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac && \
    apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    curl -fsSL "https://pkgs.tailscale.com/stable/tailscale_${TAILSCALE_VERSION}_${TS_ARCH}.tgz" \
      | tar -xz -C /tmp && \
    mv "/tmp/tailscale_${TAILSCALE_VERSION}_${TS_ARCH}/tailscale" /usr/bin/tailscale && \
    chmod +x /usr/bin/tailscale && \
    rm -rf /tmp/tailscale_${TAILSCALE_VERSION}_${TS_ARCH} && \
    apt-get purge -y curl && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/build/libs/dns-ha-agent.jar /app/dns-ha-agent.jar
COPY config/example.yaml /etc/dns-ha-agent/example.yaml

# Container HEALTHCHECK is DIAGNOSTIC ONLY — visible in `docker ps`, scraped by
# monitoring. It does NOT trigger restarts (Docker/Compose does not auto-restart
# on unhealthy alone). Recovery from hangs comes from the agent self-exiting
# via its internal WatchdogThread, plus `restart: unless-stopped`.
# Uses wget (present in eclipse-temurin:jammy base) — curl is purged above.
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
    CMD wget -qO- http://localhost:8091/health > /dev/null || exit 1

EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/dns-ha-agent.jar", "--config", "/etc/dns-ha-agent/config.yaml"]
```

Two architectures need to be built: `amd64` (if any edge is x86) and `arm64` (Pi 5). CI uses `docker buildx` for multi-arch.

**Image size target**: ≤350MB with `eclipse-temurin:21-jre-jammy` base + tailscale CLI. Smaller via `jlink` is a future optimisation, not v1.

### Runtime: docker-compose snippet

```yaml
services:
  dns-ha-agent:
    # IMMUTABLE DIGEST PIN — never use :latest in production deploys.
    # Digest is rendered into this file by the ansible role from
    # dns_ha_agent_image_digest, which is bumped on each intentional deploy.
    # Rollback = change the digest var back to a known-good value, redeploy.
    image: forgejo.homelab.internal/homelab/dns-ha-agent@sha256:DIGEST_HERE
    container_name: dns-ha-agent
    restart: unless-stopped
    network_mode: host
    volumes:
      - /etc/dns-ha-agent/config.yaml:/etc/dns-ha-agent/config.yaml:ro
      - /var/lib/dns-ha-agent:/var/lib/dns-ha-agent
      # REQUIRED: mount the host tailscaled socket so the agent can invoke
      # `tailscale set` via the LocalAPI. network_mode: host shares the network
      # namespace only — it does NOT expose the host filesystem socket.
      # Without this mount every tailscale set call will fail.
      - /var/run/tailscale/tailscaled.sock:/var/run/tailscale/tailscaled.sock
    environment:
      # Tell the Tailscale CLI where to find the socket (matches the mount path).
      TS_SOCKET: /var/run/tailscale/tailscaled.sock
    deploy:
      resources:
        limits:
          memory: 512M
          cpus: '0.5'
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
```

Host network mode is required so the agent's HTTP server binds on the node's Tailscale IP. It does **not** give the container automatic access to the host's tailscaled socket — that requires explicitly mounting `/var/run/tailscale/tailscaled.sock` (see compose snippet above). Without the socket mount the container will start and pass its own healthcheck, then fail every `tailscale set` call silently.

---

## 12. CI/CD (Forgejo Actions)

### `.forgejo/workflows/build.yml`

```yaml
name: build

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: docker
    container:
      image: eclipse-temurin:21-jdk-jammy
    steps:
      - uses: actions/checkout@v4
      - name: Run unit + integration tests
        run: ./gradlew --no-daemon test
      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: test-reports
          path: build/reports/tests/

  build-and-push:
    needs: test
    if: github.ref == 'refs/heads/main'
    runs-on: docker
    outputs:
      digest: ${{ steps.push.outputs.digest }}
    steps:
      - uses: actions/checkout@v4
      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3
      - name: Log in to Forgejo registry
        uses: docker/login-action@v3
        with:
          registry: forgejo.homelab.internal
          username: ${{ secrets.FORGEJO_REGISTRY_USER }}
          password: ${{ secrets.FORGEJO_REGISTRY_TOKEN }}
      - name: Build and push multi-arch
        id: push
        uses: docker/build-push-action@v5
        with:
          context: .
          platforms: linux/amd64,linux/arm64
          push: true
          # :latest published as a convenience pointer ONLY.
          # Ansible deploys pin by digest, not by :latest.
          tags: |
            forgejo.homelab.internal/homelab/dns-ha-agent:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max
      - name: Publish digest as build artifact
        run: |
          echo "Built digest: ${{ steps.push.outputs.digest }}"
          echo "${{ steps.push.outputs.digest }}" > digest.txt
      - name: Upload digest artifact
        uses: actions/upload-artifact@v3
        with:
          name: image-digest
          path: digest.txt

  smoke-test:
    needs: build-and-push
    runs-on: docker
    steps:
      - uses: actions/checkout@v4
      - name: Pull and smoke-test (by digest)
        run: |
          DIGEST="${{ needs.build-and-push.outputs.digest }}"
          IMAGE="forgejo.homelab.internal/homelab/dns-ha-agent@${DIGEST}"
          docker pull "${IMAGE}"

          # Generate a self-contained single-node config with no peers.
          # The probe target (127.0.0.1:53) won't resolve correctly, so the
          # agent will stay in STARTING/UNHEALTHY — that's fine. We're testing
          # that the JVM starts, the HTTP server comes up, and /health responds.
          # No external dependencies (no httpbin, no real DNS, no Tailscale).
          cat > /tmp/smoke-config.yaml << 'EOF'
          version: 1
          node:
            name: smoke-test
            region: ci
            vip: 10.253.53.53/32
          probe:
            target: 127.0.0.1
            resolve_name: cloudflare.com
            resolve_type: A
            block_name: test.blocked.internal
            interval: 2s
            query_timeout: 1s
            fail_threshold: 2
            success_threshold: 3
          peers: []
          split_brain:
            enabled: false
            peer_poll_interval: 15s
            peer_poll_timeout: 5s
            peer_unreachable_threshold: 60s
            min_peer_reports_to_self_withdraw: 2
          tailscale:
            cli_path: /usr/bin/tailscale
            preserved_routes: []
            command_timeout: 10s
          http:
            bind_address: 0.0.0.0
            port: 8091
          logging:
            format: JSON
            level: INFO
          metrics:
            enabled: true
            path: /metrics
          EOF

          # --network host so curl on the runner can reach port 8091 directly.
          docker run -d --name smoke --rm \
            --network host \
            -v /tmp/smoke-config.yaml:/etc/dns-ha-agent/config.yaml:ro \
            "${IMAGE}"

          # Poll for up to 30s — JVM startup can take a few seconds.
          for i in $(seq 1 10); do
            sleep 3
            if curl -fsS http://localhost:8091/health > /dev/null 2>&1; then
              echo "Smoke test passed (attempt ${i})"
              docker stop smoke
              exit 0
            fi
            echo "Attempt ${i}: not ready yet..."
          done

          echo "Smoke test FAILED: /health did not respond within 30s"
          docker logs smoke || true
          docker stop smoke || true
          exit 1
```

**Versioning**: every main-branch commit publishes a new image to the Forgejo registry. The `:latest` tag is published as a convenience pointer for humans browsing the registry, but **deployments pin by digest** (`sha256:...`) via the ansible variable `dns_ha_agent_image_digest`. CI exposes the digest as a job output and as a build artifact, so the new digest can be read from CI after a successful build.

**Rollback**: change `dns_ha_agent_image_digest` in ansible to a previous known-good digest, re-run the playbook. The previous artifact is still in the registry (Forgejo retention keeps the last N digests); rollback is byte-identical to the prior production state, not "revert source and hope the rebuild produces the same artifact."

**Secrets** (configured in Forgejo repo settings):
- `FORGEJO_REGISTRY_USER`
- `FORGEJO_REGISTRY_TOKEN`

---

## 13. Hosting / Registry

- **Registry**: Forgejo's built-in container registry at `forgejo.homelab.internal/homelab/dns-ha-agent`.
- **Visibility**: private to the `homelab` org.
- **Auth**: Forgejo personal access token with `write:packages` scope, stored as a CI secret. Ansible pulls with a read-only deploy token (separate token, `read:packages` scope only).
- **Tags published**: `:latest` (rolling pointer for browse-only). Deployments pin by `@sha256:DIGEST`.
- **Retention**: keep last 20 image digests (Forgejo retention policy). This is the rollback window — going further back than 20 deploys requires re-building from source. Adjust if your deploy cadence is high.

---

## 14. Tailscale Admin Policy Requirements

Before deploying, ensure the following are configured in your Tailscale admin console (or `policy.hujson` if using GitOps):

### Route auto-approval for `tag:dns-edge`

Without this, a newly deployed or re-authenticated node will advertise `10.253.53.53/32` but the route won't be active until a Tailscale admin manually approves it — silently breaking DNS failover for that node.

```json
"autoApprovers": {
  "routes": {
    "10.253.53.53/32": ["tag:dns-edge"]
  }
}
```

This grants nodes tagged `tag:dns-edge` the right to have their `10.253.53.53/32` advertisement automatically approved. Apply the tag in your node registration (`tailscale up --advertise-tags=tag:dns-edge`) or via the admin console.

### ACL for peer health polling

Peers poll each other's `/health` endpoint on port 8091. The ACL must permit this:

```json
"acls": [
  {
    "action": "accept",
    "src": ["tag:dns-edge"],
    "dst": ["tag:dns-edge:8091"]
  }
]
```

If Prometheus scrapes `/metrics` from a monitoring node, add that tag/IP to the `src` list as well.

### First-deploy checklist

Before running the ansible role on a new node:

- [ ] Node registered with `tag:dns-edge` applied.
- [ ] `autoApprovers` policy deployed to Tailscale admin.
- [ ] ACL permitting `tag:dns-edge → tag:dns-edge:8091` is active.
- [ ] Verify with: `tailscale status` on the new node shows the route as approved (not just advertised) after the agent first reaches HEALTHY.

---

## 15. Deployment (Ansible Role)

### Role structure

```
ansible/roles/dns-ha-agent/
├── defaults/main.yml
├── tasks/main.yml
├── handlers/main.yml
├── templates/
│   ├── config.yaml.j2
│   └── docker-compose.yml.j2
└── meta/main.yml
```

### `defaults/main.yml`

```yaml
dns_ha_agent_image_repo: forgejo.homelab.internal/homelab/dns-ha-agent

# MANDATORY: must be set in group_vars (or per-node host_vars).
# This is the immutable artifact pin. Bump on intentional deploys; revert
# for rollback. Read from CI build output after a successful main-branch build.
dns_ha_agent_image_digest: ""       # e.g. "sha256:abc123..."

dns_ha_agent_config_dir: /etc/dns-ha-agent
dns_ha_agent_state_dir: /var/lib/dns-ha-agent
dns_ha_agent_compose_dir: /etc/dns-ha-agent

# Per-node overrides in inventory (host_vars/dns-ams-01.yml etc.)
dns_ha_agent_node_name: "{{ inventory_hostname }}"
dns_ha_agent_node_region: ""        # MUST set in host_vars
dns_ha_agent_vip: "10.253.53.53/32"
dns_ha_agent_peers: []              # MUST set in host_vars
dns_ha_agent_http_port: 8091
# Recommended: override in host_vars with the node's Tailscale IP (100.x.x.x)
# to avoid exposing /health on the LAN interface. See §6 for details.
dns_ha_agent_bind_address: "0.0.0.0"
```

### `tasks/main.yml` (skeleton — note digest pinning)

The image reference in the docker-compose template is `{{ dns_ha_agent_image_repo }}@{{ dns_ha_agent_image_digest }}`. The role asserts that `dns_ha_agent_image_digest` is non-empty before doing anything, so accidentally running the playbook without a digest set is a hard error, not a "pulls :latest by surprise" silent regression.

```yaml
- name: Assert digest is pinned
  assert:
    that:
      - dns_ha_agent_image_digest is defined
      - dns_ha_agent_image_digest | length > 0
      - dns_ha_agent_image_digest is match("^sha256:[a-f0-9]{64}$")
    fail_msg: >
      dns_ha_agent_image_digest must be set to a sha256:... digest.
      Read it from the CI build output and set in group_vars.

# ... (rest of tasks as before — directory creation, template render, pull, compose up)
```

### `tasks/main.yml` (full)

```yaml
- name: Assert image digest is pinned (no rolling :latest in production)
  assert:
    that:
      - dns_ha_agent_image_digest is defined
      - dns_ha_agent_image_digest | length > 0
      - dns_ha_agent_image_digest is match("^sha256:[a-f0-9]{64}$")
    fail_msg: >
      dns_ha_agent_image_digest must be set to a sha256:... digest.

- name: Ensure config directory exists
  file:
    path: "{{ dns_ha_agent_config_dir }}"
    state: directory
    mode: '0755'

- name: Ensure state directory exists
  file:
    path: "{{ dns_ha_agent_state_dir }}"
    state: directory
    mode: '0700'

- name: Render agent config
  template:
    src: config.yaml.j2
    dest: "{{ dns_ha_agent_config_dir }}/config.yaml"
    mode: '0644'
  notify: restart dns-ha-agent

- name: Render docker-compose for agent (pinned by digest)
  template:
    src: docker-compose.yml.j2
    dest: "{{ dns_ha_agent_compose_dir }}/docker-compose.yml"
    mode: '0644'
  notify: restart dns-ha-agent

- name: Pull pinned image by digest
  community.docker.docker_image:
    name: "{{ dns_ha_agent_image_repo }}@{{ dns_ha_agent_image_digest }}"
    source: pull
  notify: restart dns-ha-agent

- name: Start dns-ha-agent
  community.docker.docker_compose_v2:
    project_src: "{{ dns_ha_agent_compose_dir }}"
    state: present
```

### `handlers/main.yml`

```yaml
- name: restart dns-ha-agent
  community.docker.docker_compose_v2:
    project_src: "{{ dns_ha_agent_compose_dir }}"
    state: restarted
```

### `templates/config.yaml.j2`

```jinja
version: 1

node:
  name: {{ dns_ha_agent_node_name }}
  region: {{ dns_ha_agent_node_region }}
  vip: {{ dns_ha_agent_vip }}

probe:
  target: {{ dns_ha_agent_vip | regex_replace('/.*$', '') }}
  resolve_name: cloudflare.com
  resolve_type: A
  block_name: test.blocked.internal
  interval: 2s
  query_timeout: 2s
  fail_threshold: 2
  success_threshold: 3

peers:
{% for peer in dns_ha_agent_peers %}
  - name: {{ peer.name }}
    health_url: http://{{ peer.tailscale_ip }}:{{ dns_ha_agent_http_port }}/health
{% endfor %}

split_brain:
  enabled: {{ (dns_ha_agent_peers | length) >= 2 }}
  peer_poll_interval: 15s
  peer_poll_timeout: 5s
  peer_unreachable_threshold: 60s
  min_peer_reports_to_self_withdraw: 2

tailscale:
  cli_path: /usr/bin/tailscale
  managed_routes:
    - {{ dns_ha_agent_vip }}
  preserved_routes: []
  command_timeout: 10s

http:
  bind_address: {{ dns_ha_agent_bind_address }}
  port: {{ dns_ha_agent_http_port }}

logging:
  format: JSON
  level: INFO

metrics:
  enabled: true
  path: /metrics
```

### Per-node inventory (example `host_vars/dns-ams-01.yml`)

```yaml
dns_ha_agent_node_region: eu
dns_ha_agent_peers:
  - name: dns-iom-01
    tailscale_ip: 100.64.x.y
  - name: dns-tor-01
    tailscale_ip: 100.64.a.b
  - name: dns-shz-01
    tailscale_ip: 100.64.c.d
```

---

## 16. Operational Playbook

### First-time deployment to a new edge

1. Ensure host has Docker + Compose plugin, tailscaled installed, tag `tag:dns-edge` applied.
2. **Verify host VIP binding** — before starting the agent, confirm the node has `10.253.53.53/32` bound locally and dnsdist is listening there:
   ```bash
   ip route get 10.253.53.53   # must show src via lo or dummy, NOT via tailscale
   dig @10.253.53.53 cloudflare.com +short   # must return a real IP from local dnsdist
   dig @10.253.53.53 test.blocked.internal   # must return blocked response
   ```
   If the first command routes via Tailscale, the agent's probes will test a remote node, not itself. Fix the host networking before proceeding.
3. Confirm Tailscale admin policy has `autoApprovers` for `10.253.53.53/32` on `tag:dns-edge` (§14).
4. Add node to ansible inventory with `dns_ha_agent_node_region`, `dns_ha_agent_peers`, and `dns_ha_agent_bind_address` (Tailscale IP) set.
5. Run `ansible-playbook -l <new-node> playbooks/dns-ha-agent.yml`.
6. Watch logs: `docker logs -f dns-ha-agent`.
7. Verify in `/health` that state transitions STARTING → HEALTHY after ~6s.
8. Confirm `tailscale status` on the node shows `10.253.53.53/32` advertised **and approved** (not just advertised).
9. Update peer agents' configs (add new node to their peer lists), re-run ansible against them.

### Removing an edge

1. SSH to the edge.
2. `docker compose -f /etc/dns-ha-agent/docker-compose.yml down` — agent shuts down gracefully and withdraws its route.
3. Remove from peer configs of other edges, re-run ansible against them.

### Debugging an unhealthy agent

1. `curl http://<node-ts-ip>:8091/health | jq` — see what the agent thinks.
2. `docker logs dns-ha-agent | jq` — recent state transitions and probe results.
3. Check probes directly: `dig @10.253.53.53 cloudflare.com` and `dig @10.253.53.53 test.blocked.internal`.
4. Check Tailscale CLI: `sudo tailscale status` — is the route advertised and approved?
5. If everything looks healthy but the agent isn't advertising, check container `restart: unless-stopped` is set and inspect any non-zero exit history.

### Break-glass: manual route mutation

If the agent is misbehaving and you need to force the route state:

```bash
# Stop the agent
docker stop dns-ha-agent

# Manually advertise (or withdraw with empty list)
sudo tailscale set --advertise-routes=10.253.53.53/32

# After resolving, restart the agent — it'll reconcile to its computed state
docker start dns-ha-agent
```

The agent does not fight you: on startup it goes through STARTING → HEALTHY normally based on its own probes.

### Rolling out a new agent version

1. CI publishes a new image on every main-branch commit. Read the new digest from the CI build output (or `docker pull` and `docker inspect` the `:latest` tag to extract its digest — these are equivalent because CI's `:latest` push and the digest are atomic).
2. Update `dns_ha_agent_image_digest` in `group_vars/dns-edge.yml` to the new digest. Commit the change to your infrastructure repo.
3. Canary deploy to one secondary edge:

```bash
ansible-playbook playbooks/dns-ha-agent.yml --limit dns-iom-01
# Wait, observe /health, /metrics, logs for 30 mins
```

4. If canary is healthy, roll to the rest:

```bash
ansible-playbook playbooks/dns-ha-agent.yml --limit 'dns-edge:!dns-iom-01'
```

Canary should be a secondary edge (not the primary for its region), so any breakage limits blast radius.

### Rollback

Because deploys are digest-pinned, rollback is exact and fast:

```bash
# 1. Set dns_ha_agent_image_digest back to the previous known-good digest
#    (look in git history of group_vars/dns-edge.yml, or in Forgejo's registry UI)
git revert <commit-that-bumped-digest>
# or edit group_vars manually and commit

# 2. Re-deploy. Pulls the exact previous artifact bytes.
ansible-playbook playbooks/dns-ha-agent.yml
```

The previous artifact is still in the registry (retention keeps the last 20 digests). This is byte-identical rollback, not "revert source and hope the rebuild reproduces" — important for a critical infrastructure component where a subtle base-image dep change between rebuilds could itself be the problem.

For panic-mode rollback without going through ansible:

```bash
# On affected node
docker image ls forgejo.homelab.internal/homelab/dns-ha-agent --digests
docker pull forgejo.homelab.internal/homelab/dns-ha-agent@sha256:<previous>
# Edit /etc/dns-ha-agent/docker-compose.yml to use the previous digest
docker compose -f /etc/dns-ha-agent/docker-compose.yml up -d
# Then fix the ansible vars to match, so the next playbook run doesn't undo it
```

---

## 17. Future Considerations

Explicitly out of scope for v1, to be revisited if needed:

- **GraalVM native-image build** — would cut container size to ~50MB and improve cold start. Reconsider if image pull time becomes a deployment friction.
- **Tailscale LocalAPI integration** — replace CLI subprocess with direct HTTP-over-unix-socket calls. More robust error handling, no CLI dep in image. Reconsider if subprocess error handling becomes a pain.
- **Hot config reload** — watch config file with `WatchService`, atomically swap. Complex; revisit if restart-induced downtime ever matters (it doesn't currently because graceful shutdown withdraws cleanly).
- **Quorum-based peer auto-withdrawal** — peers SSH to a broken node and withdraw its route. Adds attack surface; only consider if "wait for human" response time becomes the dominant outage component.
- **Multi-VIP support** — manage multiple VIPs from one agent (e.g., for separate internal-only DNS or service VIPs). Trivial extension; not needed v1.
- **IPv6 support** — when the rest of the HA DNS design grows IPv6, agent grows it too.
- **A proper staging tailnet** — CI tests run against real Tailscale for the mutation path, not just mocks. Would catch CLI version drift, etc.
- **Automated digest bump** — a small bot/script that, when CI publishes a new image, opens a PR against the infrastructure repo updating `dns_ha_agent_image_digest`. Reduces the manual step in deploys without losing the explicit-pin guarantee.

---

## 18. Open Questions

To be resolved during implementation, before v1.0:

1. **Container base image choice**: stick with `eclipse-temurin:21-jre-jammy` or move to `eclipse-temurin:21-jre-alpine` for size? Alpine has occasional musl-vs-glibc surprises with JNI; jammy is safer.
2. **Logback vs `java.util.logging`**: Logback is the default recommendation. JUL is bundled. If keeping deps minimal matters more than log formatting flexibility, JUL is acceptable.
3. **Whether to include a `/debug` endpoint** (current state dump, force-withdraw, force-probe) — useful for ops but adds attack surface. Probably yes but gated behind an env-var flag.
4. **What to do when the local probe target IP (10.253.53.53) is unreachable from the agent container** at all — this would mean dnsdist isn't even listening, not a soft failure. Resolve: add a distinct `DNSDIST_DOWN` probe result enum value for connection-refused / timeout-at-TCP-level (before any DNS response). Log it at ERROR with the label `probe_result=DNSDIST_DOWN` for operator visibility. Both `DNSDIST_DOWN` and generic probe failure count against `fail_threshold` identically — the distinction is diagnostic, not behavioural.

---

## 19. Acceptance Criteria for v1.0

The blueprint is implemented when:

- [ ] All unit and integration tests pass in CI.
- [ ] Smoke test in CI pulls (by digest) and runs the image successfully.
- [ ] Multi-arch image (`amd64`, `arm64`) published to `forgejo.homelab.internal/homelab/dns-ha-agent`; digest captured as CI build output.
- [ ] Ansible role asserts digest is set; refuses to deploy without one.
- [ ] Ansible role deploys cleanly to `dns-ams-01` (first canary).
- [ ] **Host VIP binding verified on every edge**: `ip route get 10.253.53.53` resolves to a local interface (lo or dummy), not via Tailscale. `dig @10.253.53.53 cloudflare.com` returns a real IP from local dnsdist. Both commands must pass on each node before the agent is started. Document this as a pre-flight step in the first-deploy playbook.
- [ ] Agent transitions STARTING → HEALTHY within 10s of container start.
- [ ] **Startup reconciliation**: if the VIP is already advertised when the agent starts (simulate by manually `tailscale set --advertise-routes=10.253.53.53/32` before starting the agent), the agent withdraws it at startup and re-advertises only after passing local probes.
- [ ] `/health` returns valid JSON matching the schema in §6.
- [ ] `/metrics` returns valid Prometheus format.
- [ ] SIGTERM withdraws route within 5s and exits 0.
- [ ] **Self-exit on hang**: forcibly hang the probe loop (e.g., inject `Thread.sleep(Long.MAX_VALUE)` in a test build). Within 30s, the `WatchdogThread` detects and the process exits non-zero. Docker `restart: unless-stopped` restarts the container.
- [ ] **Idempotent route mutation**: calling `TailscaleClient.advertiseRoutes()` twice with the same input is a no-op the second time; calling with a different input produces the expected mutation.
- [ ] **Preserved-routes handling**: a non-DNS route added externally to `preserved_routes` config is never wiped by the agent's withdraw operation.
- [ ] Inducing a filter break (stop AGH on dns-ams-01) causes the agent to withdraw within `(fail_threshold × interval) + tailscale convergence` ≈ 4s + 15s.
- [ ] Recovery after re-starting AGH: agent re-advertises within `success_threshold × interval` ≈ 6s.
- [ ] Two agents probing each other correctly detect partition when network-partitioned in a test.
- [ ] **Digest rollback** demonstrated: deploy digest A, deploy digest B, change ansible var back to A, redeploy, confirm image hash matches A exactly.
- [ ] Replaces Appendix C of `HA-DNS-Analysis.md` on every edge node without regressions.
