# HA DNS — Design Blueprint

> Source-of-truth design for the HA DNS layer. For implementation phases and rollout steps, see [`PHASES.md`](PHASES.md). For agent (dns-ha-agent) specifics, see [`dns-ha-agent/BLUEPRINT.md`](dns-ha-agent/BLUEPRINT.md).

## Context & Goals

The v0 setup listed 6 Pi-hole instances directly as Tailscale global nameservers. This is not HA — Tailscale does not guarantee resolver order, and iOS in particular loses DNS entirely when the first-listed resolver goes offline rather than falling through to the others. The Mac working while iOS fails is not evidence of resilience; macOS handles resolver failures more gracefully and may be serving cached results.

**Goals:** DNS survives any individual node failure and any single-region power outage. AdGuard Home filtering remains active on all HA failover paths — the design fails closed: if all AGH backends are unavailable, DNS breaks rather than silently serving unfiltered responses. Per-LAN Pi-hole filtering and metrics are preserved independently and are not affected by the HA DNS design. The design must be manageable at homelab scale.

**Public cloud DNS edges deliberately excluded.** Running a DNS edge on a cloud VPS (DigitalOcean, Vultr, etc.) would mean DNS queries are processed on third-party infrastructure. All edge nodes are homelab-owned machines.

**Hard constraints from the Tailscale model:**
- Tailscale IPs are node-bound — they cannot float between machines the way a VRRP VIP can on a LAN. The VIP approach described below works around this.
- Tailscale subnet-route failover is active/passive by default (~15 second switchover). The oldest-enrolled edge node becomes primary; there is no manual preference setting.
- Regional routing (clients prefer nearest edge) is Premium/Enterprise only.
- Tailscale detects node-level failure (machine offline), not service-level failure (dnsdist crashed). A health agent that withdraws the route advertisement on service failure is mandatory.
- Tailscale nameserver config requires IP addresses, not hostnames. MagicDNS cannot create custom records pointing to the VIP.
- All edge nodes must advertise the exact same `/32` prefix for failover to work. Mixed prefix lengths break the HA guarantee.
- `--accept-dns=false` is required on every DNS edge node — the resolver must not depend on the service it is providing.

---

## Chosen Design

### Two Independent Systems

This design deliberately keeps per-LAN DNS and central HA DNS as separate concerns. The existing Pi-holes on each LAN are not modified or pointed at the central DNS — they remain isolated. The central HA DNS is a new layer, used only by devices that explicitly opt in (i.e. Tailscale clients configured with the VIP as their nameserver).

```
System 1 — Per-LAN Pi-hole (existing, unchanged)
  LAN client → Pi-hole (eth0:53) → Unbound (127.0.0.1:5335) → Internet
  Purpose: per-LAN adblocking, per-client metrics, DHCP-assigned
  Consumers: all LAN devices by default

System 2 — Central HA DNS (new)
  Tailscale client → 10.253.53.53 → dnsdist → AdGuard Home pool → Unbound → Internet
  Purpose: highly available, tailnet-wide DNS with filtering
  Consumers: opt-in — Tailscale clients pointing at 10.253.53.53
```

Both systems can run on the same physical machine without conflict, as detailed in the port layout below.

---

### The Core Mechanism

Pick a private IP that overlaps with none of your LANs, bind it on the loopback of every DNS edge node, and have each node advertise it as a Tailscale subnet route. Tailscale DNS points at this single IP. Tailscale handles node-level failover transparently; dnsdist handles backend health-checking; AdGuard Home instances (kept identical via adguardhome-sync) provide filtering and are fully fungible. The `dns-ha-agent` running on each edge handles the fast service-level self-withdraw path. A separate n8n workflow on the always-on Mac Studio provides the slower external route-fencing path for the dangerous case where a broken edge remains advertised despite the agent deciding it should withdraw.

```
Reserved:    10.253.53.0/24  (tailnet service VIPs — never assign to LANs, DHCP, Docker, or k3s)
HA DNS VIP:  10.253.53.53/32

Tailscale DNS → 10.253.53.53 (single entry, Override DNS enabled)
```

This address range avoids conflicts with common home ranges (`192.168.x.x`), common lab/cloud ranges (`10.0–10.10.x.x`), and Tailscale's own CGNAT block (`100.64.0.0/10`). The whole `/24` is reserved so future tailnet service VIPs can draw from it without collision risk.

The HA model is deliberately layered:

| Layer | Handles | Mechanism |
|---|---|---|
| **Layer 0 — Tailscale node-level failover** | Machine offline, Tailscale down, power loss | Tailscale stops routing to the disconnected subnet router and converges to another advertised edge. |
| **Layer 1 — dns-ha-agent self-withdraw** | dnsdist/AGH/Unbound failure while the node is still online | Local agent detects service failure and withdraws this node's `10.253.53.53/32` advertisement. |
| **Layer 2 — n8n external route fencing** | Fail-open cases where the node is unhealthy but still advertised | n8n corroborates the failure from outside the node and disables that node's route approval via the Tailscale API. |

Layer 2 is not the primary failover path. It is a remediation layer for the specific zombie-advertisement failure class: `desired=WITHDRAW` but the VIP remains advertised/approved (e.g., the agent crashed via SIGKILL, the Tailscale socket is unreachable from the container, or the CLI mutation persistently fails).

**Tailscale tag and route auto-approval** — each DNS edge node is tagged `tag:dns-edge`. The ACL policy grants this tag permission to advertise `10.253.53.53/32` and marks it for automatic approval. This is required because `dns-ha-agent` will withdraw and re-advertise the route on service failures — manual approval in the admin console on every cycle would make the agent useless in practice.

```json
// policy.hujson (add to your Tailscale ACL)
{
  "tagOwners": {
    "tag:dns-edge": ["autogroup:admin"]
  },
  "autoApprovers": {
    "routes": {
      "10.253.53.53/32": ["tag:dns-edge"]
    }
  }
}
```

The same ACL also restricts both the agent's peer-health endpoint (TCP 8091) and AGH's admin/API port (TCP 3000). Peer health is restricted to other `tag:dns-edge` nodes; AGH admin is restricted to the Mac Studio (running adguardhome-sync) and to explicit admin devices.

```json
{
  "tagOwners": {
    "tag:dns-edge":    ["autogroup:admin"],
    "tag:agh-admin":   ["autogroup:admin"],
    "tag:dns-fencer":  ["autogroup:admin"]
  },
  "acls": [
    {
      "action": "accept",
      "src":    ["tag:dns-edge", "tag:dns-fencer"],
      "dst":    ["tag:dns-edge:8091"]
    },
    {
      "action": "accept",
      "src":    ["tag:dns-edge", "tag:dns-fencer"],
      "dst":    ["tag:dns-edge:1053"]
    },
    {
      "action": "accept",
      "src":    ["tag:agh-admin"],
      "dst":    ["tag:dns-edge:3000"]
    }
  ]
}
```

