# HA DNS — Implementation Phases

> Ordered rollout plan. Each phase has prerequisites, steps, and acceptance criteria. Do not advance to the next phase until the current phase's acceptance criteria are met. Status of each phase lives in [`PROGRESS.md`](PROGRESS.md), not here.

## Overview

| # | Phase | Prerequisites |
|---|---|---|
| 1 | Bootstrap first edge (`dns-ams-01`) | `dns-ha-agent` v1.0 built and digest known |
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
dig @127.0.0.1 -p 5335 cloudflare.com +trace
# Expected: full root → TLD → authoritative chain.
# Fix if not: check /etc/unbound/unbound.conf.d/ for any forward-zone blocks and remove.

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
dig @10.253.53.53 test.blocked.internal +short
# Expected: empty (filtered).

# 8. Tag the node in Tailscale admin (do NOT manually advertise VIP — agent does it).
#    Tailscale admin console → Machines → dns-ams-01 → Edit tags → add tag:dns-edge

# 9. Deploy dns-ha-agent via ansible.
ansible-playbook -l dns-ams-01 playbooks/dns-ha-agent.yml
# Agent transitions STARTING → HEALTHY after ~6s of successful probes,
# then calls `tailscale set --advertise-routes=10.253.53.53/32`.

# 10. Verify port layout (Pi-hole in BIND mode = LAN IP ONLY, NOT eth0+tailscale0)
sudo ss -tulpen | grep -E ':53|:5353|:5335|:8091|:3000'
# Expected:
#   Pi-hole   → LAN_IP:53 (udp+tcp)
#   dnsdist   → 10.253.53.53:53 (udp+tcp)
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
- [ ] `dig @127.0.0.1 -p 5353 test.blocked.internal` returns empty (filtering works).
- [ ] `dig @127.0.0.1 -p 5335 cloudflare.com +trace` shows the full recursive chain (Unbound is recursing, not forwarding).
- [ ] `dig @10.253.53.53 cloudflare.com` resolves via dnsdist (full path works).
- [ ] `dig +dnssec +bufsize=1232 @10.253.53.53 cloudflare.com` returns an answer (no UDP truncation).
- [ ] `curl http://AMS01_TS_IP:8091/health | jq '.state'` returns `"HEALTHY"`.
- [ ] `curl http://AMS01_TS_IP:8091/health | jq '.vip_advertised'` returns `true`.
- [ ] `tailscale status` on dns-ams-01 shows `10.253.53.53/32` advertised and approved.
- [ ] AGH admin port `:3000` is NOT reachable from a non-admin tailnet client.

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

### Test 6a — AGH backend failover

```bash
ssh dns-ams-01
sudo systemctl stop adguardhome
```

- [ ] dnsdist on dns-ams-01 fails over to dns-iom-01 backend within ~1s (dnsdist health-check interval).
- [ ] dns-ha-agent on dns-ams-01 transitions to UNHEALTHY within ~4s.
- [ ] Agent withdraws route (`tailscale status` no longer shows VIP).
- [ ] Tailscale fails over to dns-iom-01 within ~15s.

Restore:

```bash
sudo systemctl start adguardhome
```

- [ ] Agent re-advertises within ~6s (3 successful probes).
- [ ] Route auto-approves.

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

### Test 6c — partition detection

```bash
ssh dns-ams-01
sudo iptables -A OUTPUT -d IOM_TS_IP -p tcp --dport 8091 -j DROP
sudo iptables -A OUTPUT -d TOR_TS_IP -p tcp --dport 8091 -j DROP
sudo iptables -A OUTPUT -d SHZ_TS_IP -p tcp --dport 8091 -j DROP
```

- [ ] After ≥60s (`peer_unreachable_threshold`), dns-ams-01 agent transitions to PARTITIONED.
- [ ] Agent withdraws route.
- [ ] EU continues serving from dns-iom-01.

Restore:

```bash
sudo iptables -D OUTPUT -d IOM_TS_IP -p tcp --dport 8091 -j DROP
sudo iptables -D OUTPUT -d TOR_TS_IP -p tcp --dport 8091 -j DROP
sudo iptables -D OUTPUT -d SHZ_TS_IP -p tcp --dport 8091 -j DROP
```

- [ ] Agent recovers to HEALTHY within 15s.

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

### Test 6e — n8n external route fencing (fail-open)

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
- [ ] External per-edge DNS probe to `<AMS01_TS_IP>:5353` fails (or returns broken results).
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
- Test 6a/b/c/d/e all passed (including the n8n fail-open fencing test).
- n8n route-fencing workflow is deployed on the Mac Studio with the `tag:dns-fencer` ACL and a working Tailscale API token; alerts are reaching their destination.
- dns-ha-agent acceptance criteria in `dns-ha-agent/PHASES.md` all passed.

### Steps

1. Tailscale Admin Console → Settings → DNS.
2. Add nameserver → Custom → `10.253.53.53`.
3. Enable "Override local DNS".
4. **Do not yet remove** the old Pi-hole nameserver entries — keep them as a safety net.
5. From macOS, verify: `tailscale dns status` shows `10.253.53.53`; `dig @10.253.53.53 cloudflare.com` resolves; `dig @10.253.53.53 test.blocked.internal` returns NXDOMAIN.
6. Observe normal use for 24 hours. Watch agent logs and dnsdist logs for anomalies.
7. iOS test: disconnect and reconnect Tailscale on iOS. Confirm DNS resolves and filtering is active.
8. Failover test from a real client: stop an edge, confirm iOS continues resolving within ~30s.
9. Only after 24h of clean operation, remove old Pi-hole entries from Tailscale DNS settings.

### Acceptance criteria

- [ ] iOS resolves DNS via the VIP without manual reconnects.
- [ ] iOS continues resolving during an induced edge failure within ≤30s.
- [ ] Filtering remains active on all client paths (ad-tracker domains blocked).
- [ ] No client-visible breakage during 24h observation period.
- [ ] Old Pi-hole nameserver entries removed from Tailscale DNS settings.

---

## Phase 9 — Technitium evaluation (parallel, optional)

**Goal**: evaluate Technitium DNS Server as a potential future replacement for the dnsdist + AGH + Unbound stack. Does NOT block any other phase.

See `BLUEPRINT.md` Appendix G for full eval plan, setup outline, success metrics, and the migrate/keep/reject decision framework. Outcomes recorded in `PROGRESS.md`.

### Acceptance criteria

- [ ] Technitium runs as agh-hou-01 backend in dns-tor-01's dnsdist pool for ≥4 weeks.
- [ ] All 6 measurement areas (blocklist parity, DNSSEC, API sync, resource use, blocklist-failure behaviour, operator UX) documented.
- [ ] Migrate / keep-AGH / reject decision recorded in `PROGRESS.md`.
