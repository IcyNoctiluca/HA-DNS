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
| `DnsProbe` | Runs dual probe against `10.253.53.53` on a configurable interval. Resolve check: passes if AT LEAST ONE of `probe.resolve_names` returns a valid answer. Filter check: passes if the sentinel block name returns one of `probe.block_expectation.allowed_rcodes` AND (when NOERROR) every returned address is in `allowed_ips` — fails if any returned routable address is not allowed. See §5 schema for the rationale on each setting. |
| `BackendProbe` | Runs the same resolve + filter dual probe directly against each AGH backend in the edge's regional pool, on a slower cadence (default 30s). Reuses `probe.resolve_names`, `probe.block_name`, and `probe.block_expectation` — identical filter semantics to `DnsProbe`. Surfaces broken filtering on backup backends *before* dnsdist fails over to them. Failures emit metrics and `/health` flags but do NOT trigger route withdraw — that's `DnsProbe`'s job for the active path. |
| `PeerMonitor` | Concurrently polls each peer's `/health` endpoint, tracks reachability and reported state. One virtual thread per peer. |
| `HealthState` | Thread-safe holder of current state via atomic-ref-swap of immutable snapshots: latest probe result, fail/success counters, per-peer reachability, current advertised state. |
| `FsmReducer` | Pure state-transition function. Converts probe/peer observations plus the prior health snapshot into the next FSM state. No side effects. |
| `Decider` | Pure decision function. Converts FSM state + config into `ADVERTISE` or `WITHDRAW`. No side effects. |
| `Reconciler` | Side-effecting component. Combines the `Decider` output with `tailscale.managed_routes` and `tailscale.preserved_routes` from config, observes the current Tailscale advertised route set via `TailscaleClient.currentlyAdvertised()`, and invokes `TailscaleClient.advertiseRoutes(...)` only when the actual set differs from the desired set. This is the **only** component that calls `TailscaleClient` for mutation. Computes `dns_ha_agent_fail_open` after each mutation attempt. |
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

  **Scope of this state (important):** PARTITIONED is *best-effort asymmetric-inbound-reachability protection*, not a full split-brain or quorum system. It only catches the case where this node can still reach its peers (so it can read their `unreachable_peers` lists) but peers cannot reach this node back. The fully-isolated case — where this node cannot reach any peers — is explicitly NOT covered by this state (the agent's own error-handling rules say "stay HEALTHY if local probes pass but all peers are unreachable, because there is no quorum to declare partition"). For fully-isolated nodes, recovery comes from Layer 0 (Tailscale node-level failover, ~15s) plus Layer 2 (n8n external route fencing) — see [`../BLUEPRINT.md`](../BLUEPRINT.md). PARTITIONED is therefore an opportunistic safety net, not a v1.0 production gate.
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
3. **Idempotency**: every `tailscale set` call sends the full desired route list, recomputed each cycle from config (`managed_routes` + `preserved_routes`) and the current FSM decision. The agent never sends "delta" mutations and never reads route intent from a persisted state file.

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

  # Names that MUST resolve to a real A record (resolve check).
  # The check passes if AT LEAST ONE name returns a valid answer.
  # Using more than one guards against withdrawing the route because a single
  # external authoritative resolver is transiently unhappy.
  resolve_names:
    - cloudflare.com
    - example.com
  # MUST be "A" (case-insensitive). Enforced by ConfigLoader.validate() as of
  # 2026-07-22: DualCheckEngine.answersOf() only extracts ARecord answers, so
  # any other type would silently and permanently fail the resolve/filter
  # checks even though the underlying DNS query itself succeeds. There is no
  # IPv6/AAAA support in this design (the whole stack -- VIP, Tailscale CGNAT
  # range -- is IPv4-only); this field exists to name the query type
  # explicitly rather than to make it genuinely pluggable.
  resolve_type: A

  # A name that MUST be blocked by AGH (filter check).
  #
  # IMPORTANT — pick a sentinel that resolves PUBLICLY when AGH is bypassed,
  # otherwise the probe gives false positives. If the block name is a made-up
  # internal label, Unbound returns NXDOMAIN for it whether AGH is in the path
  # or not — so a filter bypass would still look "blocked" to the agent.
  #
  # Use a subdomain you own with a real public A record, then add a custom
  # AGH block rule for it. The probe then confirms:
  #   1. dnsdist → AGH path is in use (otherwise it would resolve to the real IP), and
  #   2. AGH's blocklist machinery is working.
  block_name: dns-filter-sentinel.<your-public-domain>

  # AGH can signal blocking in several ways depending on its configuration:
  # NXDOMAIN, REFUSED, or NOERROR with a sentinel IP like 0.0.0.0. The probe
  # accepts any of these as long as the REAL public IP is not returned.
  # Returning the real public IP is always a failure (filter bypassed).
  block_expectation:
    # must_not_return_real_ip:
    #   pass if rcode is NXDOMAIN or REFUSED; OR
    #   pass if rcode is NOERROR and every returned address is in allowed_ips;
    #   fail if any returned address is the real public IP or not in allowed_ips.
    mode: must_not_return_real_ip
    allowed_rcodes:
      - NXDOMAIN
      - REFUSED
      - NOERROR
    # When rcode is NOERROR, every returned A record must be in this list.
    # AGH's default block response is 0.0.0.0; add other sentinels if you
    # configure custom block IPs.
    allowed_ips:
      - "0.0.0.0"
  # Probe cadence and thresholds.
  interval: 2s
  query_timeout: 2s
  fail_threshold: 2
  success_threshold: 3

# Per-backend filter probes.
#
# The local probe above checks 10.253.53.53, which goes through dnsdist's
# firstAvailable policy — meaning it only ever tests the CURRENTLY active
# backend. A backup AGH backend with silently broken filtering would not
# be detected until dnsdist fails over to it.
#
# This block configures direct probes to each AGH backend in this edge's
# regional pool: resolve + block sentinel, same logic as the local probe.
# Backend-probe failures DO NOT withdraw this edge's route — they only
# emit metrics. The reasoning: a backup backend with broken filtering
# does not make this edge less useful while the primary is healthy, but
# it does require operator attention before failover lands traffic there.
backend_probe:
  enabled: true
  interval: 30s
  query_timeout: 2s
  # BackendProbe reuses probe.resolve_names, probe.block_name, and
  # probe.block_expectation. No duplication; the only thing that varies
  # per backend is the target address.
  backends:
    - name: agh-ams-01
      address: 127.0.0.1:5353
    - name: agh-iom-01
      address: 100.64.x.y:5353

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

  # Optional. Explicit path to the tailscaled LocalAPI unix socket, passed to
  # every CLI invocation as `--socket=<path>`. Omit to rely on the `tailscale`
  # binary's own compiled-in default (/var/run/tailscale/tailscaled.sock on
  # Linux). Fixed 2026-07-22: the container's TS_SOCKET env var (§11) does
  # NOT configure the CLI client itself — only `tailscaled`/containerboot
  # read it (tailscale/tailscale upstream issue #14406, open as of this
  # writing) — so this field is the only real way to point the CLI at a
  # non-default socket path. Rendered from ansible's
  # dns_ha_agent_tailscale_socket (§14).
  socket_path: /var/run/tailscale/tailscaled.sock

  # Routes this agent is ALLOWED to manage (advertise when HEALTHY,
  # withdraw otherwise). In v1 this should normally contain exactly the
  # HA DNS VIP. The Reconciler treats this list as the source of truth
  # for what this agent owns — never a persisted state file.
  managed_routes:
    - 10.253.53.53/32

  # Routes that may already be advertised by the host and must NOT be wiped
  # when the agent calls `tailscale set --advertise-routes=...`.
  # Safety hatch for "I added a subnet route for k3s testing, don't wipe it."
  # The Reconciler always includes these in the desired set regardless of
  # the HEALTHY/UNHEALTHY decision.
  preserved_routes: []

  # Timeout for tailscale set invocations.
  command_timeout: 10s

# HTTP server config.
http:
  # Bind address for /health and /metrics.
  #
  # IMPORTANT: with `network_mode: host`, binding to 0.0.0.0 exposes /health
  # and /metrics on EVERY interface — including the host's LAN NIC. Tailscale
  # ACLs do not protect LAN-side traffic, so 0.0.0.0 leaks the agent's view
  # of the world to anything on the LAN that can reach the host's port 8091.
  #
  # Bind to the node's Tailscale IP instead. The Ansible role looks up
  # `ansible_tailscale0.ipv4.address` and renders it here.
  # Also add a UFW deny rule on port 8091 for non-Tailscale sources as
  # defence-in-depth (see ../PHASES.md Phase 1, step 4c).
  bind_address: "{{ tailscale_ip }}"
  port: 8091

# Observability.
logging:
  format: JSON     # JSON or PLAIN. JSON for production.
  level: INFO

metrics:
  # Set false to disable the /metrics endpoint entirely (fixed 2026-07-23 --
  # HealthServer now actually consults this; previously it was read but
  # never checked, so /metrics was always served regardless).
  enabled: true
  # MUST be "/metrics" -- enforced by ConfigLoader.validate() as of 2026-07-23.
  # MetricsResource's @Path("/metrics") is a compile-time JAX-RS annotation;
  # no other value is currently honored. This field exists to name the path
  # explicitly rather than to make it genuinely configurable.
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
  "backends": [
    {
      "name": "agh-ams-01",
      "address": "127.0.0.1:5353",
      "last_probe_ts": "2026-05-23T14:33:20Z",
      "resolve_ok": true,
      "filter_ok": true
    },
    {
      "name": "agh-iom-01",
      "address": "100.64.x.y:5353",
      "last_probe_ts": "2026-05-23T14:33:18Z",
      "resolve_ok": true,
      "filter_ok": true
    }
  ],
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

**No application-layer authentication.** Access control is layered, because Tailscale ACLs alone do not protect LAN-side traffic:

1. **Bind to Tailscale IP, not `0.0.0.0`** (`http.bind_address` defaults to `ansible_tailscale0.ipv4.address`). With `network_mode: host`, a wildcard bind would expose `/health` and `/metrics` on the host's LAN NIC too — Tailscale ACLs do not see LAN traffic, so anything on the same LAN could scrape the endpoints. Binding to the Tailscale IP closes that off at the socket level.
2. **Host firewall (UFW) deny on `:8091` from non-Tailscale sources** (see `../PHASES.md` Phase 1 step 4c). Defence-in-depth — if the bind ever drifts to `0.0.0.0` by misconfiguration, UFW still blocks the LAN path.
3. **Tailscale ACL restricts tailnet sources** — only `tag:dns-edge` (peer agents) and `tag:dns-fencer` (n8n on the Mac Studio) can reach `tag:dns-edge:8091`. This is the only layer that scopes *which* tailnet members can scrape the endpoints; the bind + UFW are about preventing non-tailnet access entirely.

Together these match the homelab pattern of tailnet boundary plus defence-in-depth at the host. There is no application-layer token because the scope of `/health` is already operational metadata, not secrets.

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

# HELP dns_ha_agent_backend_resolve_ok Per-backend resolve probe result (1=ok, 0=failing)
# TYPE dns_ha_agent_backend_resolve_ok gauge
dns_ha_agent_backend_resolve_ok{backend="agh-ams-01"} 1
dns_ha_agent_backend_resolve_ok{backend="agh-iom-01"} 1

# HELP dns_ha_agent_backend_filter_ok Per-backend filter probe result (1=ok, 0=filter broken)
# TYPE dns_ha_agent_backend_filter_ok gauge
dns_ha_agent_backend_filter_ok{backend="agh-ams-01"} 1
dns_ha_agent_backend_filter_ok{backend="agh-iom-01"} 1

# HELP dns_ha_agent_backend_probe_total Per-backend probe outcome counter
# TYPE dns_ha_agent_backend_probe_total counter
dns_ha_agent_backend_probe_total{backend="agh-ams-01",result="success"} 1820
dns_ha_agent_backend_probe_total{backend="agh-iom-01",result="success"} 1819

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

The decision pipeline is split into three explicit components so each can be reasoned about and unit-tested in isolation:

1. **`FsmReducer`** — pure state-transition function. Inputs: previous `FsmState`, latest `ProbeResult`, latest `PeerObservation` set, config. Output: next `FsmState`. No side effects.
2. **`Decider`** — pure decision function. Inputs: `FsmState`, config (force-withdraw file check, partition condition). Output: `ADVERTISE` or `WITHDRAW`. No side effects.
3. **`Reconciler`** — side-effecting. Inputs: `Decision`, config (`managed_routes`, `preserved_routes`), `TailscaleClient.currentlyAdvertised()`. Computes the **desired route set** and calls `TailscaleClient.advertiseRoutes(...)` only if it differs from the actual set. Also computes `dns_ha_agent_fail_open` after the mutation attempt.

### Decider (pure)

Inputs: current `HealthState`, config. Output: `ADVERTISE` or `WITHDRAW`.

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

### Reconciler (side-effecting)

The Reconciler computes the desired route set from config + decision and converges actual Tailscale advertised routes toward it. The agent never derives intent from observed state — observed state is only used to decide whether a mutation is needed and to compute `fail_open`.

```
function reconcile(decision, config, tailscaleClient):
    if decision == ADVERTISE:
        desired_routes = config.tailscale.managed_routes ∪ config.tailscale.preserved_routes
    else:  # WITHDRAW
        desired_routes = config.tailscale.preserved_routes

    actual_routes = tailscaleClient.currentlyAdvertised()

    if actual_routes == desired_routes:
        return                # no-op; idempotent

    try:
        tailscaleClient.advertiseRoutes(desired_routes)
        increment route_mutations_total{result="success"}
    catch TailscaleException:
        increment route_mutations_total{result="failure"}
        # fall through to fail-open check

    # After mutation attempt, re-check actual state. If decision was WITHDRAW
    # but any managed_route is still advertised, we are FAIL-OPEN — Layer 2
    # (n8n external route fencing) will pick this up via metrics.
    actual_routes = tailscaleClient.currentlyAdvertised()
    if decision == WITHDRAW and any(r in actual_routes for r in config.tailscale.managed_routes):
        dns_ha_agent_fail_open = 1
        dns_ha_agent_fail_open_duration_seconds += elapsed_since_fail_open_started
    else:
        dns_ha_agent_fail_open = 0
        dns_ha_agent_fail_open_duration_seconds = 0
```

Important properties:

- **Config-derived, not state-file-derived.** Changing `managed_routes` in config and restarting takes effect on the next reconcile; no state-file migration is required.
- **Idempotent.** If actual already matches desired, nothing is invoked. Reconciliation runs every probe cycle and corrects external drift (e.g., an operator's manual `tailscale set`).
- **Preserved routes are inviolable.** They appear in the desired set regardless of decision, so a non-DNS route added externally is never wiped by the agent's withdraw.

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
- The HTTP server is also monitored: if it fails to respond to a `/health` GET against its own configured `http.bind_address` within 5s, same self-exit path. (Not `localhost` — the agent binds to the node's Tailscale IP, not the loopback, so a loopback probe would falsely report the server down.)
- Uncaught exceptions in any virtual thread are routed to `Thread.setDefaultUncaughtExceptionHandler` which logs and exits.

**There is no Dockerfile-level `HEALTHCHECK`** (see §11). The `/health` endpoint binds to the node's Tailscale IP, which is only known at deploy time, so a Dockerfile-baked healthcheck against `localhost` would always fail in production. The healthcheck is defined in the Ansible-rendered `docker-compose.yml` with the per-node Tailscale IP templated in. That Compose-level healthcheck is **diagnostic only** — visible in `docker ps` and scraped by monitoring, but does NOT trigger a restart on its own. Recovery from hangs comes from the `WatchdogThread` + `restart: unless-stopped`, not from the healthcheck.

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
| CI | Woodpecker | self-hosted runner (H1) |
| Container base | `eclipse-temurin:21-jre-jammy` | rolling |

### Project structure

```
dns-ha-agent/
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
    │   ├── probe/{DnsProbe,BackendProbe,ProbeResult,ProbeLoop}.java
    │   ├── peer/{PeerMonitor,PeerState,PeerHealthResponse}.java
    │   ├── state/{HealthState,FsmState,FsmReducer}.java
    │   ├── decide/{Decider,Decision}.java
    │   ├── tailscale/{TailscaleClient,CliTailscaleClient,Reconciler}.java
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

// FsmReducer — pure
public final class FsmReducer {
    public static FsmState reduce(FsmState previous, ProbeResult probe,
                                  Set<PeerObservation> peers, Config config);
}

// Decider — pure
public final class Decider {
    public static Decision decide(HealthState state, Config config);
}

// Reconciler — the ONLY component that calls TailscaleClient for mutation.
// Computes desired = managed_routes ∪ preserved_routes (or just preserved_routes
// on WITHDRAW), compares against currentlyAdvertised(), invokes advertiseRoutes
// only on drift. Also sets dns_ha_agent_fail_open if WITHDRAW didn't take effect.
public final class Reconciler {
    public Reconciler(TailscaleClient client, MetricsRegistry metrics, Config config);
    public void reconcile(Decision decision);
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
| `FsmReducer` | Every (previous FSM, probe result) input pair → correct next FSM state. Threshold counters bump correctly. Empty peer list short-circuits partition transitions. |
| `Decider` | Every FSM state → correct decision. Partition logic respects peer count threshold. Empty peer list disables partition logic. Force-withdraw file overrides everything except SHUTTING_DOWN. |
| `Reconciler` | (1) ADVERTISE → calls `TailscaleClient.advertiseRoutes(managed_routes ∪ preserved_routes)`. (2) WITHDRAW → calls it with only `preserved_routes`. (3) Actual already matches desired → no call (idempotent). (4) WITHDRAW + observed `managed_routes` still present after mutation attempt → `dns_ha_agent_fail_open = 1`. (5) Observed Tailscale routes are never treated as desired state; only used to decide whether mutation is needed. (6) Changing `managed_routes` in config changes the desired set without any persisted-state migration. |
| `HealthState` | Counters increment correctly; threshold transitions fire at right moments; snapshot is consistent under concurrent updates. |
| `DnsProbe` | Mocked dnsjava Resolver. (a) Resolve: passes if at least one `resolve_names` entry returns a real A; fails only if ALL entries fail. (b) Filter: passes if rcode is in `allowed_rcodes` AND, when NOERROR, every returned address is in `allowed_ips`; fails if any returned routable address is not allowed (real public IP is always a failure). (c) Failure on timeout. (d) Malformed `block_expectation` config → ConfigLoader exits 78. |
| `BackendProbe` | Same as `DnsProbe` but targeted at each configured backend address; failures do NOT influence FSM. |
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

# TARGETARCH is set automatically by `docker buildx` per platform.
ARG TARGETARCH
ARG TAILSCALE_VERSION=1.74.0

# Install tailscale CLI (for subprocess invocations) and wget.
# wget is kept in the final image because the Ansible-rendered Compose-level
# healthcheck invokes `wget -qO- http://<tailscale_ip>:8091/health`. curl is
# only needed at build time to fetch the Tailscale tarball, and is purged
# afterwards to keep the runtime image lean.
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl wget ca-certificates && \
    case "$TARGETARCH" in \
      amd64) TS_ARCH=amd64 ;; \
      arm64) TS_ARCH=arm64 ;; \
      *) echo "unsupported TARGETARCH: $TARGETARCH" && exit 1 ;; \
    esac && \
    curl -fsSL "https://pkgs.tailscale.com/stable/tailscale_${TAILSCALE_VERSION}_${TS_ARCH}.tgz" \
      | tar -xz -C /tmp && \
    mv "/tmp/tailscale_${TAILSCALE_VERSION}_${TS_ARCH}/tailscale" /usr/bin/tailscale && \
    chmod +x /usr/bin/tailscale && \
    rm -rf "/tmp/tailscale_${TAILSCALE_VERSION}_${TS_ARCH}" && \
    apt-get purge -y curl && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/build/libs/dns-ha-agent.jar /app/dns-ha-agent.jar
