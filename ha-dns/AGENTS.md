# Agent Conventions — HA DNS

Read this before making any changes to the HA DNS design or rollout. These are invariants and conventions specific to this stack; violating them creates real outage or filtering-bypass risk.

## Critical context

- This is **production DNS** for the homelab tailnet. iOS, in particular, loses DNS entirely when its first-listed resolver fails — this whole design exists because the v0 setup was unreliable for iOS clients.
- The design is **fail-closed**. If filtering can't be applied, DNS breaks rather than silently resolving unfiltered. Do not introduce fallback paths that violate this.
- The `dns-ha-agent` is the **sole authority** for mutating Tailscale route advertisements for the HA VIP. Do not write code or scripts that call `tailscale set --advertise-routes=...` outside the agent.

## Where to read first

| Task | Read |
|---|---|
| "Why does the design look like this?" | `BLUEPRINT.md` §Context & Goals, §The Core Mechanism |
| "What's the current state of rollout?" | `PROGRESS.md`, then `PHASES.md` |
| "I need to add a new edge node" | `BLUEPRINT.md` §Adding Future AZ Nodes |
| "Something broke, how do I recover?" | `BLUEPRINT.md` §Appendix F (Break-Glass) |
| "I want to change AGH config" | `BLUEPRINT.md` §AdGuard Home Backend Pool (always change on dns-ams-01; let adguardhome-sync propagate) |
| "I need to debug the agent" | `../dns-ha-agent/AGENTS.md` |

## Invariants — never violate

1. **The HA DNS VIP is `10.253.53.53/32`**. Not a different `/32` in `10.253.53.0/24`. Not a `/24`. Mixed prefix lengths break Tailscale's HA failover guarantee.
2. **Every DNS edge node has `--accept-dns=false`** on its Tailscale config. The resolver must never depend on the service it is providing.
3. **Pi-hole binds to LAN interface only** (BIND mode, not wildcard). Wildcard mode steals port 53 from dnsdist and breaks the whole stack on that node.
4. **AGH listens only on `127.0.0.1:5353` and the Tailscale IP:5353**, never on `0.0.0.0:5353`. AGH on the LAN interface bypasses Pi-hole's metrics and conflicts with the design intent.
5. **No cross-region dnsdist backends**. Each edge's dnsdist pool contains only AGH instances in its own region. Cross-region failover happens at the Tailscale layer, not the dnsdist layer.
6. **AGH config changes happen on dns-ams-01 only**. `adguardhome-sync` propagates to replicas. Editing replicas directly creates drift.
7. **The dnsdist webserver is disabled by default**. If you need it for debugging, follow the credentials-from-file pattern documented in `BLUEPRINT.md` §Appendix B — never re-enable with placeholder passwords.
8. **Tailscale ACL is the security boundary for `:8091` (agent health) and `:3000` (AGH admin)**. Host firewall (ufw) is the secondary boundary for LAN-side restriction. Both must be in place.

## Things that look like good ideas but break the design

- **"Just add a public DNS fallback so it never breaks"** — Violates fail-closed. The whole point is filtering remains active; a fallback to 1.1.1.1 bypasses filtering during outages. Refuse to add this.
- **"Use Tailscale Premium regional routing for better geo-affinity"** — Fine if you're prepared to pay, but the design must work without it. Don't make the free-tier path harder to test.
- **"Bind the VIP on `eth0` instead of loopback so it's reachable from LAN"** — Tailscale clients reach the VIP via tailnet routing, not LAN. Binding on `eth0` creates ARP conflicts on the local subnet and exposes the HA layer to non-tailnet devices.
- **"Combine Pi-hole and AGH into one filtering layer"** — They serve different consumers (LAN devices vs tailnet clients) with different metric needs. Pi-hole stays, even though it overlaps with AGH on filtering.
- **"Run dns-ha-agent under k3s for consistency with other services"** — DNS must not depend on k3s availability. The agent runs as a plain Docker container on the edge nodes.

## Operational conventions

- All timestamps in logs and configs are **UTC**. The agent enforces this; for any shell scripts in this stack, prefer `date -u`.
- Edge node hostnames follow the `dns-<site>-<n>` convention (e.g. `dns-ams-01`, `dns-tor-02`). Set hostname before Tailscale enrolment so MagicDNS records are clean.
- `tag:dns-edge` is applied in Tailscale admin before the agent is deployed. The agent's route auto-approval depends on this tag.
- `tag:agh-admin` is applied to the Mac Studio (sync daemon) and admin devices, granting access to AGH admin port `:3000`. Do not extend this tag to ordinary tailnet clients.
- Verification: run `BLUEPRINT.md` §Appendix F (Monitoring Checklist) on a clean stack before declaring rollout phase complete.

## When in doubt

If a change feels like it might violate the fail-closed invariant or might let DNS resolve unfiltered traffic — **stop and ask**. The cost of a few minutes' delay is much smaller than the cost of silently bypassing filtering across the whole tailnet.
