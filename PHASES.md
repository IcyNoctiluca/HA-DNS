# HA DNS — Implementation Phases

> Ordered rollout plan. Each phase has prerequisites, steps, and acceptance criteria. Do not advance to the next phase until the current phase's acceptance criteria are met. Status of each phase lives in [`PROGRESS.md`](PROGRESS.md), not here.

## Overview

| # | Phase | Prerequisites |
|---|---|---|
| 0 | Prerequisites (filter sentinel domain) | A domain you can publish A records on |
| 1 | Bootstrap first edge (`dns-ams-01`) | Phase 0 complete; `dns-ha-agent` v1.0 built and digest known |
| 2 | Bootstrap second EU edge (`dns-iom-01`) | Phase 1 acceptance passed |
| 3 | Bootstrap NA edge (`dns-tor-01`) | Phase 2 acceptance passed |
| 4 | Add NA AGH backend (`dns-tor-02`) | Phase 3 acceptance passed |
| 5 | Bootstrap APAC edge (`dns-shz-01`) | Phase 4 acceptance passed |
| 6 | Verify failover behaviour | Phase 5 acceptance passed |
| 7 | Empirically verify Tailscale primary-selection | Phase 6 acceptance passed |
| 8 | Production cutover (point Tailscale DNS at VIP) | Phase 7 acceptance passed |
| 9 | Technitium evaluation (parallel, optional) | Phase 8 stable for ≥2 weeks |

Note: The `dns-ha-agent` service has its own implementation phases in [`dns-ha-agent/PHASES.md`](dns-ha-agent/PHASES.md). Phase 1 below assumes the agent is already built and an image digest is available.

---

## Phase 0 — Prerequisites

These must be in place before Phase 1 starts.

### Filter sentinel domain

The agent's local probe and several rollout/monitoring checks rely on a **filter sentinel** — a domain that resolves publicly in normal DNS but is explicitly blocked by AGH. Using a made-up internal name (e.g., `test.blocked.internal`) is unsafe because Unbound returns NXDOMAIN for it whether AGH is in the path or not, so a filter bypass looks identical to a working filter.

