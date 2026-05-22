# HA DNS for Homelab Tailnet

## Context & Goals

The current setup lists 6 Pi-hole instances directly as Tailscale global nameservers. This is not HA — Tailscale does not guarantee resolver order, and iOS in particular loses DNS entirely when the first-listed resolver goes offline rather than falling through to the others. The Mac working while iOS fails is not evidence of resilience; macOS handles resolver failures more gracefully and may be serving cached results.

**Goals:** DNS survives any individual node failure and any single-region power outage. AdGuard Home filtering remains active on all HA failover paths — the design fails closed: if all AGH backends are unavailable, DNS breaks rather than silently serving unfiltered responses. Per-LAN Pi-hole filtering and metrics are preserved independently and are not affected by the HA DNS design. The design must be manageable at homelab scale.

**Public cloud DNS edges deliberately excluded.** Running a DNS edge on a cloud VPS (DigitalOcean, Vultr, etc.) would mean DNS queries are processed on third-party infrastructure. All edge nodes are homelab-owned machines.

**Hard constraints from the Tailscale model:**
- Tailscale IPs are node-bound — they cannot float between machines the way a VRRP VIP can on a LAN. The VIP approach described below works around this.
- Tailscale subnet-route failover is active/passive by default (~15 second switchover). The oldest-enrolled edge node becomes primary; there is no manual preference setting.
- Regional routing (clients prefer nearest edge) is Premium/Enterprise only.
- Tailscale detects node-level failure (machine offline), not service-level failure (dnsdist crashed). A watchdog that withdraws the route advertisement on service failure is mandatory.
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

Pick a private IP that overlaps with none of your LANs, bind it on the loopback of every DNS edge node, and have each node advertise it as a Tailscale subnet route. Tailscale DNS points at this single IP. Tailscale handles node-level failover transparently; dnsdist handles backend health-checking; AdGuard Home instances (kept identical via adguardhome-sync) provide filtering and are fully fungible.

```
Reserved:    10.253.53.0/24  (tailnet service VIPs — never assign to LANs, DHCP, Docker, or k3s)
HA DNS VIP:  10.253.53.53/32

Tailscale DNS → 10.253.53.53 (single entry, Override DNS enabled)
```

This address range avoids conflicts with common home ranges (`192.168.x.x`), common lab/cloud ranges (`10.0–10.10.x.x`), and Tailscale's own CGNAT block (`100.64.0.0/10`). The whole `/24` is reserved so future tailnet service VIPs can draw from it without collision risk.

**Tailscale tag and route auto-approval** — each DNS edge node is tagged `tag:dns-edge`. The ACL policy grants this tag permission to advertise `10.253.53.53/32` and marks it for automatic approval. This is required because the watchdog will withdraw and re-advertise the route on service failures — manual approval in the admin console on every cycle would make the watchdog useless in practice.

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

Apply the tag to each edge node before advertising the route:
```bash
# In Tailscale admin console → Machines → <node> → Edit tags → add tag:dns-edge
# OR via tailscale up on the node (if using auth keys with pre-assigned tags)
sudo tailscale set --advertise-routes=10.253.53.53/32
# Route will be auto-approved because the node carries tag:dns-edge
```

---

### Inventory Role Mapping

| Role | Machine | Site | Region | Notes |
|---|---|---|---|---|
| **DNS edge node** | Pi 5 Rack D | AMS-01 | EU | Always-on, wired, Linux. Primary EU edge. Also runs Pi-hole for AMS LAN. High tinkering risk — IOM edge provides EU continuity when AMS is broken. |
| **DNS edge node** | IOM-01 Pi 5 | IOM-01 | EU | EU secondary edge. Slower WAN but reliable. Advertises VIP; runs dnsdist + watchdog + AGH. Activates as EU primary when AMS is down. |
| **DNS edge node** | TOR-01 Pi 5 | TOR-01 | NA | Always-on, 1 Gbps WAN. Sole NA edge. Also runs Pi-hole for TOR-01 LAN. |
| **DNS edge node** | SHZ-01 Pi 5 | SHZ-01 | APAC | Sole APAC edge. Also runs the only APAC AGH instance. No intra-region failover. |
| **AGH backend only** | TOR-02 Pi 5 | TOR-02 | NA | NA AGH backend. Does not advertise VIP or run dnsdist. Also runs Pi-hole for TOR-02 LAN. |
| **AGH backend only (future)** | HOU-01 Pi 5 | HOU-01 | NA | NA AGH backend once provisioned. Does not advertise VIP. |
| **AGH config sync daemon** | Mac Studio | AMS-01 | — | Runs adguardhome-sync in Docker (OrbStack). Pulls from dns-ams-01 AGH, pushes to all replicas globally. |
| **Not involved** | k3s Rack A/B/C | AMS-01 | — | DNS must not depend on k3s availability. |
| **Not involved** | All wake-on-demand machines | AMS-01 | — | Never advertise the VIP from machines that sleep or reboot casually. |
| **Not a DNS edge** | Mac Studio | AMS-01 | — | Sync daemon role only. Too many critical services to also be a DNS edge; macOS loopback VIP binding is more complex. |

