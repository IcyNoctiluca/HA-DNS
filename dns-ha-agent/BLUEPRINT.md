# dns-ha-agent — Service Blueprint

> Source-of-truth design and spec for the agent. For phase-ordered implementation steps and per-phase acceptance criteria, see [`PHASES.md`](PHASES.md). For the wider HA DNS context, see [`../BLUEPRINT.md`](../BLUEPRINT.md).

## 1. Purpose

`dns-ha-agent` is a small Java service deployed to every DNS edge node in the homelab HA DNS topology. Its sole responsibility is to decide whether *this* edge should currently advertise the shared VIP route (`10.253.53.53/32`) to Tailscale, based on:

1. **Local stack health** — does this node's dnsdist → AGH → Unbound chain resolve correctly AND apply filtering?
2. **Peer reachability** — can other DNS edges see this node? (Split-brain protection.)

When healthy, the agent ensures the VIP is advertised. When unhealthy (or partitioned), the agent withdraws the route. It exposes a `/health` endpoint for peer observation and a `/metrics` endpoint for Prometheus.

At the wider system level, an n8n workflow on the Mac Studio may consume this service's fail-open metrics and externally fence a node through the Tailscale API, but that controller is outside the agent process and does not change the agent's local-only mutation boundary. See [`../BLUEPRINT.md`](../BLUEPRINT.md) Appendix E and [§17](#17-external-route-fencing-via-n8n) below for the contract between the agent and n8n.

---

## 2. Goals & Non-Goals

### Goals

- **Self-contained**: each instance probes itself + peers, decides locally, mutates only its own Tailscale state. No external monitoring tool is required for HA to function.
- **Identical deployment**: same container image runs on every edge; behaviour parameterised entirely by a YAML config rendered per-node by ansible.
- **Declarative**: the only operator-facing surface is the YAML config and the ansible role.
- **Reproducible**: image is built deterministically in CI; ansible role is idempotent; production pins by image digest.
- **Observable**: structured JSON logs to stdout, `/health` endpoint for peers, `/metrics` endpoint for Prometheus.
- **Fail-safe within the local lifecycle**: graceful shutdown withdraws immediately; startup reconciliation withdraws stale advertisements before health validation; internal hangs self-exit so Docker restarts the agent. Abrupt `SIGKILL` or host-level Tailscale failures can still leave a stale route until restart or external fencing, which is why fail-open metrics are exported.

### Non-Goals

- **Cross-node state mutation by the agent**: peers never mutate each other's Tailscale state. They observe only. External n8n route fencing is a system-level remediation layer, not an agent responsibility.
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
│   │  │  DnsProbe    │──┼──────────▶│ 10.253.53.53 (local VIP via dnsdist) │ │
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
| `DnsProbe` | Runs dual probe (resolve `cloudflare.com`, confirm `test.blocked.internal` is filtered) against `10.253.53.53` on a configurable interval. |
| `PeerMonitor` | Concurrently polls each peer's `/health` endpoint, tracks reachability and reported state. One virtual thread per peer. |
| `HealthState` | Thread-safe holder of current state via atomic-ref-swap of immutable snapshots: latest probe result, fail/success counters, per-peer reachability, current advertised state. |
| `Decider` | Pure function: given `HealthState` + config, returns `ADVERTISE` or `WITHDRAW` decision. Easy to unit-test. |
| `TailscaleClient` (interface) | Abstracts the Tailscale mutation. Production impl uses subprocess to `tailscale` CLI. Tests use a fake. |
| `HealthServer` | Jersey-based HTTP server exposing `/health` (peer-facing) and `/metrics` (Prometheus). |
| `App` | Entry point: loads config, wires components, manages lifecycle (startup, graceful shutdown on SIGTERM). |
| `WatchdogThread` | Internal liveness: forces `System.exit(70)` if the probe loop hangs. Docker `restart: unless-stopped` then restarts the container. |

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
3. **Idempotency**: every `Tailscale set` call sends the full intended route list, computed from a state file. The agent never sends "delta" mutations.

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
  target: 10.253.53.53
  # A name that MUST resolve to a real A record (resolve check).
  resolve_name: cloudflare.com
  resolve_type: A
  # A name that MUST be blocked by AGH (filter check). Custom AGH rewrite.
  block_name: test.blocked.internal
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
  # Production mode is `cli`. `noop` is test-only and requires
  # DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE=true or --allow-noop-tailscale.
  # Without that env/flag, starting the agent in noop mode is a fatal error.
  mode: cli
  # Path to tailscale CLI in the container (baked into image).
  cli_path: /usr/bin/tailscale
  # Additional routes to always preserve when mutating advertise list.
  # Safety hatch for "I added a subnet route for k3s testing, don't wipe it."
  preserved_routes: []
  # Timeout for tailscale set invocations.
  command_timeout: 10s

# HTTP server config.
http:
  # Bind address for /health and /metrics. With --network host, this is
  # typically the node's Tailscale IP.
  bind_address: 0.0.0.0
  port: 8091

# Observability.
logging:
  format: JSON     # JSON or PLAIN. JSON for production.
  level: INFO

metrics:
  enabled: true
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
  "desired_vip_advertised": true,
  "fail_open": false,
  "force_withdraw_active": false,
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

**No application-layer authentication.** Access control is via Tailscale ACL (only `tag:dns-edge` nodes can reach port 8091 on edges). This is consistent with the homelab pattern of relying on tailnet boundary as the security perimeter.

### `GET /metrics`

Prometheus-format metrics. Served by `simpleclient_servlet` integration with Jersey, or via a direct mount.

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

# HELP dns_ha_agent_fail_open 1 when desired=WITHDRAW but VIP is still advertised (fail-open condition)
# TYPE dns_ha_agent_fail_open gauge
dns_ha_agent_fail_open 0

# HELP dns_ha_agent_fail_open_duration_seconds Seconds the fail-open condition has been continuously active (0 if not active)
# TYPE dns_ha_agent_fail_open_duration_seconds gauge
dns_ha_agent_fail_open_duration_seconds 0

# Standard JVM metrics (heap, GC, threads) — auto-exported by simpleclient_hotspot.
```

`dns_ha_agent_fail_open == 1` is the core invariant alert: the agent's effective decision is `WITHDRAW` but the VIP advertisement is still present (e.g., the CLI mutation failed, the Tailscale socket is unreachable, or the host's `tailscaled` is wedged). Alert immediately — this is the most dangerous failure class. The `_duration_seconds` gauge drives a time-based alert (e.g., page if sustained for >60s). n8n consumes these metrics as one input to external route fencing, but n8n must also perform independent per-edge DNS probes so a dead agent cannot hide a stale route.

---

## 7. Decision Logic

Pure function. Inputs: current `HealthState`, config. Output: `ADVERTISE` or `WITHDRAW`.

```
function decide(state, config):
    # SHUTTING_DOWN always wins
    if state.fsm == SHUTTING_DOWN:
        return WITHDRAW

    # Operator force-withdraw file overrides everything else but SHUTTING_DOWN
    if exists("/var/lib/dns-ha-agent/force-withdraw"):
        return WITHDRAW

    # PARTITIONED overrides healthy local probes
    if state.fsm == PARTITIONED:
        return WITHDRAW

    # UNHEALTHY local probes
    if state.fsm == UNHEALTHY or state.fsm == STARTING:
        return WITHDRAW

    # HEALTHY: check partition condition
    if config.split_brain.enabled and len(config.peers) > 0:
        unreachable_count = count peers where
            peer.reports_me_unreachable AND
            (now - peer.first_reported_unreachable_ts) > config.split_brain.peer_unreachable_threshold

        if unreachable_count >= config.split_brain.min_peer_reports_to_self_withdraw:
            transition state.fsm to PARTITIONED
            return WITHDRAW

    return ADVERTISE
```

The FSM transitions are evaluated on every probe cycle. The `Decider` is stateless; state is held in `HealthState`.

After each decision, the route mutation step records `desired_vip_advertised` from the decision and `vip_advertised` from `tailscaleClient.currentlyAdvertised()`. If the effective decision is `WITHDRAW` but the VIP remains in `currentlyAdvertised()` after the mutation attempt, `dns_ha_agent_fail_open` is set to `1` and `dns_ha_agent_fail_open_duration_seconds` starts/continues counting. This is the signal that n8n's external route fencing watches for.

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

### Operator override: force-withdraw

Placing an empty file at `/var/lib/dns-ha-agent/force-withdraw` forces immediate VIP withdrawal and keeps the agent in that state regardless of probe results. The agent checks for this file each probe cycle. Remove the file to resume normal operation.

```bash
# Hold a node offline (e.g. while draining for maintenance)
touch /var/lib/dns-ha-agent/force-withdraw
# Agent logs: WARN: force-withdraw file present, staying WITHDRAWN

# Resume
rm /var/lib/dns-ha-agent/force-withdraw
```

The file persists across container restarts (mounted via the `/var/lib/dns-ha-agent` volume). This is the preferred operator override over a manual `tailscale set` because it works within the agent's control loop — the agent itself stays running, continues logging and responding to `/health` (with `force_withdraw_active: true`), and re-validates as soon as the file is removed. It is also visible and auditable as a filesystem artefact.

### Error handling

| Condition | Behaviour |
|---|---|
| Local probe DNS query times out | Counts as a failure. |
| Local probe gets a real IP for the blocked name | Counts as a failure (filtering broken). |
| Tailscale CLI/socket unavailable at startup in production `cli` mode | Log fatal error and exit non-zero. A production deployment without a Tailscale mutation path is unsafe — Docker `restart: unless-stopped` will keep retrying, surfacing the misconfiguration. |
| Tailscale CLI invocation exits non-zero | Log error, retry on next decision cycle. Counter `route_mutations_total{result="failure"}` incremented. If effective decision is WITHDRAW and managed routes remain advertised, set `dns_ha_agent_fail_open=1`. |
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
| HTTP server/client | Jersey + Grizzly | jersey-container-grizzly2-http 3.1.x |
| Outbound HTTP client | JDK `java.net.http.HttpClient` (peer polling) | bundled |
| JSON | Jackson (jackson-databind, jackson-dataformat-yaml) | 2.17.x |
| DNS library | dnsjava | 3.6.x |
| Metrics | Prometheus simpleclient | 0.16.x |
| Logging | SLF4J + Logback + logstash-logback-encoder (JSON) | logback 1.5.x |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers | latest stable |
| CI | Forgejo Actions | self-hosted runner |
| Container base | `eclipse-temurin:21-jre-jammy` | rolling |

### Project structure

```
dns-ha-agent/
├── .forgejo/workflows/build.yml
├── ansible/roles/dns-ha-agent/
│   ├── defaults/main.yml
│   ├── tasks/main.yml
│   ├── templates/config.yaml.j2
│   ├── templates/docker-compose.yml.j2
│   └── handlers/main.yml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/...
├── gradlew
├── Dockerfile
├── README.md / AGENTS.md / BLUEPRINT.md / PHASES.md / PROGRESS.md
├── LICENSE
├── config/example.yaml
└── src/
    ├── main/java/io/homelab/dnsha/
    │   ├── App.java
    │   ├── config/{Config,ConfigLoader,ConfigValidator}.java
    │   ├── probe/{DnsProbe,ProbeResult,ProbeLoop}.java
    │   ├── peer/{PeerMonitor,PeerState,PeerHealthResponse}.java
    │   ├── state/{HealthState,FsmState}.java
    │   ├── decide/{Decider,Decision}.java
    │   ├── tailscale/{TailscaleClient,CliTailscaleClient,RouteStateStore}.java
    │   ├── http/{HealthServer,HealthResource,MetricsResource}.java
    │   └── lifecycle/{ShutdownHook,WatchdogThread}.java
    └── test/java/...
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

// HealthState — atomic-ref-swap of immutable snapshots
public final class HealthState {
    public void recordProbeSuccess(Duration duration);
    public void recordProbeFailure(String reason);
    public void recordPeerObservation(String peer, PeerHealthResponse response);
    public void recordPeerUnreachable(String peer);
    public FsmState currentState();
    public HealthSnapshot snapshot();   // immutable view for /health
}

// Decider — pure
public final class Decider {
    public static Decision decide(HealthState state, Config config);
}

// TailscaleClient — interface
public interface TailscaleClient {
    void advertiseRoutes(Set<String> routes) throws TailscaleException;
    Set<String> currentlyAdvertised() throws TailscaleException;
}

// TailscaleConfig — production uses cli; noop is test-only and refused unless
// DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE=true or --allow-noop-tailscale is set.
public record TailscaleConfig(
    String mode,          // "cli" | "noop"
    String cliPath,
    List<String> preservedRoutes,
    Duration commandTimeout
) { }

// ProbeLoop — runs in a virtual thread
public final class ProbeLoop implements Runnable {
    public ProbeLoop(DnsProbe probe, HealthState state, Config config);
    public void run();
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
    id("com.github.johnrengelman.shadow") version "8.1.1"
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
    implementation("org.glassfish.jersey.containers:jersey-container-grizzly2-http:3.1.5")
    implementation("org.glassfish.jersey.inject:jersey-hk2:3.1.5")
    implementation("org.glassfish.jersey.media:jersey-media-json-jackson:3.1.5")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("dnsjava:dnsjava:3.6.0")
    implementation("io.prometheus:simpleclient:0.16.0")
    implementation("io.prometheus:simpleclient_hotspot:0.16.0")
    implementation("io.prometheus:simpleclient_servlet:0.16.0")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")
}

tasks.test { useJUnitPlatform() }

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
| `Decider` | Every FSM state → correct decision. Partition logic respects peer count threshold. Empty peer list disables partition logic. |
| `HealthState` | Counters increment correctly; threshold transitions fire at right moments; snapshot is consistent under concurrent updates. |
| `DnsProbe` | Mocked dnsjava Resolver: success on real IP, failure on NXDOMAIN for resolve_name, failure on real IP for block_name, failure on timeout. |
| `RouteStateStore` | Round-trip persistence; corrupted state file → safe default (empty). |
| `CliTailscaleClient` | Subprocess invocation arguments correct; non-zero exit → exception; timeout → exception. (Uses an interface-mocked `ProcessBuilder`-equivalent.) |

### Integration tests (Testcontainers)

| Test | Setup |
|---|---|
| `EndToEndIT.healthyProbeAdvertises` | Spin up `FakeAghContainer`. Configure agent to probe it. Verify agent transitions STARTING → HEALTHY and calls `advertise`. |
| `EndToEndIT.filterBreakWithdraws` | FakeAGH returns real IP for blocked name. Agent transitions to UNHEALTHY, calls `withdraw`. |
| `EndToEndIT.peerPartitionDetected` | Two agent instances, each with the other as a peer. Network-partition one container. Confirm the partitioned one self-withdraws after threshold. |
| `EndToEndIT.gracefulShutdownWithdraws` | Send SIGTERM. Verify `withdraw` called before container exits with code 0. |
| `EndToEndIT.crashLeavesRouteStaleButRecovers` | SIGKILL one agent. Verify systemd/Docker restarts it. After restart, agent goes through STARTING → HEALTHY normally; reconciliation withdrew the stale advertisement before re-advertising. |

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
ARG TAILSCALE_VERSION=1.74.0

# Install tailscale CLI (for subprocess invocations).
# Pinned version for reproducibility; bump deliberately.
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    curl -fsSL "https://pkgs.tailscale.com/stable/tailscale_${TAILSCALE_VERSION}_amd64.tgz" \
      | tar -xz -C /tmp && \
    mv "/tmp/tailscale_${TAILSCALE_VERSION}_amd64/tailscale" /usr/bin/tailscale && \
    chmod +x /usr/bin/tailscale && \
    rm -rf /tmp/tailscale_${TAILSCALE_VERSION}_amd64 && \
    apt-get purge -y curl && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/build/libs/dns-ha-agent.jar /app/dns-ha-agent.jar
COPY config/example.yaml /etc/dns-ha-agent/example.yaml

# Container HEALTHCHECK is DIAGNOSTIC ONLY — visible in `docker ps`, scraped by
# monitoring. It does NOT trigger restarts (Docker/Compose does not auto-restart
# on unhealthy alone). Recovery from hangs comes from the agent self-exiting
# via its internal WatchdogThread, plus `restart: unless-stopped`.
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:8091/health || exit 1

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

Host network mode is required so the agent's HTTP server binds on the node's Tailscale IP and `tailscale` CLI talks to the host's tailscaled socket without explicit mounting.

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
      - name: Pull and smoke-test (by digest)
        run: |
          DIGEST="${{ needs.build-and-push.outputs.digest }}"
          IMAGE="forgejo.homelab.internal/homelab/dns-ha-agent@${DIGEST}"
          docker pull "${IMAGE}"
          docker run -d --name smoke --rm \
            -v ./test-config.yaml:/etc/dns-ha-agent/config.yaml \
            "${IMAGE}"
          sleep 30
          curl -fsS http://localhost:8091/health
          docker stop smoke
```

**Versioning**: every main-branch commit publishes a new image to the Forgejo registry. The `:latest` tag is published as a convenience pointer for humans browsing the registry, but **deployments pin by digest** (`sha256:...`) via the ansible variable `dns_ha_agent_image_digest`. CI exposes the digest as a job output and as a build artifact, so the new digest can be read from CI after a successful build.

**Rollback**: change `dns_ha_agent_image_digest` in ansible to a previous known-good digest, re-run the playbook. The previous artifact is still in the registry (Forgejo retention keeps the last 20 digests); rollback is byte-identical to the prior production state.

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

## 14. Deployment (Ansible Role)

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
```

### `tasks/main.yml`

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
  mode: cli
  cli_path: /usr/bin/tailscale
  preserved_routes: []
  command_timeout: 10s

http:
  bind_address: 0.0.0.0
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

## 15. Operational Playbook

### First-time deployment to a new edge

1. Ensure host has Docker + Compose plugin, tailscaled installed, tag `tag:dns-edge` applied.
2. Add node to ansible inventory with `dns_ha_agent_node_region` and `dns_ha_agent_peers` set.
3. Run `ansible-playbook -l <new-node> playbooks/dns-ha-agent.yml`.
4. Watch logs: `docker logs -f dns-ha-agent`.
5. Verify in `/health` that state transitions STARTING → HEALTHY after ~6s.
6. Confirm `tailscale status` on the node shows `10.253.53.53/32` advertised and approved.
7. Update peer agents' configs (add new node to their peer lists), re-run ansible against them.

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

**Preferred — force-withdraw file** (no container restart needed): if you need to hold a node offline while investigating, place the override file and the agent will withdraw and stay withdrawn while remaining fully observable:

```bash
touch /var/lib/dns-ha-agent/force-withdraw   # agent withdraws within one probe cycle (~2s)
# investigate, fix, then:
rm    /var/lib/dns-ha-agent/force-withdraw   # agent resumes normal operation
```

**Nuclear — stop the agent and mutate manually**: if the agent process itself is the problem:

```bash
# Stop the agent
docker stop dns-ha-agent

# Manually advertise (or withdraw with empty list)
sudo tailscale set --advertise-routes=10.253.53.53/32

# After resolving, restart the agent — it'll reconcile to its computed state
docker start dns-ha-agent
```

The agent does not fight you: on startup it goes through STARTING → HEALTHY normally based on its own probes. Any force-withdraw file left in place will keep it withdrawn until you remove it.

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

## 17. External Route Fencing via n8n

`dns-ha-agent` remains deliberately local-only: it mutates only its own host's Tailscale route advertisement. The wider HA DNS system adds a second remediation layer outside this service: an n8n workflow on the always-on Mac Studio can fence a bad edge by disabling that node's approved `10.253.53.53/32` route through the Tailscale API.

This section documents the contract between the agent and that external controller. The agent does not call n8n and n8n does not change the agent's local state. n8n consumes the agent's telemetry plus external checks. Full operational detail (Tailscale API secrets, fencing decision guardrails, audit log, autoApprover interaction test) lives in [`../BLUEPRINT.md`](../BLUEPRINT.md) Appendix E.

### Signals exported for n8n

- `/health`: current FSM state, `vip_advertised`, `desired_vip_advertised`, `fail_open`, `force_withdraw_active`, probe counters, peer observations, and uptime.
- `/metrics`: `dns_ha_agent_fail_open`, `dns_ha_agent_fail_open_duration_seconds`, route mutation counters, probe counters, and peer reachability gauges.
- Logs: route mutation failures and startup stale-route reconciliation events.

### n8n remediation logic (summary)

n8n must not depend solely on `dns_ha_agent_fail_open`, because a dead agent cannot report that it is fail-open. The external controller combines agent telemetry with independent per-edge probes and Tailscale route state:

```text
for each edge every 30s:
  health    = GET http://<edge-ts-ip>:8091/health  # may fail
  metrics   = GET http://<edge-ts-ip>:8091/metrics # may fail
  dns_check = dig @<edge-ts-ip> -p 5353 cloudflare.com
  block_chk = dig @<edge-ts-ip> -p 5353 test.blocked.internal
  route     = tailscale_api route state for edge + 10.253.53.53/32

  if route.enabled
     and external DNS/filtering check is unhealthy
     and (fail_open(metrics) == 1
          or agent unreachable for >60s
          or quorum peers report edge unhealthy)
     and at least one other edge is healthy:
       disable that edge's route approval via Tailscale API
       alert + write audit log
```

Re-enable is manual by default. The operator repairs the edge, verifies `/health` and direct DNS probes, then re-enables the route approval and clears n8n's fenced-state record.

### Agent-side responsibilities

The agent must make fail-open states visible and machine-readable, but it must not attempt to coordinate remote fencing:

- Maintain accurate `desired_vip_advertised` and `vip_advertised` state.
- Set `dns_ha_agent_fail_open=1` when `desired_vip_advertised=false` but any managed route remains advertised after the mutation attempt.
- Keep `/health` available while `force-withdraw` is active.
- Avoid hidden side effects that would fight n8n's fenced state. If n8n disables central route approval, the agent may continue to advertise locally once healthy; central approval/re-enable remains an operator/n8n concern.

---

## 18. Future Considerations & Open Questions

To be resolved during or after v1.0:

- **GraalVM native-image build** — would cut container size to ~50MB and improve cold start. Reconsider if image pull time becomes a deployment friction.
- **Tailscale LocalAPI integration** — replace CLI subprocess with direct HTTP-over-unix-socket calls. More robust error handling, no CLI dep in image. Reconsider if subprocess error handling becomes a pain.
- **Hot config reload** — watch config file with `WatchService`, atomically swap. Complex; revisit if restart-induced downtime ever matters (it doesn't currently because graceful shutdown withdraws cleanly).
- **Multi-VIP support** — manage multiple VIPs from one agent (e.g., for separate internal-only DNS or service VIPs). Trivial extension; not needed v1.
- **IPv6 support** — when the rest of the HA DNS design grows IPv6, agent grows it too.
- **A proper staging tailnet** — CI tests run against real Tailscale for the mutation path, not just mocks. Would catch CLI version drift, etc.
- **Automated digest bump** — a small bot/script that, when CI publishes a new image, opens a PR against the infrastructure repo updating `dns_ha_agent_image_digest`. Reduces the manual step in deploys without losing the explicit-pin guarantee.

Open implementation questions to be resolved before v1.0:

1. **Container base image choice**: stick with `eclipse-temurin:21-jre-jammy` or move to `eclipse-temurin:21-jre-alpine` for size? Alpine has occasional musl-vs-glibc surprises with JNI; jammy is safer.
2. **Logback vs `java.util.logging`**: Logback is the default recommendation. JUL is bundled. If keeping deps minimal matters more than log formatting flexibility, JUL is acceptable.
3. **Whether to include a `/debug` endpoint** (current state dump, force-withdraw, force-probe) — useful for ops but adds attack surface. Probably yes but gated behind an env-var flag.
4. **What to do when the local probe target IP (10.253.53.53) is unreachable from the agent container** at all — this would mean dnsdist isn't even listening, not a soft failure. Currently treated identically to NXDOMAIN. Worth a distinct "DNSDIST_DOWN" probe result for clearer logs.
