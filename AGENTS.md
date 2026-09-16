# Agent Conventions — HA DNS

Read this before changing the HA DNS producer or its portable contract. These
invariants and conventions protect against outage and filtering bypass.

## Repository ownership boundary

This is the producer repository. It owns fail-closed behavior,
`dns-ha-agent` source/tests/image build, and the versioned config, health and
route-plan contracts. Physical hosts, sites, the actual VIP, Tailscale policy,
credentials, supervisor, firewall, resolver deployment and live evidence belong
to the consuming infrastructure repository. See the boundary in
[`README.md`](README.md#producer-and-consumer-boundary).

The retired root deployment blueprint, phases, and progress log remain in Git
history. Do not recreate them or add new deployment detail here.

## Critical context

- This is **production DNS** for the homelab tailnet. iOS, in particular, loses DNS entirely when its first-listed resolver fails — this whole design exists because the v0 setup was unreliable for iOS clients.
- The design is **fail-closed**. If filtering can't be applied, DNS breaks rather than silently resolving unfiltered. Do not introduce fallback paths that violate this.
- The `dns-ha-agent` is the **sole authority for *local self-mutation* of each edge's Tailscale route advertisement** (i.e., the only thing that runs `tailscale set --advertise-routes=...` on that host). Do not write code or scripts that call `tailscale set --advertise-routes=...` outside the agent. A consumer-owned external fencer may operate at a different layer by disabling a node's *approved* route centrally through the Tailscale API; that is intentionally outside the agent process.

## Where to read first

| Task | Read |
|---|---|
| "Why does the design look like this?" | `README.md` §System contract |
| "What's the current state of a deployment?" | The consuming infrastructure repository |
| "I need to add a new edge node" | The consuming infrastructure repository plus this repo's published contract |
| "Something broke, how do I recover?" | The consuming infrastructure repository's runbook |
| "I want to change AGH config" | The consuming infrastructure repository |
| "I need to debug the agent" | `dns-ha-agent/AGENTS.md` |

## Git remotes — Forgejo is canonical, GitHub is the verified mirror

Forgejo is the system of record. Woodpecker observes this repository directly,
and Forgejo mirrors every committed ref to GitHub with `sync_on_commit`; GitHub
is a durable external copy, not a second writable source.

- **`origin`** — canonical Forgejo:
  `ssh://git@git.battlenet.l-p.dev:30022/liamp/HA-DNS.git`. It is both the
  fetch and push remote; `main` tracks `origin/main`.
- **`github`** — optional fetch-only reference to
  `git@github.com:IcyNoctiluca/HA-DNS.git`. Never push to it. The Forgejo
  repository's declared push mirror owns that write path and proves the two
  `main` refs agree after every reconciliation.

For an existing checkout that still points at GitHub, make Forgejo `origin`
before its next fetch or push:

```bash
git remote set-url origin ssh://git@git.battlenet.l-p.dev:30022/liamp/HA-DNS.git
git config --unset-all remote.origin.pushurl
git fetch origin
git branch --set-upstream-to=origin/main main
```

Use an isolated worktree and a topic branch for every change. Push the topic
branch to Forgejo and open the PR there; the declared rebase auto-merge policy
merges it only after Woodpecker succeeds. Do not open a GitHub PR for this
repository and do not repair a delayed GitHub mirror by pushing GitHub directly.

```bash
git worktree add /path/to/some/worktree -b codex/<topic> origin/main
cd /path/to/some/worktree
git push origin codex/<topic>
```

If GitHub replication is behind, stop changing the source repository and run
Battlenet's `configure-forgejo-push-mirrors.yml`; it verifies the Forgejo
generated deploy key, forces the initial sync when necessary, and refuses to
claim success until both default-branch commits are identical.

## Invariants — never violate

1. **Every edge in one deployment advertises the exact same `/32`.** The actual address is consumer-owned. Mixed prefixes break Tailscale failover.
2. **Every DNS edge node has `--accept-dns=false`** on its Tailscale config. The resolver must never depend on the service it is providing.
3. **Consumers prevent wildcard listener collisions.** The producer specifies required DNS behavior, not Pi-hole placement or interface names.
4. **Filtering backends and management listeners bind only to declared addresses**, never an unrestricted wildcard in production. The consumer supplies addresses and ports.
5. **A responsive but unfiltered backend is unhealthy.** Backend admission must test both resolution and the controlled filtering sentinel.
6. **Filtering-policy ownership and replication are consumer deployment concerns.** This repository defines only the required equivalent behavior across backends.
7. **Any DNS-proxy management surface is disabled by default.** If a consumer enables one for debugging, it must use credentials from a protected secret source and a non-routable or explicitly protected bind; placeholder credentials are forbidden.
8. **Management surfaces require three capabilities:** a non-wildcard bind, host-firewall defence in depth, and tailnet source policy. The consumer chooses the firewall technology, ports, tags and allowed principals.

## Things that look like good ideas but break the design

- **"Just add a public DNS fallback so it never breaks"** — Violates fail-closed. The whole point is filtering remains active; a fallback to 1.1.1.1 bypasses filtering during outages. Refuse to add this.
- **"Use Tailscale Premium regional routing for better geo-affinity"** — Fine if you're prepared to pay, but the design must work without it. Don't make the free-tier path harder to test.
- **"Bind the VIP on `eth0` instead of loopback so it's reachable from LAN"** — Tailscale clients reach the VIP via tailnet routing, not LAN. Binding on `eth0` creates ARP conflicts on the local subnet and exposes the HA layer to non-tailnet devices.
- **"Combine Pi-hole and AGH into one filtering layer"** — They serve different consumers (LAN devices vs tailnet clients) with different metric needs. Pi-hole stays, even though it overlaps with AGH on filtering.
- **"Run dns-ha-agent under k3s for consistency with other services"** — the *constraint* is real: DNS must not depend on the k3s control plane. But this was previously written as a ban on the orchestrator, which is the deployment layer's decision, not this repository's (see the homelab repo's "service-blueprint abstraction" rule — service docs stay silent on orchestrator choice). State the constraint, not the mechanism: **no component of this stack may require an API server, scheduler, datastore, overlay network, or cluster DNS in order to start or to keep serving.** Anything satisfying that is acceptable.

  `dns-ha-agent` additionally requires access to the host Tailscale CLI and
  LocalAPI socket. A static process, container or static pod can satisfy that
  capability if it remains independent of the control plane. The producer does
  not select the mechanism.

## Operational conventions

- All timestamps in logs and configs are **UTC**. The agent enforces this; for any shell scripts in this stack, prefer `date -u`.
- Consumers provide stable node identities before tailnet enrollment.
- Consumers admit route-approval and management-access policy before the agent.
- Producer verification covers schemas, state-machine behavior, image build and
  conformance tests. Consumer verification covers real routes, listeners,
  failure rehearsal, rollback and live evidence.

## When in doubt

If a change feels like it might violate the fail-closed invariant or might let DNS resolve unfiltered traffic — **stop and ask**. The cost of a few minutes' delay is much smaller than the cost of silently bypassing filtering across the whole tailnet.