---

### Per-Node Service Stack

Each DNS edge node runs the following in parallel with its existing Pi-hole:

```
Pi-hole          — binds on eth0:53 only (BIND mode, not wildcard); per-LAN filtering + metrics
AdGuard Home     — binds on 127.0.0.1:5353 + Tailscale IP:5353; dnsdist backend; not exposed to LAN
Unbound          — binds on 127.0.0.1:5335; recursive resolver + DNSSEC; used by both Pi-hole and AGH
dnsdist          — binds on 10.253.53.53:53 (loopback alias); routes to AGH pool
Watchdog         — withdraws 10.253.53.53/32 if dnsdist or AGH filtering becomes unhealthy
Tailscale        — --accept-dns=false; tagged tag:dns-edge for auto-approved route advertisement
```

**Port layout — no conflicts:**

| Process | Interface | Port | Purpose |
|---|---|---|---|
| Pi-hole | eth0 only (BIND mode — not wildcard) | 53 | LAN DNS, per-LAN metrics |
| AdGuard Home | 127.0.0.1 + Tailscale IP | 5353 | dnsdist backend (not exposed to LAN) |
| Unbound | 127.0.0.1 | 5335 | Recursive resolver (shared by Pi-hole and AGH) |
| dnsdist | 10.253.53.53 (loopback alias) | 53 | HA DNS entry point |

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

A note on Tailscale routing: on the free tier, there is no geographic client-to-edge affinity guarantee. The oldest-enrolled edge node is Tailscale's primary for all clients. In practice, when a primary edge is unreachable, Tailscale converges to the nearest available edge, so regional routing is approximate rather than strict. Regional routing preference is an Enterprise feature.

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

**Central AGH query logs are not the source of truth for per-device metrics.** Use dnsdist logs for tailnet client attribution. Use Pi-hole for per-LAN client metrics. Use AGH for service-level filtering metrics (blocked request counts, blocklist hit rates).

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
| All AGH backends in a region go down | dnsdist health check (~1s) | DNS fails for clients on that region's edge. Tailscale routes them to the next available edge (~15s), which serves from its own regional pool. Filtering remains active. |
| dnsdist crashes on an edge node | Watchdog: 3 failed probes (~15s) | Watchdog withdraws 10.253.53.53/32 on that node. Tailscale fails over to next edge (~15s). ~15–30s max disruption. |
| dns-ams-01 goes down (AMS tinkering, power, hardware) | Tailscale node disconnects (~15s) | Tailscale fails over to dns-iom-01. EU DNS continues uninterrupted from IOM's pool. AMS LAN Pi-hole also down — AMS LAN clients lose DNS until node recovers. |
| dns-iom-01 goes down | Tailscale node disconnects (~15s) | EU DNS continues on dns-ams-01. No tailnet impact. |
| AMS total power outage (dns-ams-01 + agh-ams-01 down) | Tailscale node disconnects | dns-iom-01 takes over EU. Its pool falls back to agh-iom-01 only (agh-ams-01 is also down). EU DNS continues, single AGH backend. |
| dns-tor-01 goes down (sole NA edge) | Tailscale node disconnects (~15s) | Tailscale routes NA clients to nearest remaining edge (most likely EU). NA LAN Pi-hole down until node recovers. |
| dns-shz-01 goes down (sole APAC edge) | Tailscale node disconnects (~15s) | Tailscale routes APAC clients to EU. APAC LAN Pi-hole down until node recovers. |
| All EU edges down simultaneously | Tailscale node disconnects | Tailscale clients route to NA or APAC. HA DNS continues. EU LAN Pi-holes down until power/nodes recover. |
| All edge nodes down simultaneously | None | HA DNS broken for all tailnet clients. Each LAN's Pi-hole continues working independently. |
| AGH config drifts between regions | adguardhome-sync corrects within 5 minutes | No functional impact within the sync window. |
| Mac Studio goes down | None | adguardhome-sync pauses globally. DNS continues normally. Config changes must be made manually on each AGH instance until Mac Studio recovers. |
| k3s cluster goes down | k3s events | No DNS impact. DNS does not depend on k3s. |
| Pi-hole goes down on any node | None (independent system) | That LAN's local DNS broken until Pi-hole recovers. HA tailnet DNS unaffected. |