1. Pick a subdomain of a domain you control (e.g., `dns-filter-sentinel.<your-public-domain>`).
2. Create a real public A record for it (any routable IP works — a 192.0.2.x TEST-NET-1 address, or your own homelab IP, as long as it's NOT `0.0.0.0` and would actually resolve if AGH were bypassed).
3. On the primary AGH instance (`dns-ams-01`), add a custom block rule for that exact name. adguardhome-sync will propagate the rule to all replicas.
4. Choose and standardise the **expected AGH block response** across all replicas. The agent's `probe.block_expectation` validates that the rcode is in `allowed_rcodes` (NXDOMAIN / REFUSED / NOERROR) AND, on NOERROR, that every returned address is in `allowed_ips` (default `0.0.0.0`). Pick AGH's blocking mode once, document it here, and confirm every replica behaves the same way.
5. Verify the asymmetry from any machine before continuing:
   ```bash
   dig @1.1.1.1 dns-filter-sentinel.<your-public-domain> +short
   # Expected: the public A record value (or your chosen routable IP)

   dig @<AGH_AMS_TS_IP> -p 5353 dns-filter-sentinel.<your-public-domain>
   # Expected behaviour MUST match the configured block_expectation:
   #   - rcode NXDOMAIN / REFUSED, OR
   #   - rcode NOERROR with answer 0.0.0.0 (or whatever you set in allowed_ips)
   # Anything that returns the real public A record value is a FAILURE.
   ```
6. Record the sentinel name in `dns_ha_agent_filter_sentinel` (Ansible group var) so the agent config, dnsdist filter probes, and monitoring scripts all reference the same value.

Acceptance:

- [ ] Sentinel domain resolves publicly via `1.1.1.1` to the configured A record.
- [ ] Sentinel domain returns a response matching `block_expectation` (NXDOMAIN / REFUSED / NOERROR + allowed_ip) via `AGH_AMS:5353`.
- [ ] adguardhome-sync has propagated the rule to all replicas (`agh-iom-01`, `agh-tor-01`, etc.) — verified by repeating the AGH probe against each replica AND confirming the response shape matches across all of them. Drift between replicas (e.g. one returns NXDOMAIN, another returns NOERROR + 0.0.0.0) is acceptable only if every variant is in the configured `allowed_rcodes` / `allowed_ips` set.

---

## Phase 1 — Bootstrap first edge (`dns-ams-01`)

**Goal**: stand up a single DNS edge end-to-end on the AMS Pi 5, with dns-ha-agent advertising the VIP. No Tailscale DNS cutover yet — clients still use existing Pi-hole nameservers.

### Per-node setup steps

Apply on `dns-ams-01`. (For Phases 2–5 the same setup applies; AGH-backend-only nodes in Phase 4 skip steps 5, 6, 8, 9.)

```bash
# 0. MANDATORY PRE-STEP: Restrict Pi-hole to LAN interface only.
#    Pi-hole FTL binds 0.0.0.0:53 by default, which will conflict with dnsdist.
sudo pihole-FTL --config dns.listeningMode BIND
sudo systemctl restart pihole-FTL
sudo ss -tulpen | grep ':53 '
# Expected: Pi-hole bound only to LAN IP, NOT 0.0.0.0:53

# 1. Disable DNS acceptance on Tailscale
sudo tailscale set --accept-dns=false

# 2. Install Unbound
sudo apt update && sudo apt install -y unbound
sudo systemctl disable --now systemd-resolved 2>/dev/null || true
sudo curl -o /var/lib/unbound/root.hints https://www.internic.net/domain/named.cache
sudo systemctl enable --now unbound

# 2a. VERIFY Unbound is doing recursive resolution (not silently forwarding).
#
# CORRECTED 2026-08-23: this step used to be `dig ... +trace`, which cannot
# prove it. `+trace` queries the server under test only for the initial
# root-NS lookup and then walks the delegation from the *client* host, so it
# passes identically against a pure forwarder.
#
# The DNSSEC `ad` flag is the real evidence: it is set only when the answering
# resolver built and validated the chain itself, which requires fetching DS and
# DNSKEY records down the delegation path.
dig @127.0.0.1 -p 5335 +dnssec cloudflare.com | grep 'flags:'
# Expected: `flags: qr rd ra ad` — the `ad` is the one that matters.

dig @127.0.0.1 -p 5335 dnssec-failed.org
# Expected: status: SERVFAIL — validation fails closed rather than passing
# through a bogus signature.

grep -rE '^\s*(forward-zone|forward-addr|forward-host):' /etc/unbound/
# Expected: no output. Any forwarder here makes the resolver a proxy for a
# third party, which is the dependency this component exists to remove.

# 3. Set Pi-hole upstream to local Unbound (web UI)
#    Settings → DNS → Custom upstream: 127.0.0.1#5335
#    Uncheck all other upstream providers.

# 4. Install AdGuard Home (on port 5353, restricted interfaces)
curl -fsSL https://raw.githubusercontent.com/AdguardTeam/AdGuardHome/master/scripts/install.sh \
  | sudo sh -s -- -v
# After initial setup on :3000:
#   - DNS listening to 127.0.0.1:5353 AND <tailscale_ip>:5353 (NOT 0.0.0.0)
#   - Upstream DNS: 127.0.0.1:5335 (Unbound)

# 4a. Firewall: allow port 5353 (BOTH UDP and TCP) only from DNS edge Tailscale IPs.
#     TCP required for DNSSEC large responses and UDP-truncation fallback.
for EDGE in <AMS01_TS_IP> <IOM01_TS_IP> <TOR01_TS_IP> <TOR02_TS_IP> <SHZ01_TS_IP>; do
  sudo ufw allow from $EDGE to any port 5353 proto udp
  sudo ufw allow from $EDGE to any port 5353 proto tcp
done
sudo ufw allow from 127.0.0.1 to any port 5353 proto udp
sudo ufw allow from 127.0.0.1 to any port 5353 proto tcp
sudo ufw deny to any port 5353 proto udp
sudo ufw deny to any port 5353 proto tcp

# 4b. Firewall: AGH admin/API port 3000 — restrict to sync daemon + admin devices.
sudo ufw allow from <MAC_STUDIO_TS_IP> to any port 3000 proto tcp
sudo ufw allow from <ADMIN_LAPTOP_TS_IP> to any port 3000 proto tcp
sudo ufw allow from 127.0.0.1 to any port 3000 proto tcp
sudo ufw deny to any port 3000 proto tcp

# 4c. Firewall: dns-ha-agent /health + /metrics port 8091 — Tailscale only.
#     Defence-in-depth alongside the agent binding to the Tailscale IP.
#     Tailscale ACLs do NOT protect LAN-side traffic, so this UFW rule is
#     what stops a host on the same LAN from scraping the agent.
for EDGE in <AMS01_TS_IP> <IOM01_TS_IP> <TOR01_TS_IP> <SHZ01_TS_IP>; do
  sudo ufw allow from $EDGE to any port 8091 proto tcp
done
sudo ufw allow from <MAC_STUDIO_TS_IP> to any port 8091 proto tcp   # n8n fencer + Prometheus
sudo ufw allow from 127.0.0.1 to any port 8091 proto tcp
sudo ufw deny to any port 8091 proto tcp

# 4d. Firewall: dnsdist diagnostic listener port 1053 — full edge path
#     for n8n external fencing probes (Appendix E). Same restriction model
#     as :8091: edge peers + Mac Studio (n8n) + loopback only, deny LAN.
for EDGE in <AMS01_TS_IP> <IOM01_TS_IP> <TOR01_TS_IP> <SHZ01_TS_IP>; do
  sudo ufw allow from $EDGE to any port 1053 proto udp
  sudo ufw allow from $EDGE to any port 1053 proto tcp
done
sudo ufw allow from <MAC_STUDIO_TS_IP> to any port 1053 proto udp
sudo ufw allow from <MAC_STUDIO_TS_IP> to any port 1053 proto tcp
sudo ufw allow from 127.0.0.1 to any port 1053 proto udp
sudo ufw allow from 127.0.0.1 to any port 1053 proto tcp
sudo ufw deny to any port 1053 proto udp
sudo ufw deny to any port 1053 proto tcp
sudo ufw reload

# 5. Install dnsdist
echo "deb [arch=arm64 signed-by=/usr/share/keyrings/dnsdist.gpg] \
  http://repo.powerdns.com/debian bookworm-dnsdist-19 main" \
  | sudo tee /etc/apt/sources.list.d/dnsdist.list
curl -fsSL https://repo.powerdns.com/FD380FBB-pub.asc \
  | sudo gpg --dearmor -o /usr/share/keyrings/dnsdist.gpg
sudo apt update && sudo apt install -y dnsdist
# Install dnsdist config per BLUEPRINT.md Appendix B (this edge's regional pool).

# 6. Bind VIP to loopback (systemd unit from BLUEPRINT.md Appendix B)
sudo systemctl enable --now dns-vip-bind

# 7. Start dnsdist BEFORE deploying the agent. Verify locally.
sudo systemctl enable --now dnsdist
dig @10.253.53.53 cloudflare.com +short
# Expected: a real A record.
dig @10.253.53.53 dns-filter-sentinel.<your-public-domain> +short
# Expected: empty (filtered).

# 8. Tag the node in Tailscale admin (do NOT manually advertise VIP — agent does it).
#    Tailscale admin console → Machines → dns-ams-01 → Edit tags → add tag:dns-edge

# 9. Deploy dns-ha-agent via ansible.
ansible-playbook -l dns-ams-01 playbooks/dns-ha-agent.yml
# Agent transitions STARTING → HEALTHY after ~6s of successful probes,
# then calls `tailscale set --advertise-routes=10.253.53.53/32`.

# 10. Verify port layout (Pi-hole in BIND mode = LAN IP ONLY, NOT eth0+tailscale0)
sudo ss -tulpen | grep -E ':53|:5353|:5335|:8091|:3000|:1053'
# Expected:
#   Pi-hole   → LAN_IP:53 (udp+tcp)
#   dnsdist   → 10.253.53.53:53 (udp+tcp)
#   dnsdist   → tailscale0_IP:1053 (udp+tcp) — diagnostic listener for n8n
#   AGH       → 127.0.0.1:5353, tailscale0_IP:5353 (udp+tcp)
#   AGH admin → 127.0.0.1:3000, tailscale0_IP:3000 (tcp)
#   Unbound   → 127.0.0.1:5335 (udp+tcp)
#   dns-ha-agent → tailscale0_IP:8091 (tcp)
```

### DNSSEC + EDNS0 large-response verification

Large signed responses can be silently truncated by UDP-only paths with small buffers. Both of the following must return a real answer:

```bash
dig +dnssec +bufsize=1232 @10.253.53.53 cloudflare.com
dig +dnssec +bufsize=1232 +tcp @10.253.53.53 cloudflare.com
```

If the UDP version returns no answer or truncated, check dnsdist + AGH EDNS0 buffer settings and host MTU.

### Auto-approval timing caveat

If `10.253.53.53/32` was ever manually advertised on this node before `autoApprovers` was added to the Tailscale ACL, the route stays pending and the agent's re-advertisement does not auto-approve. Fix:

```bash
sudo tailscale set --advertise-routes=     # withdraw
docker restart dns-ha-agent                # agent re-advertises; auto-approves this time
```

### Acceptance criteria

- [ ] `sudo ss -tulpen | grep -E ':53|:5353|:5335|:8091|:3000'` shows the expected port layout (Pi-hole on LAN IP only).
- [ ] `dig @127.0.0.1 -p 5353 cloudflare.com` returns an A record (local AGH works).
- [ ] `dig @127.0.0.1 -p 5353 dns-filter-sentinel.<your-public-domain>` returns empty (filtering works).
- [ ] `dig @127.0.0.1 -p 5335 +dnssec cloudflare.com` includes the `ad` flag, `dig @127.0.0.1 -p 5335 dnssec-failed.org` returns `SERVFAIL`, and `/etc/unbound/` contains no `forward-zone`, `forward-addr`, or `forward-host` directives (Unbound recurses and validates rather than forwards).
- [ ] `dig @10.253.53.53 cloudflare.com` resolves via dnsdist (full path works).
- [ ] `dig @<AMS01_TS_IP> -p 1053 cloudflare.com` resolves via dnsdist's diagnostic listener (same full path, edge-direct).
- [ ] `dig @<AMS01_TS_IP> -p 1053 dns-filter-sentinel.<your-public-domain>` returns empty (filter active on the diagnostic path).
- [ ] `dig +dnssec +bufsize=1232 @10.253.53.53 cloudflare.com` returns an answer (no UDP truncation).
- [ ] `curl http://AMS01_TS_IP:8091/health | jq '.state'` returns `"HEALTHY"`.
- [ ] `curl http://AMS01_TS_IP:8091/health | jq '.vip_advertised'` returns `true`.
- [ ] `tailscale status` on dns-ams-01 shows `10.253.53.53/32` advertised and approved.
- [ ] AGH admin port `:3000` is NOT reachable from a non-admin tailnet client.
- [ ] Agent `:8091` is NOT reachable from a LAN-only host (try `curl http://<LAN_IP_OF_EDGE>:8091/health` from a LAN device with no Tailscale — should fail). Belt and braces: the agent should be bound to the Tailscale IP, not 0.0.0.0, AND blocked from LAN by UFW.

---

## Phase 2 — Bootstrap second EU edge (`dns-iom-01`)

**Goal**: add the EU secondary so EU has real intra-region failover. Edges become aware of each other for split-brain detection.

### Steps

1. Run the per-node setup steps from Phase 1 on `dns-iom-01`. dnsdist config uses the IOM regional pool (see `BLUEPRINT.md` Appendix B).
2. Add `dns-iom-01` as a backend in `dns-ams-01`'s dnsdist config (order=20). Restart dnsdist on dns-ams-01.
3. Deploy `dns-ha-agent` on dns-iom-01 with `dns-ams-01` in its peer list.
4. Update `dns-ams-01`'s agent config to add dns-iom-01 to its peer list. Re-run ansible against dns-ams-01.

### Acceptance criteria

- [ ] All Phase 1 acceptance criteria still pass on dns-ams-01.
- [ ] Phase 1 acceptance criteria also pass on dns-iom-01.
- [ ] `dns-ams-01`'s `/health` shows `dns-iom-01` as reachable.
- [ ] `dns-iom-01`'s `/health` shows `dns-ams-01` as reachable.
- [ ] Cross-site AGH resolution works: from dns-ams-01, `dig @IOM01_TS_IP -p 5353 cloudflare.com` returns an A record.
- [ ] Both edges show `10.253.53.53/32` advertised in `tailscale status`.

---

## Phase 3 — Bootstrap NA edge (`dns-tor-01`)

**Goal**: extend to North America.

### Steps

1. Run per-node setup from Phase 1 on `dns-tor-01`. dnsdist config uses the NA regional pool.
2. Add `dns-tor-01` to peer lists of dns-ams-01 and dns-iom-01; re-run ansible against both.
3. Deploy `dns-ha-agent` on dns-tor-01 with dns-ams-01, dns-iom-01, and (future) dns-shz-01 in its peer list.

### Acceptance criteria

- [ ] All Phase 2 acceptance criteria still pass.
- [ ] Phase 1 acceptance criteria pass on dns-tor-01.
- [ ] All three agents see each other as reachable in `/health`.

---

## Phase 4 — Add NA AGH backend (`dns-tor-02`)

**Goal**: provide intra-region redundancy for NA. This node is AGH-only — no VIP advertisement, no dnsdist, no agent.

### Steps

1. Per-node setup from Phase 1 steps 0–4 only (Pi-hole, Unbound, AGH).
2. Skip steps 5–9 (no dnsdist, no VIP bind, no Tailscale tag, no agent).
3. Add dns-tor-02 as a backend in `dns-tor-01`'s dnsdist config (order=20). Restart dnsdist on dns-tor-01.
4. Add dns-tor-02 to adguardhome-sync replica list on Mac Studio.

### Acceptance criteria

- [ ] Phase 1 steps 0–4 acceptance criteria pass on dns-tor-02.
- [ ] dns-tor-02 has no `:53` dnsdist listener (only Pi-hole on LAN IP).
- [ ] dns-tor-02 does NOT have `tag:dns-edge`.
- [ ] dns-tor-02 does NOT advertise `10.253.53.53/32`.
- [ ] dns-tor-01's dnsdist health-checks both backends successfully.
- [ ] adguardhome-sync logs show dns-tor-02 receiving config updates.

---

## Phase 5 — Bootstrap APAC edge (`dns-shz-01`)

**Goal**: extend to APAC. Single-node region (no intra-region peers).

### Steps

1. Per-node setup from Phase 1 on dns-shz-01. dnsdist config uses the APAC regional pool (single backend).
2. Deploy `dns-ha-agent` with **empty peer list** — explicitly disables partition detection (no peers to consult).
3. Add dns-shz-01 to peer lists of dns-ams-01, dns-iom-01, dns-tor-01 — they observe the APAC edge even though it doesn't observe them.

### Acceptance criteria

- [ ] Phase 1 acceptance criteria pass on dns-shz-01.
- [ ] dns-shz-01's `/health` shows empty peer list and partition detection disabled.
- [ ] The three other edges show dns-shz-01 as reachable in their `/health` output.

---

## Phase 6 — Verify failover behaviour

**Goal**: prove the failure matrix in `BLUEPRINT.md` is actually correct before any client traffic touches the VIP.

### Test 6a-i — single AGH backend failure (edge stays HEALTHY)

The agent probes the local VIP (`10.253.53.53`), which goes through dnsdist → first healthy AGH backend in the regional pool. As long as *any* regional AGH backend is healthy, the edge is still useful: dnsdist transparently fails over and the agent's probes continue to succeed. The agent must NOT withdraw the route in this case — withdrawing would be premature and would lose a healthy edge.

```bash
ssh dns-ams-01
sudo systemctl stop adguardhome   # local AGH only; agh-iom-01 still up
```

- [ ] dnsdist on dns-ams-01 fails over to `agh-iom-01` backend within ~1s (dnsdist health-check interval).
- [ ] `dig @10.253.53.53 cloudflare.com` from a tailnet client continues to resolve.
- [ ] `dig @10.253.53.53 <FILTER_SENTINEL>` continues to be blocked (filtering still active via remote backend).
- [ ] dns-ha-agent on dns-ams-01 **stays HEALTHY** (`/health` `state` remains `HEALTHY`, `vip_advertised` remains `true`).
- [ ] dns-ams-01's `10.253.53.53/32` advertisement remains in `tailscale status`.

Restore:

```bash
sudo systemctl start adguardhome
```

- [ ] dnsdist re-pools the local backend within ~1s.
- [ ] Agent state remains HEALTHY throughout (it never left).

### Test 6a-ii — all regional AGH backends down (edge withdraws)

This is the fail-closed case from the Failure Matrix. With every backend in the edge's regional pool unhealthy, the agent's probe via `10.253.53.53` fails, the agent transitions to UNHEALTHY, and withdraws the route so Tailscale fails over to another region.

```bash
# Bring down both EU backends simultaneously.
ssh dns-ams-01 'sudo systemctl stop adguardhome'
ssh dns-iom-01 'sudo systemctl stop adguardhome'
```

- [ ] dns-ams-01's dnsdist marks both backends down within ~1s.
- [ ] dns-ams-01's agent transitions to UNHEALTHY within ~4s (`fail_threshold × interval`).
- [ ] Agent withdraws route (`tailscale status` no longer shows VIP).
- [ ] Tailscale routes EU clients to dns-tor-01 (NA) or dns-shz-01 (APAC) within ~15s.
- [ ] Filtering remains active on the new path (verify with `dig @10.253.53.53 <FILTER_SENTINEL>`).

Restore:

```bash
ssh dns-iom-01 'sudo systemctl start adguardhome'
ssh dns-ams-01 'sudo systemctl start adguardhome'
```

- [ ] Both agents re-advertise within ~6s (3 successful probes).
- [ ] Routes auto-approve.

### Test 6b — agent crash → restart → recovery

```bash
docker stop dns-ha-agent
```

- [ ] Within ~15s, agent withdraws route via SIGTERM handler.

```bash
docker start dns-ha-agent
```

- [ ] Agent transitions STARTING → HEALTHY within ~10s.
- [ ] Agent re-advertises route.
- [ ] Startup reconciliation log line visible: agent confirmed pre-existing advertisement (if any) was withdrawn before re-validating.

### Test 6c — PARTITIONED detection (asymmetric inbound only)

PARTITIONED is best-effort asymmetric-inbound-reachability protection, not a full quorum/split-brain system. It catches the case where this node can still poll its peers but peers cannot reach this node — the agent reads its own name from peers' `unreachable_peers` lists.

The previous version of this test blocked AMS's **outbound** traffic to peer `:8091`. That cannot work: a node that cannot poll its peers cannot learn that peers report it unreachable, and per the agent's own error-handling rule it stays HEALTHY. The fully-isolated case is covered by Layer 0 (Tailscale node-level failover) and Layer 2 (n8n external fencing) instead — see Test 6f.

The correct simulation blocks **inbound** access to AMS's `:8091` from each peer's Tailscale IP, while leaving AMS's outbound peer-poll traffic intact:

```bash
ssh dns-ams-01
sudo iptables -A INPUT -s IOM_TS_IP -p tcp --dport 8091 -j DROP
sudo iptables -A INPUT -s TOR_TS_IP -p tcp --dport 8091 -j DROP
sudo iptables -A INPUT -s SHZ_TS_IP -p tcp --dport 8091 -j DROP
```

- [ ] dns-iom-01, dns-tor-01, dns-shz-01 each begin to list `dns-ams-01` in their `unreachable_peers` array within ~30s (one peer poll cycle plus retries).
- [ ] dns-ams-01's PeerMonitor reads `dns-ams-01` in each peer's `unreachable_peers` and starts the `peer_unreachable_threshold` timer.
- [ ] After ≥60s, dns-ams-01 agent transitions to PARTITIONED.
- [ ] Agent withdraws route.
- [ ] EU continues serving from dns-iom-01.

Restore:

```bash
sudo iptables -D INPUT -s IOM_TS_IP -p tcp --dport 8091 -j DROP
sudo iptables -D INPUT -s TOR_TS_IP -p tcp --dport 8091 -j DROP
sudo iptables -D INPUT -s SHZ_TS_IP -p tcp --dport 8091 -j DROP
```

- [ ] Agent recovers to HEALTHY within 15s.

**Not a Phase 8 production gate.** If this test fails or is flaky on the asymmetric simulation, it does NOT block the cutover. PARTITIONED is an opportunistic safety net; the load-bearing protections for the partition class of failure are Layer 0 (Tailscale's own ~15s node-level failover) and Layer 2 (Test 6f, n8n route fencing).

### Test 6d — node-level failover

```bash
ssh dns-ams-01
sudo tailscale down
```

- [ ] Tailscale routes to dns-iom-01 within ~15s.
- [ ] DNS still resolves from a tailnet client during the failover window.

Restore:

```bash
sudo tailscale up
```

- [ ] dns-ams-01 reconverges; route is re-advertised once agent passes probes.

### Test 6e — remote tailnet client end-to-end

This is the cheapest acceptance check that catches the most embarrassing class of bug: VIP setup, ACLs, and Tailscale subnet-routing all work locally on the edge, but a real tailnet client can't actually reach the VIP. Run this **before** Phase 8 so the cutover is never the first time a non-edge client tries the VIP.

From a tailnet client that is NOT itself a DNS edge (laptop, phone via Tailscale, etc.):

```bash
dig @10.253.53.53 cloudflare.com +short
dig @10.253.53.53 dns-filter-sentinel.<your-public-domain> +short
tailscale ping 10.253.53.53
```

- [ ] `dig @10.253.53.53 cloudflare.com` returns a real A record from at least one of {AMS, IOM, TOR, SHZ} (depending on which edge Tailscale routes you to).
- [ ] `dig @10.253.53.53 dns-filter-sentinel.<your-public-domain>` returns empty (filtering active end-to-end).
- [ ] `tailscale ping 10.253.53.53` succeeds, confirming the subnet route is reachable from the client's view of the tailnet.
- [ ] Repeat from a second tailnet client in a different region (e.g., a phone on cellular vs. a desktop on home Wi-Fi). Both must succeed.

If this test fails, do NOT proceed to the production cutover. Common causes: missing `autoApprovers` entry, edge nodes never had `tag:dns-edge` actually applied, VIP not bound on loopback, or `accept-dns=false` not set on edges.

### Test 6f — n8n external route fencing (fail-open)

Validates Layer 2 of the failover model (see [`BLUEPRINT.md`](BLUEPRINT.md) §"External Route Fencing via n8n" and Appendix E). The goal is to confirm n8n correctly fences a zombie-advertised edge that the agent itself cannot withdraw.

Prerequisites: n8n workflow deployed on the Mac Studio with the `tag:dns-fencer` Tailscale tag and a scoped Tailscale API token.

```bash
ssh dns-ams-01

# Step 1: break the local DNS stack
sudo systemctl stop dnsdist

# Step 2: simulate the agent being unable to mutate Tailscale state.
# Pick one of (use whichever fits your container layout):
#   (a) Stop the agent entirely:  docker stop dns-ha-agent
#   (b) Break the Tailscale socket mount in a compose override and recreate the container
#   (c) Make the configured tailscale CLI path invalid in a test config override
```

- [ ] Either: dns-ha-agent reports `state != HEALTHY` and `dns_ha_agent_fail_open == 1`,
      OR: agent is unreachable from n8n and the Tailscale API still reports the route enabled.
- [ ] **Primary external probe** to `<AMS01_TS_IP>:1053` (dnsdist diagnostic listener) fails — this is the fencing signal because it exercises the full dnsdist → AGH → Unbound path.
- [ ] (Secondary diagnostic only) `<AMS01_TS_IP>:5353` may still resolve if AGH is locally healthy — this is informational, not the fencing trigger.
- [ ] Within ~60–90s (one n8n poll + threshold), n8n disables `dns-ams-01`'s `10.253.53.53/32` route approval via the Tailscale API.
- [ ] n8n sends the configured alert and writes an audit record (node, evidence, action, API response, timestamp).
- [ ] From a tailnet client, `dig @10.253.53.53 cloudflare.com` resolves via another edge.

AutoApprover interaction check:

- [ ] Confirm the disabled route stays disabled and is NOT immediately re-approved by `autoApprovers` while the node still has `tag:dns-edge`. If it is re-approved, move to the two-tag `tag:dns-edge-active` model documented in Appendix E.

Restore:

```bash
ssh dns-ams-01
sudo systemctl start dnsdist
# Undo whichever simulation step you used in Step 2
docker start dns-ha-agent  # if you stopped it
# In the Tailscale Admin Console, manually re-enable dns-ams-01's 10.253.53.53/32 route approval
# Clear the n8n fenced-state record so the next workflow run does not re-disable it
```

- [ ] dns-ams-01 returns to HEALTHY and re-advertises the VIP within ~10s of the route approval being restored.

---

## Phase 7 — Empirically verify Tailscale primary-selection

**Goal**: the HA design assumes "oldest-enrolled edge is primary" for Tailscale's HA subnet-router behaviour. Tailscale's free-tier algorithm is not formally documented. Confirm what actually happens before relying on it.

### Steps

1. From a tailnet client: `dig @10.253.53.53 cloudflare.com`.
2. On each edge, check which one received the query (dnsdist logs, or temporarily increase log verbosity).
3. Note the current primary edge as the baseline.
4. **Optional**: re-enrol one non-primary edge (force a newer enrollment date). Observe whether primary selection changes.

### Acceptance criteria

- [ ] Primary edge under normal conditions is documented in `PROGRESS.md`.
- [ ] If primary selection does NOT track enrollment order, the Failure Matrix and AGENTS.md are updated to reflect actual behaviour.
- [ ] One-off verification — do not repeat unless Tailscale changes algorithm.

---

## Phase 8 — Production cutover

**Goal**: switch Tailscale DNS to the VIP. iOS-first, the original motivation for this work.

### Pre-cutover gate

Do not advance to this phase until Phases 1–7 all show green acceptance criteria. Specifically:

- All edges report HEALTHY in `/health`.
- All edges advertise the VIP and routes are auto-approved.
- Test 6a-i/6a-ii/6b/6c/6d/6e/6f all passed (including the remote-client acceptance test and the n8n fail-open fencing test).
- n8n route-fencing workflow is deployed on the Mac Studio with the `tag:dns-fencer` ACL and a working Tailscale API token; alerts are reaching their destination.
- dns-ha-agent acceptance criteria in `dns-ha-agent/PHASES.md` all passed.

### Cutover principle: full cutover, not gradual mix

The v0 setup listed multiple Pi-hole nameservers as Tailscale globals — and one of the reasons we're doing this work at all is that iOS does not gracefully fall through that list when a resolver fails. Adding `10.253.53.53` to the same list "as a safety net" reintroduces exactly that ordering ambiguity: on iOS in particular, the OS may pick the old Pi-hole entries instead of the VIP, masking whether clients are actually using the new path and re-exposing the original resolver-order bug.

The cleaner cutover is a single deliberate switch with a fast rollback plan, validated first on a handful of real clients.

### Pre-cutover validation (on real client devices, NOT just the edges)

Before changing the tailnet-wide setting, manually override DNS on 2–3 representative clients to point at `10.253.53.53` only:

```bash
# macOS (per-network):
# System Settings → Network → <interface> → Details → DNS → set 10.253.53.53 as the only DNS server.

# iOS:
# Settings → Wi-Fi → (i) on current network → Configure DNS → Manual → add 10.253.53.53,
# then delete any other entries. Also do the same for Cellular if relevant.

# Linux client:
# echo "nameserver 10.253.53.53" | sudo tee /etc/resolv.conf
```

- [ ] All three clients resolve `dig @10.253.53.53 cloudflare.com` to a real IP.
- [ ] All three clients block `dig @10.253.53.53 dns-filter-sentinel.<your-public-domain>`.
- [ ] iOS in particular: lock the screen, leave the network for ≥5 minutes, return, confirm DNS still works without a manual Tailscale reconnect.
- [ ] Trigger an edge failure (e.g., `ssh dns-ams-01 'sudo systemctl stop dnsdist'`) and confirm the iOS client continues resolving within ≤30s. Restore the edge.
- [ ] Run these checks for at least 24 hours on the manual-override clients before flipping the tailnet-wide setting.

### Cutover

1. Tailscale Admin Console → Settings → DNS.
2. **Remove all existing Pi-hole nameserver entries.** Do not keep them as a "safety net" — see the principle above.
3. Add nameserver → Custom → `10.253.53.53`.
4. Enable "Override local DNS".
5. Save.
6. Remove the manual per-device overrides on the pre-cutover validation clients — they should now get `10.253.53.53` via the tailnet setting.
7. Observe normal use for 24 hours. Watch agent logs, dnsdist logs, and `dns_ha_agent_fail_open` for anomalies.
8. iOS test: disconnect and reconnect Tailscale on iOS. Confirm DNS resolves and filtering is active.
9. Failover test from a real client: stop an edge, confirm iOS continues resolving within ~30s.

### Rollback plan (keep ready, do NOT pre-configure)

If the cutover causes client-visible breakage:

```text
Tailscale Admin Console → Settings → DNS
  Remove: 10.253.53.53
  Re-add: <previous Pi-hole nameserver IPs>
  Save.
```

This is intentionally a manual rollback step rather than a parallel-nameserver safety net. The whole point of the cutover is to prove the VIP works for real clients; running both in parallel masks failures.

### Acceptance criteria

- [ ] Pre-cutover validation on 2–3 manual-override clients passed for ≥24h before flipping the tailnet setting.
- [ ] iOS resolves DNS via the VIP without manual reconnects.
- [ ] iOS continues resolving during an induced edge failure within ≤30s.
- [ ] Filtering remains active on all client paths (sentinel + ad-tracker domains blocked).
- [ ] No client-visible breakage during 24h observation period.
- [ ] Tailscale DNS settings contain `10.253.53.53` as the ONLY nameserver entry (no leftover Pi-hole IPs).

---

## Phase 9 — Technitium evaluation (parallel, optional)

**Goal**: evaluate Technitium DNS Server as a potential future replacement for the dnsdist + AGH + Unbound stack. Does NOT block any other phase.

See `BLUEPRINT.md` Appendix G for full eval plan, setup outline, success metrics, and the migrate/keep/reject decision framework. Outcomes recorded in `PROGRESS.md`.

### Acceptance criteria

- [ ] Technitium runs as agh-hou-01 backend in dns-tor-01's dnsdist pool for ≥4 weeks.
- [ ] All 6 measurement areas (blocklist parity, DNSSEC, API sync, resource use, blocklist-failure behaviour, operator UX) documented.
- [ ] Migrate / keep-AGH / reject decision recorded in `PROGRESS.md`.