Apply `tag:agh-admin` to the Mac Studio (sync daemon) and any admin laptops/devices. Apply `tag:dns-fencer` to the always-on Mac Studio/n8n host so it can poll edge `/health` and `/metrics` endpoints. All other tailnet clients are denied by default (Tailscale ACLs are deny-by-default once any policy is defined). Note: Tailscale ACLs do not affect LAN traffic — host firewall rules (see [`PHASES.md`](PHASES.md) Phase 1) also restrict `:3000` from the local LAN.

---

### External Route Fencing via n8n

`dns-ha-agent` is still the fast path: healthy nodes advertise, unhealthy nodes self-withdraw. The n8n workflow is a slower, external safety path for **fail-open** states where the agent or host can no longer perform its own withdrawal (SIGKILL'd agent, container that lost its Tailscale socket mount, persistent CLI mutation failures, host kernel hang, etc.).

The n8n workflow runs on the always-on Mac Studio and polls each edge every 30 seconds. For each edge, it combines three classes of evidence:

1. **Agent self-report** — `/health` and `/metrics`, especially `dns_ha_agent_fail_open` and `dns_ha_agent_fail_open_duration_seconds`.
2. **External per-edge DNS probe** — query that edge directly over its Tailscale IP, not the shared VIP. Initially this can probe AGH on `<EDGE_TS_IP>:5353`; a future dnsdist diagnostic listener on `<EDGE_TS_IP>:1053` would test the full dnsdist → AGH → Unbound path.
3. **Tailscale route state** — the node's `10.253.53.53/32` route is still advertised/approved/enabled according to the Tailscale API.

n8n may fence an edge only when all guardrails pass:

```text
route_enabled_for_edge == true
AND edge_externally_unhealthy == true
AND (
  dns_ha_agent_fail_open == 1
  OR agent_unreachable_for > threshold
  OR quorum_peers_report_unhealthy
)
AND at_least_one_other_edge_healthy == true
AND failure_persists_for >= 60s
```

The remediation action is to disable that specific node's approved `10.253.53.53/32` route via the Tailscale API. This bypasses the failed node's local Docker, `tailscaled` socket, and Tailscale CLI path. n8n must alert and write an audit record containing node, evidence, action, API response, and timestamp.

Re-enable is **manual by default**. A fenced node should be repaired, verified healthy, and then re-enabled by an operator. Automatic re-enable is intentionally deferred to avoid flapping a partially repaired node back into the active DNS path.

Important caveat: test the interaction between `autoApprovers` and API-based route disabling. If a disabled route is immediately re-approved because the node still has `tag:dns-edge`, use a separate active tag pattern (`tag:dns-edge-active`) or keep n8n's fenced state active until manual clearance.

Full operational detail — Tailscale API secret handling, per-edge evidence collection commands, audit log format, and the autoApprover interaction test — is in [Appendix E](#appendix-e-external-route-remediation-via-n8n).

---

### Inventory Role Mapping

| Role | Machine | Site | Region | Notes |
|---|---|---|---|---|
| **DNS edge node** | Pi 5 Rack D | AMS-01 | EU | Always-on, wired, Linux. Primary EU edge. Also runs Pi-hole for AMS LAN. High tinkering risk — IOM edge provides EU continuity when AMS is broken. |
| **DNS edge node** | IOM-01 Pi 5 | IOM-01 | EU | EU secondary edge. Slower WAN but reliable. Advertises VIP; runs dnsdist + dns-ha-agent + AGH. Activates as EU primary when AMS is down. |
| **DNS edge node** | TOR-01 Pi 5 | TOR-01 | NA | Always-on, 1 Gbps WAN. Sole NA edge. Also runs Pi-hole for TOR-01 LAN. |
| **DNS edge node** | SHZ-01 Pi 5 | SHZ-01 | APAC | Sole APAC edge. Also runs the only APAC AGH instance. No intra-region failover. Agent runs with zero peers configured (no partition detection). |
| **AGH backend only** | TOR-02 Pi 5 | TOR-02 | NA | NA AGH backend. Does not advertise VIP, run dnsdist, or run dns-ha-agent. Also runs Pi-hole for TOR-02 LAN. |
| **AGH backend only (future)** | HOU-01 Pi 5 | HOU-01 | NA | NA AGH backend once provisioned. Does not advertise VIP. Candidate for Technitium evaluation — see Appendix G. |
| **AGH config sync daemon** | Mac Studio | AMS-01 | — | Runs adguardhome-sync in Docker (OrbStack). Pulls from dns-ams-01 AGH, pushes to all replicas globally. |
| **External route-fencing controller** | Mac Studio | AMS-01 | — | Runs the n8n workflow. Detects fail-open DNS edges and disables that edge's VIP route approval through the Tailscale API. See [Appendix E](#appendix-e-external-route-remediation-via-n8n). |
| **Not involved** | k3s Rack A/B/C | AMS-01 | — | DNS must not depend on k3s availability. |
| **Not involved** | All wake-on-demand machines | AMS-01 | — | Never advertise the VIP from machines that sleep or reboot casually. |
| **Not a DNS edge** | Mac Studio | AMS-01 | — | Sync/fencing controller role only. Too many critical services to also be a DNS edge; macOS loopback VIP binding is more complex. |

---

### Per-Node Service Stack

Each DNS edge node runs the following in parallel with its existing Pi-hole:

```
Pi-hole          — binds on eth0:53 only (BIND mode, not wildcard); per-LAN filtering + metrics
AdGuard Home     — binds on 127.0.0.1:5353 + Tailscale IP:5353; dnsdist backend; not exposed to LAN
Unbound          — binds on 127.0.0.1:5335; recursive resolver + DNSSEC; used by both Pi-hole and AGH
dnsdist          — binds on 10.253.53.53:53 (loopback alias); routes to AGH pool
dns-ha-agent     — containerised Java service; probes local stack + peers, mutates Tailscale route
                   advertisement on failure. See dns-ha-agent/BLUEPRINT.md for full spec.
Tailscale        — --accept-dns=false; tagged tag:dns-edge for auto-approved route advertisement
```

**Port layout — no conflicts:**

| Process | Interface | Port | Purpose |
|---|---|---|---|
| Pi-hole | eth0 only (BIND mode — not wildcard) | 53 | LAN DNS, per-LAN metrics |
| AdGuard Home | 127.0.0.1 + Tailscale IP | 5353 | dnsdist backend (not exposed to LAN) |
| AdGuard Home admin | 127.0.0.1 + Tailscale IP | 3000 | Admin/API, restricted by Tailscale ACL + host firewall |
| Unbound | 127.0.0.1 | 5335 | Recursive resolver (shared by Pi-hole and AGH) |
| dnsdist | 10.253.53.53 (loopback alias) | 53 | HA DNS entry point |
| dns-ha-agent | Tailscale IP (via --network host) | 8091 | Peer-facing /health + /metrics endpoints |

Pi-hole **must** be set to `BIND` listening mode (`pihole-FTL --config dns.listeningMode BIND`) so it binds only to the LAN interface — not the wildcard. Without this, Pi-hole claims `0.0.0.0:53` and dnsdist cannot bind to `10.253.53.53:53` on the same machine.

Pi-hole's upstream is set to `127.0.0.1#5335` (local Unbound). AGH's upstream is also set to `127.0.0.1#5335`. Both filtering layers share the same recursive resolver without interfering with each other.

---

### Regional Topology

AGH backends are organised by region. Each DNS edge routes only to AGH instances within its own region — a regional AGH outage does not affect other regions. All AGH instances remain globally identical for filtering behaviour via adguardhome-sync; only the routing is regional.

```
EU region  — 2 edges, 2 AGH backends
  dns-ams-01 (NL)  →  pool: agh-ams-01 (order=10), agh-iom-01 (order=20)
  dns-iom-01 (IOM) →  pool: agh-iom-01 (order=10), agh-ams-01 (order=20)

NA region  — 1 edge, 3 AGH backends
  dns-tor-01 (TOR) →  pool: agh-tor-01 (order=10), agh-tor-02 (order=20), agh-hou-01 (order=30, future)

APAC region  — 1 edge, 1 AGH backend
  dns-shz-01 (SHZ) →  pool: agh-shz-01 (order=10)
```

No cross-region fallback is configured. If an entire regional AGH pool is unavailable, DNS fails for clients routed to that region's edge — which is the correct fail-closed behaviour. Tailscale will route those clients to the next available edge (most likely EU) within ~15s, where EU AGH will serve them correctly.

A note on Tailscale routing: on the free tier, there is no geographic client-to-edge affinity guarantee. Tailscale's HA subnet-router behaviour selects one active node per subnet; when that node is unreachable, Tailscale converges to *an* available edge — but on the free tier, the destination should be treated as implementation-selected, not necessarily geographically nearest. Regional routing preference is an Enterprise feature.

This matters because the regional AGH pool model assumes a client routed to NA gets NA AGH. In reality, the active subnet router may be one edge for all clients regardless of their location. That is not necessarily a problem (filtering is identical across regions thanks to adguardhome-sync, only latency differs), but "regional pools" are edge-local pools, not guaranteed client-local pools.

---

### AdGuard Home Backend Pool

All AGH instances are kept globally identical by adguardhome-sync — same blocklists, allowlists, rewrites, and client settings — making them fully fungible within their region. Configuration changes are made once on dns-ams-01 and propagate to all replicas within 5 minutes.

```
dns-ams-01 AGH   ← primary (make all config changes here)
      ↓  adguardhome-sync (Mac Studio Docker, global sync)
dns-iom-01 AGH   ← EU replica
dns-tor-01 AGH   ← NA replica
dns-tor-02 AGH   ← NA replica
dns-shz-01 AGH   ← APAC replica
dns-hou-01 AGH   ← NA replica (when provisioned)
```

adguardhome-sync replicates blocklists, allowlists, rewrites, and client settings. It does not replicate query logs — each instance's query log reflects only traffic routed to it by dnsdist.

---

### dnsdist Backend Pool

Each edge's dnsdist routes only to its regional AGH pool. There is no unfiltered Unbound fallback — if all regional AGH backends are down, DNS fails for that edge. This is deliberate: silent failover to unfiltered DNS contradicts the stated goal and masks a serious infrastructure problem.

```
dns-ams-01 pool (EU):
  agh-ams-01  →  127.0.0.1:5353      (local, order=10)
  agh-iom-01  →  IOM01_TS_IP:5353   (order=20)

dns-iom-01 pool (EU):
  agh-iom-01  →  127.0.0.1:5353      (local, order=10)
  agh-ams-01  →  AMS01_TS_IP:5353   (order=20)

dns-tor-01 pool (NA):
  agh-tor-01  →  127.0.0.1:5353      (local, order=10)
  agh-tor-02  →  TOR02_TS_IP:5353   (order=20)
  agh-hou-01  →  HOU01_TS_IP:5353   (order=30, add when provisioned)

dns-shz-01 pool (APAC):
  agh-shz-01  →  127.0.0.1:5353      (local, order=10)
```

Adding a new AGH backend to a region means adding one line to each edge in that region's dnsdist.conf and restarting dnsdist.

---

### Query Paths

**Tailscale client (iOS, macOS, remote Linux device):**
```
Device
  → DNS query to 10.253.53.53
  → Tailscale routes to active edge (e.g. dns-ams-01)
  → dnsdist picks first healthy AGH backend
  → AGH applies blocklist, forwards to Unbound (127.0.0.1:5335)
  → Unbound performs recursive resolution
  → answer returned
```

**LAN client (non-Tailscale device — TV, printer, phone on LAN):**
```
Device
  → DHCP DNS = Pi-hole LAN IP (unchanged from current setup)
  → Pi-hole (eth0:53) applies blocklist, logs per-client metrics
  → forwards to Unbound (127.0.0.1:5335)
  → Unbound performs recursive resolution
  → answer returned
```
The LAN Pi-hole path is completely independent of the central HA DNS and requires no changes to existing Pi-hole configuration.

**Opt-in LAN device (any device that sets DNS manually to 10.253.53.53):**  
Only reachable if the device runs Tailscale (since `10.253.53.53` is a tailnet-routed address, not a LAN address). Non-Tailscale LAN devices cannot reach it and continue using Pi-hole.

---

### Metrics Expectations

The two systems have different visibility characteristics — this matters for dashboard interpretation:

| Layer | Per-client visibility | Notes |
|---|---|---|
| Pi-hole (LAN) | Excellent — per-device metrics | Clients query Pi-hole directly, so Pi-hole sees the real source IP |
| AGH (tailnet HA) | Weaker per-client visibility | AGH sees the dnsdist edge node as the querying client, not the original Tailscale device — especially when the backend is remote |
| dnsdist | Best place for tailnet source IPs | dnsdist receives the original query before forwarding; its logs are the source of truth for per-device tailnet metrics |
| dns-ha-agent | Stack-health metrics (Prometheus) | Exports probe success rates, peer reachability, route mutation events |

**Central AGH query logs are not the source of truth for per-device metrics.** Use dnsdist logs for tailnet client attribution. Use Pi-hole for per-LAN client metrics. Use AGH for service-level filtering metrics (blocked request counts, blocklist hit rates). Use `dns-ha-agent` `/metrics` for HA layer health.

---

### Phase 2 (Optional): Per-LAN Pi-hole → HA DNS Upstream

A future extension would point each LAN's Pi-hole upstream at `10.253.53.53` instead of local Unbound. This would preserve per-client metrics in Pi-hole while making the recursive resolution path centrally HA. Not recommended at initial rollout — adds coupling between the two systems and means a HA DNS outage affects LAN clients. Implement only once the HA DNS has proven stable over several weeks.

```
Phase 2 path (optional, future):
  LAN client → Pi-hole (eth0:53, per-client metrics preserved)
             → 10.253.53.53 (HA DNS)
             → dnsdist → AGH → Unbound
```

---

### Failure Matrix

| Failure | Detection | Effect |
|---|---|---|
| One regional AGH backend goes down | dnsdist health check (~1s) | Routes to next healthy backend in the same region. No client impact. |
| Backup regional AGH backend has broken filtering (silently serving real IPs for blocked names) | dns-ha-agent per-backend filter probe (`dns_ha_agent_backend_filter_ok{backend=...} == 0`) | Edge stays HEALTHY (active backend is fine); operator alerted via Prometheus. If dnsdist fails over to the broken backend, the agent's *local* probe then fails and Layer 1 self-withdraw kicks in. Goal of this metric is to catch the latent breakage before failover lands traffic on it. |
| All AGH backends in a region go down; agent can mutate Tailscale | dns-ha-agent probe failure (~4s) | Agent withdraws `10.253.53.53/32` on that node. Tailscale routes clients to next available edge (~15s), which serves from its own regional pool. Filtering remains active. |
| dnsdist crashes on an edge; agent can mutate Tailscale | dns-ha-agent probe failure (~4s) | Agent withdraws the route. Tailscale fails over to next edge (~15s). ~19s max disruption. |
| Edge DNS stack unhealthy but agent cannot withdraw (Tailscale socket gone, CLI persistently failing, etc.) | `dns_ha_agent_fail_open=1`, external per-edge DNS probe failure, Tailscale API still shows route enabled | n8n quorum-fencing workflow disables that node's `10.253.53.53/32` route approval via Tailscale API. Clients converge to another edge. This is the key fail-open remediation path. |
| Agent dead/unreachable while node still advertises route | n8n cannot scrape `/health`/`/metrics`; external per-edge DNS probe fails; Tailscale API still shows route enabled | n8n may fence the node if the failure persists and at least one other edge is healthy. This covers the case where the agent cannot self-report `fail_open`. |
| dns-ha-agent crashes (non-zero exit) | Docker `restart: unless-stopped` (immediate) | Container restarts. Brief window (~6s) before STARTING → HEALTHY re-advertises. If the underlying DNS stack is broken, startup reconciliation withdraws first; if reconciliation itself fails, n8n can remediate via fail-open fencing. |
| dns-ha-agent hangs | Internal liveness check → self-exit (~15s) | Agent's internal watchdog detects deadlock/hang and forces non-zero exit. Docker `restart: unless-stopped` then restarts the container. Note: Docker `HEALTHCHECK` alone does NOT auto-restart in Compose; the recovery mechanism is the self-exit + restart policy. |
| dns-ha-agent on a node loses peer reachability | Peer monitor (60s threshold) | Partition detection: if 2+ peers report this node unreachable, agent self-withdraws. Full isolation where the node cannot read peer reports is not treated as quorum by the agent; n8n external checks cover persistent fail-open cases. |
| n8n host / Mac Studio down | n8n workflow health check / missing scheduled executions | Normal Tailscale node-level failover and dns-ha-agent self-withdraw still work. External fail-open route fencing is unavailable until n8n returns. DNS continues unless an edge enters fail-open during this window. |
| n8n Tailscale API token revoked/expired | n8n API call failure | n8n can still detect and alert on fail-open states, but cannot fence. Operator must remediate manually. |
| n8n false-positive fencing | n8n audit log + route disabled unexpectedly | A healthy edge may be disabled. Guardrails: repeated checks, quorum corroboration, never fence the last healthy edge, disable only one edge per run unless explicitly allowed, and manual re-enable. |
| Tailscale autoApprover re-enables a fenced route | n8n sees route enabled again after fencing | Treat as failed fencing. Either keep fenced state active until manual clearance or move to a two-tag model where only `tag:dns-edge-active` auto-approves the VIP route. |
| dns-ams-01 goes down (AMS tinkering, power, hardware) | Tailscale node disconnects (~15s) | Tailscale fails over to dns-iom-01. EU DNS continues from IOM's pool. AMS LAN Pi-hole also down — AMS LAN clients lose DNS until node recovers. |
| dns-iom-01 goes down | Tailscale node disconnects (~15s) | EU DNS continues on dns-ams-01. No tailnet impact. |
| AMS total power outage (dns-ams-01 + agh-ams-01 + Mac Studio down) | Tailscale node disconnects; n8n unavailable | dns-iom-01 takes over EU. Its pool falls back to agh-iom-01 only. EU DNS continues, but external route fencing is unavailable until Mac Studio power returns. |
| dns-tor-01 goes down (sole NA edge) | Tailscale node disconnects (~15s) | Tailscale routes NA clients to an available remaining edge. NA LAN Pi-hole down until node recovers. |
| dns-shz-01 goes down (sole APAC edge) | Tailscale node disconnects (~15s) | Tailscale routes APAC clients to an available remaining edge. APAC LAN Pi-hole down until node recovers. |
| All EU edges down simultaneously | Tailscale node disconnects | Tailscale clients route to NA or APAC. HA DNS continues. EU LAN Pi-holes down until power/nodes recover. |
| All edge nodes down simultaneously | None | HA DNS broken for all tailnet clients. Each LAN's Pi-hole continues working independently. |
| AGH config drifts between regions | adguardhome-sync corrects within 5 minutes | No functional impact within the sync window. |
| Mac Studio goes down | n8n/adguardhome-sync unavailable | adguardhome-sync pauses and n8n route fencing is unavailable. DNS continues via existing edge nodes and self-withdraw logic. Config changes must be made manually on each AGH instance until Mac Studio recovers. |
| k3s cluster goes down | k3s events | No DNS impact. DNS does not depend on k3s. |
| Pi-hole goes down on any node | None (independent system) | That LAN's local DNS broken until Pi-hole recovers. HA tailnet DNS unaffected. |

---

### Adding Future AZ Nodes

**Adding an AGH backend to an existing region (e.g. HOU-01 to NA):**

1. Install Pi-hole in BIND mode (LAN DNS, independent).
2. Install Unbound on `127.0.0.1:5335`; set as upstream for both Pi-hole and AGH.
3. Install AdGuard Home on restricted `:5353`. Add it to adguardhome-sync on the Mac Studio — it will receive the full config automatically.
4. Add the node's Tailscale IP as a new backend in the **regional edge's** dnsdist.conf only (e.g. add HOU-01 to dns-tor-01's pool). Do not add it to other regions' pools.
5. The node does **not** advertise `10.253.53.53/32`, does not run dnsdist, and does not run `dns-ha-agent`.

**Promoting a node to a DNS edge (e.g. adding a second NA edge):**

Follow the same steps above, then additionally:
1. Assign `tag:dns-edge` in Tailscale admin.
2. Install dnsdist with the regional pool config for that region.
3. Bind VIP to loopback (via systemd unit in Appendix B).
4. Deploy `dns-ha-agent` via the ansible role. Update peer lists on the other edges in this region.
5. The agent advertises `10.253.53.53/32` automatically once it transitions to HEALTHY — route auto-approves via tag.
6. Add this edge's local AGH as a backend to the other edge in the same region (and vice versa), so both edges share the full regional pool.

---

### Naming Convention

Set hostnames before Tailscale enrolment so MagicDNS names are clean:

| Hostname | Machine | Role |
|---|---|---|
| `dns-ams-01` | Pi 5 Rack D (AMS-01) | EU primary edge |
| `dns-iom-01` | IOM-01 Pi 5 | EU secondary edge |
| `dns-tor-01` | TOR-01 Pi 5 | NA edge |
| `dns-shz-01` | SHZ-01 Pi 5 | APAC edge |
| `dns-tor-02` | TOR-02 Pi 5 | NA AGH backend only (no VIP, no dnsdist, no agent) |
| `dns-hou-01` | HOU-01 Pi 5 (future) | NA AGH backend only |

Nodes that are AGH backends only (`dns-tor-02`, `dns-hou-01`) run Pi-hole, Unbound, and AGH on `:5353` — but not dnsdist, the dns-ha-agent, or the loopback VIP alias.

---

## Alternative Approaches Considered

**Technitium DNS Server** — a single process replacing the dnsdist + AGH + Unbound stack entirely, with built-in forwarder failover and DNSSEC; simpler stack but less compositional and less battle-tested. Now planned for parallel evaluation on a single node — see Appendix G.

**Unbound with RPZ** — drops the filtering application and uses Unbound's native Response Policy Zone support to apply blocklists inside the recursive resolver; loses the web UI but reduces the process count to one.

**CoreDNS as proxy layer** — replaces dnsdist with a simpler Corefile-configured forwarder that integrates naturally with k3s; less DNS-specific than dnsdist but easier to configure.

**NextDNS as filtering-aware fallback** — a managed cloud resolver with matching blocklists as a last-resort backend so filtering still applies if all local nodes are down; introduces a privacy trade-off.

**Tailscale API nameserver controller** — a daemon that dynamically rewrites the Tailscale nameserver list based on health checks. Rejected as the primary failover mechanism because it is broader, slower, and more fragile than subnet-router failover. This is distinct from **Tailscale API route fencing**, which is adopted as a targeted secondary remediation layer via n8n: it disables one bad node's approved `10.253.53.53/32` route without changing the global nameserver setting. See [Appendix E](#appendix-e-external-route-remediation-via-n8n).

**Monit + shell action script** — host-level monitor running probes and triggering shell-script route mutation. Considered and rejected in favour of `dns-ha-agent` because Monit doesn't fit the homelab's container-and-ansible deployment pattern, and an external monitoring tool would couple the HA layer to other services.

**Pacemaker / Corosync** — the canonical OSS HA cluster manager. Rejected here because Corosync assumes LAN-local clusters with low-latency, reliable peer communication; cross-continent Corosync over Tailscale would oscillate during WAN blips. Reasonable choice for single-datacenter HA, wrong fit for this topology.

---

## Appendix B: Config Files

### Unbound — `/etc/unbound/unbound.conf.d/homelab.conf`

```conf
server:
    interface: 127.0.0.1
    port: 5335
    do-ip4: yes
    do-ip6: no
    do-udp: yes
    do-tcp: yes
    access-control: 127.0.0.0/8 allow
    auto-trust-anchor-file: "/var/lib/unbound/root.key"
    root-hints: "/var/lib/unbound/root.hints"
    cache-min-ttl: 300
    cache-max-ttl: 86400
    prefetch: yes
    num-threads: 2
    verbosity: 0
    harden-glue: yes
    harden-dnssec-stripped: yes
    hide-identity: yes
    hide-version: yes
```

---

### dnsdist — `/etc/dnsdist/dnsdist.conf`

Each edge node gets its own config containing only its regional AGH pool. Four variants are shown below. Replace placeholder IPs with real values from `tailscale status`.

Notes:
- `order=` is explicit on every backend so intent is unambiguous regardless of declaration order.
- No Unbound fallback — design fails closed if all regional AGH backends are down.
- Packet cache is disabled initially; AGH and Unbound already cache. Enable only if latency or load later justifies it (note: enabling cache makes AGH query logs incomplete for tailnet clients).
- **Webserver removed.** Earlier drafts of this document enabled the dnsdist webserver with placeholder credentials, which is a credential-leak risk. If you need the webserver for ad-hoc inspection, enable it with strong credentials from a secrets file and bind it to 127.0.0.1 only.

The preamble (bind, ACL, policy) is identical on all edges — only the `newServer` pool entries differ.

```lua
-- ============================================================
-- PREAMBLE — identical on all edge nodes
-- The EDGE_TS_IP value is templated per-node by Ansible.
-- ============================================================

-- Primary listener: the shared VIP on loopback. This is the path
-- normal tailnet clients hit via Tailscale DNS.
setLocal("10.253.53.53:53")

-- Diagnostic listener: full dnsdist → AGH → Unbound path, but
-- reachable directly via this node's Tailscale IP on port 1053.
-- Used by the n8n external fencer (see Appendix E) to probe each
-- edge's *real* serving path — not just AGH on :5353. A failure on
-- :1053 means this edge cannot serve queries even though AGH may
-- be locally healthy. That is exactly the signal n8n needs.
-- Restricted by Tailscale ACL to tag:dns-fencer + tag:dns-edge,
-- and by host UFW (PHASES.md Phase 1 step 4d).
addLocal("<EDGE_TS_IP>:1053")

-- Allow Tailscale CGNAT and all RFC1918 ranges
setACL({
  "100.64.0.0/10",
  "192.168.0.0/16",
  "10.0.0.0/8",
  "172.16.0.0/12",
})

setServerPolicy(firstAvailable)

-- Packet cache disabled until design is stable.
-- AGH and Unbound already cache; enabling here makes AGH logs incomplete.
-- pc = newPacketCache(10000, { maxTTL = 300, minTTL = 30 })
-- getPool(""):setCache(pc)

-- Webserver intentionally NOT enabled. If you want it for debugging:
--   1. Generate strong credentials and store them in /etc/dnsdist/secrets.lua
--      (mode 0600, root:root).
--   2. Add: dofile("/etc/dnsdist/secrets.lua")  -- defines local apiKey, pwd
--   3. Add: webserver("127.0.0.1:8083")
--           setWebserverConfig({ password = pwd, apiKey = apiKey })
--   4. Never bind the webserver to a public or Tailscale-routable address.

-- ============================================================
-- POOL — replace section below per edge node
-- ============================================================
```

**dns-ams-01 (EU primary):**
```lua
newServer({ address="127.0.0.1:5353",    name="agh-ams-01", order=10, checkName="cloudflare.com.", checkType="A", mustResolve=true })
newServer({ address="IOM01_TS_IP:5353",  name="agh-iom-01", order=20, checkName="cloudflare.com.", checkType="A", mustResolve=true })
-- No cross-region backends. Fails closed if both EU AGH instances are down.
```

**dns-iom-01 (EU secondary):**
```lua
newServer({ address="127.0.0.1:5353",    name="agh-iom-01", order=10, checkName="cloudflare.com.", checkType="A", mustResolve=true })
newServer({ address="AMS01_TS_IP:5353",  name="agh-ams-01", order=20, checkName="cloudflare.com.", checkType="A", mustResolve=true })
-- No cross-region backends.
```

**dns-tor-01 (NA):**
```lua
newServer({ address="127.0.0.1:5353",    name="agh-tor-01", order=10, checkName="cloudflare.com.", checkType="A", mustResolve=true })
newServer({ address="TOR02_TS_IP:5353",  name="agh-tor-02", order=20, checkName="cloudflare.com.", checkType="A", mustResolve=true })
-- Add HOU when provisioned:
-- newServer({ address="HOU01_TS_IP:5353", name="agh-hou-01", order=30, checkName="cloudflare.com.", checkType="A", mustResolve=true })
-- No cross-region backends.
```

**dns-shz-01 (APAC):**
```lua
newServer({ address="127.0.0.1:5353",    name="agh-shz-01", order=10, checkName="cloudflare.com.", checkType="A", mustResolve=true })
-- Single-node APAC region. If AGH fails here, Tailscale routes APAC clients to EU.
```

---

### VIP loopback bind — `/etc/systemd/system/dns-vip-bind.service`

```ini
[Unit]
Description=Bind HA DNS VIP to loopback
After=network.target
Before=dnsdist.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStartPre=-/sbin/ip addr del 10.253.53.53/32 dev lo
ExecStart=/sbin/ip addr add 10.253.53.53/32 dev lo
ExecStop=/sbin/ip addr del 10.253.53.53/32 dev lo

[Install]
WantedBy=multi-user.target
```

---

### adguardhome-sync — Mac Studio (OrbStack Docker)

All config changes are made on dns-ams-01 AGH; sync pushes to all replicas.

```yaml
# adguardhome-sync.yml
origin:
  url: http://AGH_AMS_TS_IP:3000
  username: admin
  password: CHANGE_ME

replicas:
  - url: http://IOM01_TS_IP:3000   # EU secondary
    username: admin
    password: CHANGE_ME
  - url: http://TOR01_TS_IP:3000   # NA edge
    username: admin
    password: CHANGE_ME
  - url: http://TOR02_TS_IP:3000   # NA AGH backend
    username: admin
    password: CHANGE_ME
  - url: http://SHZ01_TS_IP:3000   # APAC edge
    username: admin
    password: CHANGE_ME
  # Add future nodes here (HOU-01, etc.) as they come online

cron: "*/5 * * * *"
runOnStart: true

sync_config:
  general_settings: true
  filter_settings: true
  filters: true
  clients: true
  dns_rewrites: true
```

```bash
docker run -d \
  --name adguardhome-sync \
  --restart unless-stopped \
  -v /path/to/adguardhome-sync.yml:/opt/adguardhome-sync/config.yml \
  ghcr.io/bakito/adguardhome-sync
```

---

## Appendix C: dns-ha-agent

Health-driven route mutation is handled by `dns-ha-agent`, a containerised Java service deployed to every edge node. Full specification, including state machine, config schema, API surface, implementation guidance, testing strategy, and CI/CD pipeline, is documented in [`dns-ha-agent/BLUEPRINT.md`](dns-ha-agent/BLUEPRINT.md).

**Deployment summary** (the operational hook for this document):

1. The agent is built by Forgejo Actions and published to the Forgejo container registry at `forgejo.homelab.internal/homelab/dns-ha-agent`. CI publishes `:latest` as a browse-only pointer; **deployments pin by image digest** (`@sha256:...`).
2. An ansible role (`ansible/roles/dns-ha-agent`) renders a per-node config from `host_vars` and starts the container via docker-compose with `--network host`. The role asserts a digest is set; deploys without one are a hard error.
3. Per-node config sets the node's name, region, VIP, peer list (other edges' Tailscale IPs), and probe parameters. Single-node regions (currently SHZ-01) deploy with an empty peer list, which automatically disables partition detection.
4. The agent exposes `/health` (JSON, peer-facing) and `/metrics` (Prometheus) on TCP 8091. The Tailscale ACL restricts inbound to `tag:dns-edge`.
5. On startup, the agent reconciles actual Tailscale route state — explicitly withdrawing the VIP if a previous instance crashed without graceful shutdown — then waits for 3 consecutive successful local probes before re-advertising. On SIGTERM, it withdraws the route gracefully before exit.
6. The agent has an internal `WatchdogThread` that forces `System.exit(70)` on probe-loop deadlock. Docker `restart: unless-stopped` then restarts the container. This is the recovery path — `HEALTHCHECK` alone does NOT auto-restart in Compose.
7. Rollback is byte-identical: change `dns_ha_agent_image_digest` in ansible back to a previous known-good digest, re-run the playbook. The previous artifact remains in the registry (retention: last 20 digests).
8. The agent exports `dns_ha_agent_fail_open` and `dns_ha_agent_fail_open_duration_seconds`. n8n consumes these metrics as one input to external route fencing, but it also performs external DNS checks so a dead agent cannot hide a stale route. See [Appendix E](#appendix-e-external-route-remediation-via-n8n).

---

## Appendix D: Tailscale DNS Configuration

Once at least two edge nodes are advertising `10.253.53.53/32` and routes are approved in the admin console:

```
Tailscale Admin Console → Settings → DNS
→ Remove all existing Pi-hole nameserver entries
→ Add nameserver → Custom → 10.253.53.53
→ Enable "Override local DNS"
→ Save
```

Verify from macOS:
```bash
tailscale dns status                            # confirm 10.253.53.53 is the resolver
dig @10.253.53.53 cloudflare.com               # should resolve
dig @10.253.53.53 dns-filter-sentinel.<your-public-domain>        # should return NXDOMAIN (custom AGH block entry)
```

Verify each edge node directly:
```bash
dig @DNS_AMS_TS_IP  -p 5353 cloudflare.com   # AGH on dns-ams-01
dig @DNS_TOR01_TS_IP -p 5353 cloudflare.com  # AGH on dns-tor-01
dig @DNS_TOR02_TS_IP -p 5353 cloudflare.com  # AGH on dns-tor-02
```

Verify the agent itself:
```bash
curl http://DNS_AMS_TS_IP:8091/health | jq    # agent's self-view + peer view
curl http://DNS_AMS_TS_IP:8091/metrics        # Prometheus metrics
```

---

## Appendix E: External Route Remediation via n8n

The n8n workflow is the external safety layer for zombie DNS edges: nodes that are unhealthy but still have the VIP route approved/enabled. It is intentionally slower and more conservative than `dns-ha-agent` self-withdraw.

### Runtime location

- **Host**: Mac Studio at AMS-01.
- **Role**: control-plane helper only; not a DNS edge and does not advertise `10.253.53.53/32`.
- **Inputs**: edge `/health`, edge `/metrics`, direct per-edge DNS probes, and Tailscale route state from the API.
- **Output**: disable the bad edge's approved `10.253.53.53/32` route via the Tailscale API, then alert.

### Required n8n secrets

Store these as n8n credentials/environment variables, not in workflow JSON:

```text
TAILSCALE_TAILNET=<tailnet name or ID>
TAILSCALE_API_TOKEN=<restricted token capable of reading devices/routes and disabling route approval>
DNS_EDGE_INVENTORY=<static JSON or file-backed list of edge names, TS IPs, route IDs>
```

Prefer a token with the narrowest available scope. Rotate it deliberately, and alert on API authentication failures.

### Per-edge evidence collection

For each edge, n8n collects:

```bash
# Agent self-report (may fail if agent is the broken component)
curl -fsS http://<EDGE_TS_IP>:8091/health
curl -fsS http://<EDGE_TS_IP>:8091/metrics

# PRIMARY external DNS signal — full dnsdist → AGH → Unbound path
# via this edge's dnsdist diagnostic listener (see Appendix B).
# Failure here means the edge cannot actually serve queries, which
# is exactly the signal we need to fence on.
dig @<EDGE_TS_IP> -p 1053 cloudflare.com +short
dig @<EDGE_TS_IP> -p 1053 dns-filter-sentinel.<your-public-domain> +short

# SECONDARY diagnostic — AGH-only path on :5353.
# Useful for narrowing down whether a failure is dnsdist or AGH,
# but NOT the fencing signal: dnsdist could be down while AGH is
# still healthy, and that case still warrants fencing.
dig @<EDGE_TS_IP> -p 5353 cloudflare.com +short
dig @<EDGE_TS_IP> -p 5353 dns-filter-sentinel.<your-public-domain> +short
```

The `:1053` listener tests the same path real tailnet clients take when they query `10.253.53.53` — just via the edge's direct Tailscale IP instead of the shared VIP, so n8n can target one specific edge at a time. The `:5353` probe is kept for diagnostic visibility (it tells the operator whether AGH alone is still healthy when dnsdist has failed), but the fencing decision must be driven by `:1053`.

### Fencing decision

Fence only when the evidence points to a real fail-open condition:

```text
route_enabled_for_edge == true
AND edge_externally_unhealthy == true
AND (
  dns_ha_agent_fail_open == 1
  OR agent_unreachable_for > 60s
  OR quorum_peers_report_unhealthy
)
AND at_least_one_other_edge_healthy == true
AND failure_persists_for >= 60s
```

Guardrails:

- Never fence the last healthy edge.
- Do not fence more than one edge per workflow run unless explicitly overridden.
- Require repeated failures, not one missed probe.
- Alert before and after the fencing API call.
- Record an audit log containing node, checks, decision, API response, timestamp, and workflow run ID.

### Remediation action

The workflow disables that node's approved route for `10.253.53.53/32` through the Tailscale API. The node may still be locally advertising the route, but clients should stop selecting it once the route is disabled/unapproved centrally.

Re-enable is manual by default:

1. Repair the edge.
2. Confirm `/health` is `HEALTHY`.
3. Confirm direct per-edge DNS probes pass.
4. Confirm no `dns_ha_agent_fail_open` is present.
5. Re-enable the route approval in Tailscale.
6. Clear the n8n fenced-state record.

### Auto-approval interaction test

Because `tag:dns-edge` auto-approves `10.253.53.53/32`, explicitly test whether disabling a route via API remains disabled while the node continues advertising. If auto-approval immediately re-enables it, move to a two-tag model:

```text
tag:dns-edge          — can run edge services and be polled
tag:dns-edge-active   — auto-approves 10.253.53.53/32
```

n8n fencing would remove or disable the active approval state; manual repair would restore it.

---

## Appendix F: Break-Glass and Monitoring

### Break-Glass Procedures

If the HA DNS breaks and clients lose DNS:

```bash
# Option 1: Disable Tailscale DNS on the affected client temporarily
# macOS: System Settings → Network → DNS → remove 10.253.53.53 override
# Or: tailscale set --accept-dns=false  (on the affected machine)

# Option 2: Restore old Pi-hole nameservers in Tailscale Admin Console
# Settings → DNS → replace 10.253.53.53 with the direct Pi-hole Tailscale IPs

# Option 3: Stop the agent and force route state manually
ssh dns-ams-01
docker stop dns-ha-agent                          # agent withdraws on shutdown
sudo tailscale set --advertise-routes=10.253.53.53/32   # force-advertise
# Investigate, fix, then:
docker start dns-ha-agent                         # agent reconciles to its computed state

# Option 4: Re-enable a node fenced by n8n
# 1. Repair the edge and confirm /health is HEALTHY.
# 2. Confirm direct DNS probes pass.
# 3. Re-enable the node's 10.253.53.53/32 route approval in Tailscale.
# 4. Clear the n8n fenced-state/audit flag so it does not disable the route again.
```

### Monitoring Checklist

Run these periodically or wire into a monitoring tool (e.g. Uptime Kuma, Prometheus blackbox exporter):

```bash
# Resolution health (should return an IP)
dig @10.253.53.53 cloudflare.com +short

# Filtering health (should return NXDOMAIN or empty)
dig @10.253.53.53 dns-filter-sentinel.<your-public-domain> +short

# dns-ha-agent self-view + peer-view
curl -s http://DNS_AMS_TS_IP:8091/health | jq

# dns-ha-agent Prometheus metrics
curl -s http://DNS_AMS_TS_IP:8091/metrics

# AGH sync status (from Mac Studio — check last sync time)
docker logs adguardhome-sync --tail 20

# Unbound recursion
dig @127.0.0.1 -p 5335 cloudflare.com +short

# Currently advertised routes per node
tailscale status --json | python3 -c \
  "import sys,json; d=json.load(sys.stdin); \
   print([r for r in d.get('Self',{}).get('AllowedIPs',[]) if '10.253.53' in r])"

# Tailscale node online state (run from another tailnet node)
tailscale ping dns-ams-01
tailscale ping dns-iom-01
tailscale ping dns-tor-01
tailscale ping dns-shz-01

# n8n route-fencing workflow health
# Check last successful scheduled run, last Tailscale API call status,
# and any fenced-state records awaiting manual review.
```

Prometheus scrape config snippet (for whatever Prometheus you run):

```yaml
scrape_configs:
  - job_name: dns-ha-agent
    scrape_interval: 30s
    static_configs:
      - targets:
          - 100.64.x.y:8091   # dns-ams-01
          - 100.64.a.b:8091   # dns-iom-01
          - 100.64.c.d:8091   # dns-tor-01
          - 100.64.e.f:8091   # dns-shz-01
```

Alerting rules worth setting up (Alertmanager):

- `dns_ha_agent_state{state="HEALTHY"} == 0` for >5m on any edge → page
- `dns_ha_agent_vip_advertised == 0` on >1 edge simultaneously → page (degraded HA)
- `rate(dns_ha_agent_route_mutations_total[5m]) > 0.1` → warn (flapping)
- `dns_ha_agent_backend_filter_ok == 0` for >2m → page (silent filter breakage on a backup backend, will only hurt after failover)
- `dns_ha_agent_backend_resolve_ok == 0` for >2m → warn (backup backend resolution down; dnsdist will also flag it)
- `dns_ha_agent_fail_open == 1` → immediate critical alert and n8n fencing candidate
- `dns_ha_agent_fail_open_duration_seconds > 60` → page; fail-open has persisted beyond one remediation window
- `up{job="dns-ha-agent"} == 0` → warn (agent scrape failing); n8n should combine this with external DNS and Tailscale route-state checks
- n8n scheduled workflow missed/failed for >2 intervals → warn; external fencing unavailable
- n8n Tailscale API authentication failure → page; fencing can detect but not remediate

---

## Appendix G: Technitium Evaluation (Planned)

Technitium DNS Server is a single-process alternative that could replace the dnsdist + AGH + Unbound stack on edge nodes. It's GPL-3.0, has built-in DNSSEC + filtering + forwarder-failover, and exposes a clean HTTP API for config sync.

The plan is to evaluate it in production conditions without committing to migration, by deploying it to a single node and running it as a backend for the existing dnsdist pool.

### Eval node

**HOU-01** (when provisioned) is the preferred eval node:
- Clean slate — no AGH or Unbound history to migrate from.
- Sits in the NA region as an AGH-backend-only role, so failure has limited blast radius (dnsdist on dns-tor-01 will simply route to agh-tor-01 or agh-tor-02 if Technitium misbehaves).
- Exercises real production conditions: serving real queries from dnsdist under real load.

Alternative: a spare Pi outside the production pool, used as a personal DNS for one device. Faster to set up but doesn't test integration with the dnsdist backend pool or the cross-region sync story.

### Setup outline

1. Install Technitium via the upstream install script (or Docker image).
2. Configure its built-in recursive resolver with DNSSEC (replaces Unbound).
3. Import the same blocklists used by AGH.
4. Configure its DNS listener on `127.0.0.1:5353` and `<TS_IP>:5353` — same port layout AGH would use, so dnsdist on dns-tor-01 can list it as a backend interchangeably.
5. Add `agh-hou-01` (actually Technitium, named for consistency) to dns-tor-01's dnsdist pool as `order=30`.
6. No `dns-ha-agent`, no dnsdist, no VIP advertisement on HOU-01 (AGH-backend-only role).

### What to measure (4-week eval period)

- **Blocklist parity**: pick 20 known-blocked domains and 20 known-allowed; confirm AGH and Technitium agree on every one. Differences = ecosystem trust gap.
- **DNSSEC validation**: `dig +dnssec +trace` against Technitium for a signed domain; confirm clean validation.
- **API stability for config sync**: write a small Python script that uses Technitium's API to dump and restore config across instances. If the API is painful, the migration cost balloons.
- **Memory/CPU on Pi 5**: baseline footprint, peak under load. .NET runtime has a larger baseline than AGH+Unbound but should still fit comfortably in 1GB.
- **Blocklist update behaviour on failure**: simulate blocklist URL fetch failure; verify Technitium serves stale cached blocklists rather than failing open.
- **Operator experience**: how natural is the web UI? Are common changes (add rewrite, allowlist a domain) easy?

### Decision framework

After 4 weeks, evaluate:

| Outcome | Action |
|---|---|
| Technitium passes all parity tests, sync script is clean, operator experience is good | Plan migration: one edge at a time, starting with dns-iom-01 (secondary EU). |
| Passes technically but operator UX is worse than AGH | Keep AGH. Document why Technitium was considered and rejected. |
| Fails parity or has DNSSEC issues | Keep AGH. File issues upstream if appropriate. |

Migration plan (if pursued): replace `agh-* + unbound` with Technitium on one edge at a time. dnsdist pool composition doesn't change (Technitium listens on the same `:5353` and looks identical to AGH from dnsdist's perspective). Roll back any individual edge by reinstalling AGH + Unbound from ansible.
