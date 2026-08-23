# Agent Conventions — HA DNS

Read this before making any changes to the HA DNS design or rollout. These are invariants and conventions specific to this stack; violating them creates real outage or filtering-bypass risk.

## Critical context

- This is **production DNS** for the homelab tailnet. iOS, in particular, loses DNS entirely when its first-listed resolver fails — this whole design exists because the v0 setup was unreliable for iOS clients.
- The design is **fail-closed**. If filtering can't be applied, DNS breaks rather than silently resolving unfiltered. Do not introduce fallback paths that violate this.
- The `dns-ha-agent` is the **sole authority for *local self-mutation*** of each edge's Tailscale route advertisement (i.e., the only thing that runs `tailscale set --advertise-routes=...` on that host). Do not write code or scripts that call `tailscale set --advertise-routes=...` outside the agent. The n8n external fencer (see `BLUEPRINT.md` Appendix E) operates at a different layer: it disables a node's *approved* route centrally via the Tailscale API for fail-open remediation, and is intentionally outside the agent process.

## Where to read first

| Task | Read |
|---|---|
| "Why does the design look like this?" | `BLUEPRINT.md` §Context & Goals, §The Core Mechanism |
| "What's the current state of rollout?" | `PROGRESS.md`, then `PHASES.md` |
| "I need to add a new edge node" | `BLUEPRINT.md` §Adding Future AZ Nodes |
| "Something broke, how do I recover?" | `BLUEPRINT.md` §Appendix F (Break-Glass) |
| "I want to change AGH config" | `BLUEPRINT.md` §AdGuard Home Backend Pool (always change on dns-ams-01; let adguardhome-sync propagate) |
| "I need to debug the agent" | `dns-ha-agent/AGENTS.md` |

## Invariants — never violate

1. **The HA DNS VIP is `10.253.53.53/32`**. Not a different `/32` in `10.253.53.0/24`. Not a `/24`. Mixed prefix lengths break Tailscale's HA failover guarantee.
2. **Every DNS edge node has `--accept-dns=false`** on its Tailscale config. The resolver must never depend on the service it is providing.
3. **Pi-hole binds to LAN interface only** (BIND mode, not wildcard). Wildcard mode steals port 53 from dnsdist and breaks the whole stack on that node.
4. **AGH listens only on `127.0.0.1:5353` and the Tailscale IP:5353**, never on `0.0.0.0:5353`. AGH on the LAN interface bypasses Pi-hole's metrics and conflicts with the design intent.
5. **No cross-region dnsdist backends**. Each edge's dnsdist pool contains only AGH instances in its own region. Cross-region failover happens at the Tailscale layer, not the dnsdist layer.
6. **AGH config changes happen on dns-ams-01 only**. `adguardhome-sync` propagates to replicas. Editing replicas directly creates drift.
7. **The dnsdist webserver is disabled by default**. If you need it for debugging, follow the credentials-from-file pattern documented in `BLUEPRINT.md` §Appendix B — never re-enable with placeholder passwords.
8. **Three layers protect `:8091` (agent health/metrics) and `:1053` (dnsdist diagnostic listener for n8n fencing)**: (1) bind to the node's Tailscale IP, not `0.0.0.0` (`network_mode: host` + wildcard bind would leak the endpoint on the LAN NIC, and Tailscale ACLs do not see LAN traffic); (2) host firewall (UFW) denies the port from non-Tailscale sources as defence-in-depth; (3) Tailscale ACL restricts which tailnet members can reach it (`tag:dns-edge` peers and `tag:dns-fencer` for n8n). All three must be in place. `:3000` (AGH admin) uses the same three-layer model, restricted to `tag:agh-admin`.

## Things that look like good ideas but break the design

- **"Just add a public DNS fallback so it never breaks"** — Violates fail-closed. The whole point is filtering remains active; a fallback to 1.1.1.1 bypasses filtering during outages. Refuse to add this.
- **"Use Tailscale Premium regional routing for better geo-affinity"** — Fine if you're prepared to pay, but the design must work without it. Don't make the free-tier path harder to test.
- **"Bind the VIP on `eth0` instead of loopback so it's reachable from LAN"** — Tailscale clients reach the VIP via tailnet routing, not LAN. Binding on `eth0` creates ARP conflicts on the local subnet and exposes the HA layer to non-tailnet devices.
- **"Combine Pi-hole and AGH into one filtering layer"** — They serve different consumers (LAN devices vs tailnet clients) with different metric needs. Pi-hole stays, even though it overlaps with AGH on filtering.
- **"Run dns-ha-agent under k3s for consistency with other services"** — the *constraint* is real: DNS must not depend on the k3s control plane. But this was previously written as a ban on the orchestrator, which is the deployment layer's decision, not this repository's (see the homelab repo's "service-blueprint abstraction" rule — service docs stay silent on orchestrator choice). State the constraint, not the mechanism: **no component of this stack may require an API server, scheduler, datastore, overlay network, or cluster DNS in order to start or to keep serving.** Anything satisfying that is acceptable.

  Amsterdam's AdGuard/Unbound backends satisfy it as **kubelet static pods** with `hostNetwork: true`: manifests read off each node's disk, needing no API server, scheduler, etcd, CNI or CoreDNS, and bypassing the pod network entirely. A Deployment or DaemonSet would *not* satisfy it, because scheduling requires the API server. `dns-ha-agent` has an additional constraint a static pod cannot meet — it must run `tailscale set` against the host's `tailscaled` socket — so it remains a plain Docker container on the edge nodes.

## Operational conventions

- All timestamps in logs and configs are **UTC**. The agent enforces this; for any shell scripts in this stack, prefer `date -u`.
- Edge node hostnames follow the `dns-<site>-<n>` convention (e.g. `dns-ams-01`, `dns-tor-02`). Set hostname before Tailscale enrolment so MagicDNS records are clean.
- `tag:dns-edge` is applied in Tailscale admin before the agent is deployed. The agent's route auto-approval depends on this tag.
- `tag:agh-admin` is applied to the Mac Studio (sync daemon) and admin devices, granting access to AGH admin port `:3000`. Do not extend this tag to ordinary tailnet clients.
- Verification: run `BLUEPRINT.md` §Appendix F (Monitoring Checklist) on a clean stack before declaring rollout phase complete.

## When in doubt

If a change feels like it might violate the fail-closed invariant or might let DNS resolve unfiltered traffic — **stop and ask**. The cost of a few minutes' delay is much smaller than the cost of silently bypassing filtering across the whole tailnet.