---

### Adding Future AZ Nodes

**Adding an AGH backend to an existing region (e.g. HOU-01 to NA):**

1. Install Pi-hole in BIND mode (LAN DNS, independent).
2. Install Unbound on `127.0.0.1:5335`; set as upstream for both Pi-hole and AGH.
3. Install AdGuard Home on restricted `:5353`. Add it to adguardhome-sync on the Mac Studio — it will receive the full config automatically.
4. Add the node's Tailscale IP as a new backend in the **regional edge's** dnsdist.conf only (e.g. add HOU-01 to dns-tor-01's pool). Do not add it to other regions' pools.
5. The node does **not** advertise `10.253.53.53/32` and does not run dnsdist or the watchdog.

**Promoting a node to a DNS edge (e.g. adding a second NA edge):**

Follow the same steps above, then additionally:
1. Assign `tag:dns-edge` in Tailscale admin.
2. Install dnsdist with the regional pool config for that region.
3. Bind VIP to loopback and install the watchdog.
4. Advertise `10.253.53.53/32` — route auto-approves via tag.
5. Add this edge's local AGH as a backend to the other edge in the same region (and vice versa), so both edges share the full regional pool.

---

### Naming Convention

Set hostnames before Tailscale enrolment so MagicDNS names are clean:

| Hostname | Machine | Role |
|---|---|---|
| `dns-ams-01` | Pi 5 Rack D (AMS-01) | EU primary edge |
| `dns-iom-01` | IOM-01 Pi 5 | EU secondary edge |
| `dns-tor-01` | TOR-01 Pi 5 | NA edge |
| `dns-shz-01` | SHZ-01 Pi 5 | APAC edge |
| `dns-tor-02` | TOR-02 Pi 5 | NA AGH backend only (no VIP, no dnsdist) |
| `dns-hou-01` | HOU-01 Pi 5 (future) | NA AGH backend only |

Nodes that are AGH backends only (`dns-tor-02`, `dns-hou-01`) run Pi-hole, Unbound, and AGH on `:5353` — but not dnsdist, the watchdog, or the loopback VIP alias.

---

## Alternative Approaches Considered

**Technitium DNS Server** — a single process replacing the dnsdist + AGH + Unbound stack entirely, with built-in forwarder failover and DNSSEC; simpler stack but less compositional and less battle-tested.

**Unbound with RPZ** — drops the filtering application and uses Unbound's native Response Policy Zone support to apply blocklists inside the recursive resolver; loses the web UI but reduces the process count to one.

**CoreDNS as proxy layer** — replaces dnsdist with a simpler Corefile-configured forwarder that integrates naturally with k3s; less DNS-specific than dnsdist but easier to configure.

**NextDNS as filtering-aware fallback** — a managed cloud resolver with matching blocklists as a last-resort backend so filtering still applies if all local nodes are down; introduces a privacy trade-off.

**Tailscale API controller** — a daemon that dynamically rewrites the Tailscale nameserver list based on health checks; valid as a secondary remediation layer but too slow and fragile to be the primary failover mechanism.

---

## Appendix A: Per-Node Setup

Apply to **dns-ams-01**, **dns-tor-01**, and **dns-tor-02**.

```bash
# 0. MANDATORY PRE-STEP: Restrict Pi-hole to LAN interface only
#    Pi-hole FTL binds 0.0.0.0:53 by default, which will conflict with dnsdist.
#    This must be set before dnsdist is installed.
sudo pihole-FTL --config dns.listeningMode BIND
sudo systemctl restart pihole-FTL

# Verify Pi-hole is no longer on wildcard:
sudo ss -tulpen | grep ':53 '
# Expected: Pi-hole bound only to LAN IP (e.g. 192.168.x.x:53), NOT 0.0.0.0:53

# 1. Disable DNS acceptance on Tailscale
sudo tailscale set --accept-dns=false

# 2. Install Unbound
sudo apt update && sudo apt install -y unbound
sudo systemctl disable --now systemd-resolved 2>/dev/null || true
sudo curl -o /var/lib/unbound/root.hints https://www.internic.net/domain/named.cache
sudo systemctl enable --now unbound

# 3. Set Pi-hole upstream to local Unbound (web UI)
#    Settings → DNS → Custom upstream: 127.0.0.1#5335
#    Uncheck all other upstream providers
#    Confirm Pi-hole is NOT binding to loopback:
sudo ss -ulnp | grep :53

# 4. Install AdGuard Home (on port 5353, restricted interfaces)
curl -fsSL https://raw.githubusercontent.com/AdguardTeam/AdGuardHome/master/scripts/install.sh \
  | sudo sh -s -- -v
# After initial setup on :3000:
#   - Change DNS listening to 127.0.0.1:5353 AND <tailscale_ip>:5353 in AGH settings
#     (do NOT use 0.0.0.0:5353 — this exposes AGH DNS on the LAN interface)
#   - Set AGH upstream DNS to 127.0.0.1:5335 (Unbound)

# Firewall: allow port 5353 only from DNS edge Tailscale IPs; block from LAN clients
# Replace TS_IPs with actual Tailscale IPs of dns-ams-01, dns-tor-01, dns-tor-02
sudo ufw allow from <AMS01_TS_IP> to any port 5353 proto udp
sudo ufw allow from <TOR01_TS_IP> to any port 5353 proto udp
sudo ufw allow from <TOR02_TS_IP> to any port 5353 proto udp
sudo ufw allow from 127.0.0.1 to any port 5353 proto udp
sudo ufw deny to any port 5353 proto udp
sudo ufw reload

# 5. Install dnsdist
echo "deb [arch=arm64 signed-by=/usr/share/keyrings/dnsdist.gpg] \
  http://repo.powerdns.com/debian bookworm-dnsdist-19 main" \
  | sudo tee /etc/apt/sources.list.d/dnsdist.list
curl -fsSL https://repo.powerdns.com/FD380FBB-pub.asc \
  | sudo gpg --dearmor -o /usr/share/keyrings/dnsdist.gpg
sudo apt update && sudo apt install -y dnsdist

# 6. Bind VIP to loopback
sudo ip addr add 10.253.53.53/32 dev lo
# (Make persistent — see Appendix B for systemd unit)

# 7. Tag the node and advertise VIP via Tailscale
# First: assign tag:dns-edge to this node in Tailscale admin console (Machines → Edit tags)
# Then:
sudo tailscale set --advertise-routes=10.253.53.53/32
# Route is auto-approved because tag:dns-edge is in autoApprovers (see Core Mechanism section)
# No manual approval needed — required for watchdog to function correctly

# 8. Enable services
sudo systemctl enable --now dns-vip-bind
sudo systemctl enable --now dnsdist
sudo systemctl enable --now dns-vip-watchdog

# 9. Verify port layout
sudo ss -ulnp | grep :53
# Expected:
#   Pi-hole   → eth0_IP:53, tailscale0_IP:53
#   dnsdist   → 10.253.53.53:53
# Expected on :5353:
#   AGH       → 0.0.0.0:5353
# Expected on :5335:
#   Unbound   → 127.0.0.1:5335
```

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

The preamble (bind, ACL, policy, webserver) is identical on all edges — only the `newServer` pool entries differ.

```lua
-- ============================================================
-- PREAMBLE — identical on all edge nodes
-- ============================================================

-- Bind to VIP on loopback only
setLocal("10.253.53.53:53")

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

webserver("127.0.0.1:8083")
setWebserverConfig({ password = "changeme", apiKey = "changeme" })

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

## Appendix C: Watchdog Script

### `/usr/local/sbin/dns-vip-watchdog.sh`

Requires 3 consecutive failures before withdrawing the route, and 2 consecutive successes before re-advertising. Preserves any other advertised Tailscale routes when modifying.

The probe checks **both** resolution (a known-good domain must return an IP) and filtering (a known-blocked domain must return NXDOMAIN or `0.0.0.0`). This ensures the watchdog reflects true service health, not just raw DNS connectivity through the unfiltered Unbound layer.

`test.blocked.internal` is a custom rewrite entry in AdGuard Home configured to block (return NXDOMAIN). Use a dedicated test entry rather than relying on a real ad domain like `doubleclick.net`, since block response style for real domains may vary across blocklist updates.

```bash
#!/usr/bin/env bash
set -euo pipefail

VIP="10.253.53.53/32"
TEST_DOMAIN="cloudflare.com"
BLOCKED_DOMAIN="test.blocked.internal"   # Custom block entry in AGH — must return NXDOMAIN
FAIL_THRESHOLD=3
SUCCESS_THRESHOLD=2
PROBE_INTERVAL=5
LOG_TAG="dns-watchdog"

fail_count=0
success_count=0
advertised=true

log() { logger -t "$LOG_TAG" "$*"; echo "$(date -Is) $*" >&2; }

is_dns_healthy() {
  # Check 1: known-good domain must resolve to an IP
  dig @10.253.53.53 "$TEST_DOMAIN" +time=2 +tries=1 +short A \
    | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' || return 1

  # Check 2: known-blocked domain must NOT resolve (filtering is active)
  local blocked_result
  blocked_result=$(dig @10.253.53.53 "$BLOCKED_DOMAIN" +time=2 +tries=1 +short A 2>/dev/null)
  # NXDOMAIN or 0.0.0.0 response = blocked = healthy; any real IP = AGH filtering not working
  [[ -z "$blocked_result" || "$blocked_result" == "0.0.0.0" ]]
}

get_other_routes() {
  tailscale status --json 2>/dev/null \
    | python3 -c "
import sys, json
d = json.load(sys.stdin)
routes = d.get('Self', {}).get('AllowedIPs', [])
print(','.join(r for r in routes if r != '10.253.53.53/32'))" 2>/dev/null || echo ""
}

advertise_vip() {
  local others; others=$(get_other_routes)
  local routes="$VIP"
  [ -n "$others" ] && routes="${others},${VIP}"
  sudo tailscale set --advertise-routes="$routes"
}

withdraw_vip() {
  sudo tailscale set --advertise-routes="$(get_other_routes)"
}

log "Watchdog started. VIP=$VIP interval=${PROBE_INTERVAL}s"

while true; do
  if is_dns_healthy; then
    fail_count=0
    ((success_count++)) || true
    if ! $advertised && [ "$success_count" -ge "$SUCCESS_THRESHOLD" ]; then
      log "DNS healthy (${success_count} consecutive) — re-advertising $VIP"
      advertise_vip && advertised=true && success_count=0
    fi
  else
    success_count=0
    ((fail_count++)) || true
    log "DNS probe failed (${fail_count}/${FAIL_THRESHOLD})"
    if $advertised && [ "$fail_count" -ge "$FAIL_THRESHOLD" ]; then
      log "ALERT: DNS unhealthy — withdrawing $VIP"
      withdraw_vip && advertised=false && fail_count=0
    fi
  fi
  sleep "$PROBE_INTERVAL"
done
```

### `/etc/systemd/system/dns-vip-watchdog.service`

```ini
[Unit]
Description=HA DNS VIP Watchdog
After=dnsdist.service tailscaled.service dns-vip-bind.service
Requires=dnsdist.service tailscaled.service

[Service]
Type=simple
ExecStart=/usr/local/sbin/dns-vip-watchdog.sh
Restart=always
RestartSec=10
User=root

[Install]
WantedBy=multi-user.target
```

```bash
sudo chmod +x /usr/local/sbin/dns-vip-watchdog.sh
sudo systemctl daemon-reload
sudo systemctl enable --now dns-vip-watchdog.service
```

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
dig @10.253.53.53 test.blocked.internal        # should return NXDOMAIN (custom AGH block entry)
```

Verify each edge node directly:
```bash
dig @DNS_AMS_TS_IP  -p 5353 cloudflare.com   # AGH on dns-ams-01
dig @DNS_TOR01_TS_IP -p 5353 cloudflare.com  # AGH on dns-tor-01
dig @DNS_TOR02_TS_IP -p 5353 cloudflare.com  # AGH on dns-tor-02
```

---

## Appendix E: Rollout Plan

Build and verify incrementally. Do not point Tailscale DNS at the VIP until all failover scenarios have been tested.

```
1. Build dns-ams-01 only.
   - Install and verify: Pi-hole (BIND mode), Unbound, AGH (port 5353, restricted interfaces), dnsdist.
   - Confirm port layout with: sudo ss -tulpen | grep -E ':53|:5353|:5335'
   - Confirm AGH resolves and blocks: dig @127.0.0.1 -p 5353 cloudflare.com
                                       dig @127.0.0.1 -p 5353 test.blocked.internal
   - Confirm dnsdist routes correctly: dig @10.253.53.53 cloudflare.com
   - Start watchdog; confirm it logs healthy probes.

2. Build dns-tor-01. Add it to dns-ams-01's dnsdist pool; verify cross-site AGH resolution.
   - Assign tag:dns-edge; advertise 10.253.53.53/32; confirm auto-approval in Tailscale console.

3. Build dns-tor-02. Repeat.

4. Test AGH backend failover.
   - Stop AGH on dns-ams-01: sudo systemctl stop adguardhome
   - Confirm dnsdist fails over to dns-tor-01 within ~1s (health check interval).
   - Restart AGH; confirm it comes back into rotation.

5. Test dnsdist crash → watchdog → route withdrawal.
   - Stop dnsdist: sudo systemctl stop dnsdist
   - Wait 15–20s; confirm watchdog withdraws 10.253.53.53/32 (check tailscale status).
   - Restart dnsdist; confirm watchdog re-advertises after 2 successful probes.

6. Test node-level failover.
   - Power off or stop Tailscale on dns-ams-01: sudo tailscale down
   - Confirm Tailscale routes to dns-tor-01 within ~15s.
   - Confirm DNS still resolves from a tailnet client during the failover window.

7. Only after steps 1–6 pass: replace Tailscale nameservers with 10.253.53.53.
   - In Tailscale Admin Console → Settings → DNS, replace all Pi-hole entries with 10.253.53.53.

8. Test iOS last (most sensitive to DNS failure).
   - Disconnect and reconnect Tailscale on iOS.
   - Confirm DNS resolves and ad blocking is active.
   - Simulate an edge node failure; confirm iOS continues resolving within ~30s.
```

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

# Option 3: SSH to a DNS edge node and force-reset
ssh dns-ams-01
sudo tailscale set --advertise-routes=   # withdraw VIP
sudo systemctl restart dnsdist
sudo tailscale set --advertise-routes=10.253.53.53/32  # re-advertise
```

### Monitoring Checklist

Run these periodically or wire into a monitoring tool (e.g. Uptime Kuma, Prometheus blackbox exporter):

```bash
# Resolution health (should return an IP)
dig @10.253.53.53 cloudflare.com +short

# Filtering health (should return NXDOMAIN or empty)
dig @10.253.53.53 test.blocked.internal +short

# dnsdist backend health (via dnsdist web API — adjust password)
curl -s -H 'X-API-Key: changeme' http://127.0.0.1:8083/api/v1/servers/localhost/pool \
  | python3 -m json.tool

# AGH sync status (from Mac Studio — check last sync time)
docker logs adguardhome-sync --tail 20

# Unbound recursion
dig @127.0.0.1 -p 5335 cloudflare.com +short

# Watchdog route state
tailscale status --json | python3 -c \
  "import sys,json; d=json.load(sys.stdin); \
   print([r for r in d.get('Self',{}).get('AllowedIPs',[]) if '10.253.53' in r])"

# Tailscale node online state (run from another tailnet node)
tailscale ping dns-ams-01
tailscale ping dns-tor-01
tailscale ping dns-tor-02
```