COPY config/example.yaml /etc/dns-ha-agent/example.yaml

# NO Dockerfile-level HEALTHCHECK.
#
# The agent binds /health to the node's Tailscale IP (NOT 0.0.0.0), so the
# bind address is not known at image-build time and a localhost healthcheck
# would always fail. The HEALTHCHECK is defined in the Ansible-rendered
# docker-compose.yml instead, where the Tailscale IP can be templated in:
#
#   healthcheck:
#     test: ["CMD", "wget", "-qO-",
#            "http://{{ ansible_tailscale0.ipv4.address }}:8091/health"]
#
# See §11 docker-compose snippet below and §14 ansible role.
# Reminder: HEALTHCHECK is DIAGNOSTIC ONLY — visible in `docker ps`, scraped
# by monitoring. It does NOT trigger restarts (Docker/Compose does not
# auto-restart on unhealthy alone). Recovery from hangs comes from the
# agent self-exiting via its internal WatchdogThread + `restart: unless-stopped`.

EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/dns-ha-agent.jar", "--config", "/etc/dns-ha-agent/config.yaml"]
```

Two architectures need to be built: `amd64` (if any edge is x86) and `arm64` (Pi 5). CI uses `docker buildx --platform linux/amd64,linux/arm64` for multi-arch — `TARGETARCH` is set automatically by buildx for each platform target, so the same Dockerfile produces the right Tailscale binary on both.

**Image size target**: ≤350MB with `eclipse-temurin:21-jre-jammy` base + tailscale CLI. Smaller via `jlink` is a future optimisation, not v1.

### Runtime: docker-compose snippet

```yaml
services:
  dns-ha-agent:
    # IMMUTABLE DIGEST PIN — never use :latest in production deploys.
    # Digest is rendered into this file by the ansible role from
    # dns_ha_agent_image_digest, which is bumped on each intentional deploy.
    # Rollback = change the digest var back to a known-good value, redeploy.
    image: git.bnet.internal/liamp/dns-ha-agent@sha256:DIGEST_HERE
    container_name: dns-ha-agent
    restart: unless-stopped
    network_mode: host
    environment:
      # Documentation-only (fixed 2026-07-22): TS_SOCKET is read by
      # `tailscaled`/the official image's `containerboot` entrypoint, NOT by
      # the `tailscale` CLI client (tailscale/tailscale upstream issue
      # #14406, open as of this writing). This container runs neither of
      # those — it bind-mounts the host's already-running tailscaled.sock and
      # subprocesses the CLI against it — so this env var has no functional
      # effect on socket resolution either way. The line that actually makes
      # a non-default socket path work is `tailscale.socket_path` in the
      # rendered config.yaml (§5), passed to the CLI as `--socket=...` by
      # CliTailscaleClient.
      TS_SOCKET: /var/run/tailscale/tailscaled.sock
    volumes:
      - /etc/dns-ha-agent/config.yaml:/etc/dns-ha-agent/config.yaml:ro
      - /var/lib/dns-ha-agent:/var/lib/dns-ha-agent
      # REQUIRED: the in-container tailscale CLI talks to the host's tailscaled
      # via its LocalAPI Unix socket. `network_mode: host` does NOT expose the
      # host filesystem socket — without this explicit mount, every
      # `tailscale set --advertise-routes=...` invocation will fail and the
      # agent will not be able to perform its core function. The CLI is told
      # to look here via `tailscale.socket_path` in config.yaml (§5), not via
      # the TS_SOCKET env var above (which this container's CLI ignores).
      - /var/run/tailscale/tailscaled.sock:/var/run/tailscale/tailscaled.sock
    deploy:
      resources:
        limits:
          memory: 512M
          cpus: '0.5'
    # HEALTHCHECK lives here (not in the Dockerfile) because the agent binds
    # to the node's Tailscale IP and that IP isn't known at image-build time.
    # Ansible renders {{ tailscale_ip }} per-node from ansible_tailscale0.
    # Diagnostic only — does NOT trigger restart; see WatchdogThread.
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://{{ tailscale_ip }}:8091/health"]
      interval: 10s
      timeout: 3s
      start_period: 20s
      retries: 3
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
```

Two things make this layout work:

1. **`network_mode: host`** — so the agent's HTTP server binds on the node's Tailscale IP (peer-reachable) and the local DNS probe to `10.253.53.53` follows the host's loopback alias.
2. **Bind-mounted `tailscaled.sock`** — the `tailscale` CLI is a thin client over the LocalAPI Unix socket on the host (`/var/run/tailscale/tailscaled.sock`). Host networking alone does NOT expose this file into the container; it must be mounted explicitly. Without it, route mutation fails immediately and the agent enters fail-open. The CLI is pointed at the mounted path via `tailscale.socket_path` in config.yaml (§5) / `--socket=...` (fixed 2026-07-22) — NOT via the `TS_SOCKET` env var, which only the official Tailscale Docker image's own `containerboot` entrypoint reads, not the plain `tailscale` CLI client (tailscale/tailscale upstream issue #14406).

Alternative: run the agent as a host systemd service rather than a container. Lower operational tax for socket access, but loses the digest-pinned, byte-identical-rollback story we get from Docker. v1 sticks with the container + socket mount; revisit if the socket mount turns out to be brittle in practice.

---

## 12. CI/CD (Woodpecker)

**Engine**: [Woodpecker](../../docs/k3s/woodpecker.md) at `ci.bnet.internal`, OAuth-wired to Forgejo. **Not Forgejo Actions** — that's deliberately disabled on this Forgejo instance (`actions.ENABLED=false`, by name, so it can't quietly stand up a competing CI system; see the Battlenet infra repo's `docs/k3s/forgejo.md` §4). An earlier draft of this section assumed Forgejo Actions and used GitHub-Actions-shaped syntax (`uses:`, `runs-on`, job outputs); that was never runnable against the real instance and has been replaced below.

The actual pipeline lives at [`../.woodpecker.yml`](../.woodpecker.yml) (repo root, not `dns-ha-agent/`, since that's where Woodpecker looks by default). Read that file for the literal, current definition — this section is a summary, not a second copy, so the two can't drift.

**Shape**, same intent as the original design:
- `test`: `./gradlew --no-daemon check` in `eclipse-temurin:21-jdk-jammy`. Runs on every push and PR.
- `pr-smoke-test` (PRs only): local single-arch `docker build` + boot + `/health`/`/metrics` check, without needing registry write access.
- `build-and-push` (`main` only): multi-arch (`linux/amd64`, `linux/arm64`) `docker buildx build --push` to the Forgejo registry, tagged `:latest`. The manifest digest is resolved with `docker buildx imagetools inspect` and written to `digest.txt` in the shared workspace — Woodpecker has no GitHub-Actions-style `needs.<job>.outputs.<x>` mechanism, so passing the digest to the next step is a plain file, not a job output.
- `smoke-test` (`main` only): pulls the image **by digest** (from `digest.txt`), boots it, polls `/health` with retry, asserts `/metrics`.

Docker-in-Docker is required because Woodpecker step containers don't get host Docker access by default — a `services.docker` sidecar (`docker:24-dind`, `privileged: true`) provides one, reachable at `tcp://docker:2375`. This is why the repo must be marked **trusted** in Woodpecker's own repo settings (privileged containers are opt-in per repo). `linux/arm64` (Pi 5 edges) additionally needs QEMU registered on the runner via `tonistiigi/binfmt --install all`, since H1 (the only registered runner, per [`woodpecker.md`](../../docs/k3s/woodpecker.md) §3) is x86.

**Versioning**: every main-branch commit publishes a new image to the Forgejo registry. The `:latest` tag is published as a convenience pointer for humans browsing the registry, but **deployments pin by digest** (`sha256:...`) via the ansible variable `dns_ha_agent_image_digest`. CI exposes the digest as a workspace file (`digest.txt`) inside the same pipeline run; there is no cross-run artifact store to read it from later, so a human rolling forward reads it from the `build-and-push` step's own log line (`Built digest: ...`) or `docker buildx imagetools inspect` against `:latest` directly.

**Rollback**: change `dns_ha_agent_image_digest` in ansible to a previous known-good digest, re-run the playbook. The previous artifact is still in the registry (Forgejo retention keeps the last 20 digests); rollback is byte-identical to the prior production state.

**Secrets** (configured in Woodpecker's per-repo secret store, not Forgejo — Woodpecker does not read Forgejo Actions secrets):
- `forgejo_registry_user`
- `forgejo_registry_token`

---

## 13. Hosting / Registry

- **Registry**: Forgejo's built-in container registry at `git.bnet.internal/liamp/dns-ha-agent`. (No `homelab` org exists on this Forgejo instance — repos and their packages live under the `liamp` user, per the pattern documented in `docs/k3s/forgejo.md` §3 of the Battlenet infra repo.)
- **Visibility**: private, owned by `liamp`.
- **Auth**: Forgejo personal access token with `write:package` scope, stored as a Woodpecker CI secret (`forgejo_registry_token`). Ansible pulls with a read-only deploy token (separate token, `read:package` scope only).
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
dns_ha_agent_image_repo: git.bnet.internal/liamp/dns-ha-agent

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

# Probe schema settings. The defaults are good for most deployments.
# `dns_ha_agent_filter_sentinel` MUST be set group-wide and must match the
# sentinel A record + AGH block rule from `../PHASES.md` Phase 0.
dns_ha_agent_resolve_names:
  - cloudflare.com
  - example.com
dns_ha_agent_filter_sentinel: ""    # MUST set in group_vars (e.g. "dns-filter-sentinel.example.com")

# Routes the agent is allowed to manage / must preserve. v1 defaults match
# the VIP; preserved_routes is a per-host safety hatch.
dns_ha_agent_preserved_routes: []
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

- name: Assert filter sentinel is configured (must match Phase 0 setup)
  assert:
    that:
      - dns_ha_agent_filter_sentinel is defined
      - dns_ha_agent_filter_sentinel | length > 0
    fail_msg: >
      dns_ha_agent_filter_sentinel must be set to a real public DNS name
      that is blocked by AGH (see ../PHASES.md Phase 0).

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
  resolve_names:
{% for name in dns_ha_agent_resolve_names | default(['cloudflare.com', 'example.com']) %}
    - {{ name }}
{% endfor %}
  resolve_type: A
  block_name: {{ dns_ha_agent_filter_sentinel }}
  block_expectation:
    mode: must_not_return_real_ip
    allowed_rcodes:
      - NXDOMAIN
      - REFUSED
      - NOERROR
    allowed_ips:
      - "0.0.0.0"
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
  # socket_path fixed 2026-07-22 to actually make dns_ha_agent_tailscale_socket
  # a real per-node override — see §5 and CliTailscaleClient javadoc for why
  # the TS_SOCKET env var in docker-compose.yml.j2 doesn't do this on its own.
  socket_path: {{ dns_ha_agent_tailscale_socket }}
  managed_routes:
    - {{ dns_ha_agent_vip }}
  preserved_routes: {{ dns_ha_agent_preserved_routes | default([]) | to_json }}
  command_timeout: 10s

http:
  # Bind to the node's Tailscale IP (NOT 0.0.0.0) — with network_mode: host,
  # binding to wildcard exposes /health and /metrics on the LAN NIC too.
  bind_address: {{ ansible_tailscale0.ipv4.address }}
  port: {{ dns_ha_agent_http_port }}

logging:
  format: JSON
  level: INFO

metrics:
  enabled: true  # see §5 -- HealthServer now honors this (fixed 2026-07-23)
  path: /metrics  # MUST stay "/metrics" -- see §5
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
3. Check probes directly: `dig @10.253.53.53 cloudflare.com` and `dig @10.253.53.53 dns-filter-sentinel.<your-public-domain>`.
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
docker image ls git.bnet.internal/liamp/dns-ha-agent --digests
docker pull git.bnet.internal/liamp/dns-ha-agent@sha256:<previous>
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

  # PRIMARY external signal — full dnsdist → AGH → Unbound path
  # via this edge's dnsdist diagnostic listener on :1053.
  dns_check = dig @<edge-ts-ip> -p 1053 cloudflare.com
  block_chk = dig @<edge-ts-ip> -p 1053 dns-filter-sentinel.<your-public-domain>

  # SECONDARY diagnostic — AGH-only path on :5353.
  # Not the fencing signal: dnsdist down + AGH up still needs fencing.
  agh_check = dig @<edge-ts-ip> -p 5353 cloudflare.com

  route     = tailscale_api route state for edge + 10.253.53.53/32

  if route.enabled
     and dns_check is unhealthy           # the :1053 result, not :5353
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
