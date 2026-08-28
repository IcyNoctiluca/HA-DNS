# Agent Conventions — HA DNS

Read this before making any changes to the HA DNS design or rollout. These are invariants and conventions specific to this stack; violating them creates real outage or filtering-bypass risk.

## Repository ownership boundary

This is the producer repository. It owns fail-closed behavior,
`dns-ha-agent` source/tests/image build, and the versioned config, health and
route-plan contracts. Physical hosts, sites, the actual VIP, Tailscale policy,
credentials, supervisor, firewall, resolver deployment and live evidence belong
to the consuming infrastructure repository. See
[`REPOSITORY_BOUNDARY.md`](REPOSITORY_BOUNDARY.md).

Historical Battlenet names and commands remain in `BLUEPRINT.md`, `PHASES.md`
and old progress entries while they are migrated. They are not current desired
state and must not be extended with new deployment detail.

## Critical context

- This is **production DNS** for the homelab tailnet. iOS, in particular, loses DNS entirely when its first-listed resolver fails — this whole design exists because the v0 setup was unreliable for iOS clients.
- The design is **fail-closed**. If filtering can't be applied, DNS breaks rather than silently resolving unfiltered. Do not introduce fallback paths that violate this.
- The `dns-ha-agent` is the **sole authority for *local self-mutation*** of each edge's Tailscale route advertisement (i.e., the only thing that runs `tailscale set --advertise-routes=...` on that host). Do not write code or scripts that call `tailscale set --advertise-routes=...` outside the agent. The n8n external fencer (see `BLUEPRINT.md` Appendix E) operates at a different layer: it disables a node's *approved* route centrally via the Tailscale API for fail-open remediation, and is intentionally outside the agent process.

## Where to read first

| Task | Read |
|---|---|
| "Why does the design look like this?" | `BLUEPRINT.md` §Context & Goals, §The Core Mechanism |
| "What's the current state of a deployment?" | The consuming infrastructure repository; `PROGRESS.md` is producer history only |
| "I need to add a new edge node" | The consuming infrastructure repository plus this repo's published contract |
| "Something broke, how do I recover?" | The consuming infrastructure repository's runbook |
| "I want to change AGH config" | The consuming infrastructure repository |
| "I need to debug the agent" | `dns-ha-agent/AGENTS.md` |

## Git remotes — GitHub is canonical, Forgejo is a mirror

GitHub is the system of record. Forgejo holds a full copy so the homelab isn't
responsible for hosting the canonical repo reliably.

- **`origin`** — canonical. Fetches from GitHub (`git@github.com:IcyNoctiluca/HA-DNS.git`).
  Has **two push URLs**: GitHub first, then Forgejo
  (`ssh://git@git.battlenet.internal:30022/liamp/HA-DNS.git`). A single
  `git push origin` writes to both. `main`'s upstream and `remote.pushDefault`
  are both `origin`, so a bare `git push` / `git pull` does the right thing.
- **`battlenet`** — a fetch-only convenience pointing at the Forgejo copy. Don't
  push to it directly; that would bypass GitHub and let the two diverge. Woodpecker
  CI (`ci.battlenet.internal`) still watches this Forgejo repo and builds from it —
  see `dns-ha-agent/BLUEPRINT.md` §12 — and it receives every commit via `origin`'s
  second push URL, so CI keeps triggering as normal.

Push directly to `main` as normal. There is no "PR only" rule for GitHub any more.

### The Forgejo push leg can fail on its own

`git push` writes to the push URLs in order: GitHub succeeds first, then Forgejo.
If Forgejo is unreachable, git exits non-zero and reports the Forgejo error **even
though the GitHub push already landed**. Don't re-run blindly assuming nothing
happened — check `git ls-remote origin main` (GitHub) vs the branch you pushed.

If the Forgejo leg fails because `git.battlenet.internal` won't resolve from a
sandboxed environment (a known intermittent issue with some setups' local DNS
resolver — `nslookup` may still succeed even when `ssh`/`git` don't), re-push the
Forgejo copy alone through a host that can reach it directly:

```bash
GIT_SSH_COMMAND='ssh -o ProxyCommand="ssh liampi@battlenet-1 -W %h:%p"' \
  git push ssh://git@git.battlenet.internal:30022/liamp/HA-DNS.git HEAD:main
```

### When the user wants a PR instead of a direct push

Use an isolated worktree + topic branch, not the shared working copy's `main`.
When the user asks for changes to be merged (commit in a new worktree, resolve
conflicts, create a PR, merge it), that is explicit authorization for the full
flow: commit the scoped changes, rebase on the target's current `main`, resolve
genuine conflicts, push the branch, create the PR, merge it, then clean up.

```bash
git worktree add /path/to/some/worktree -b claude/<topic> origin/main
cd /path/to/some/worktree
git push origin claude/<topic>   # topic branches also dual-push; harmless
gh pr create --title "..." --body "..."
gh pr merge --squash
```

Clean up afterward: `git worktree remove <path>`, delete the merged branch both
locally and on `origin`. Note the PR branch's dual-push also created it on Forgejo —
delete it there too if it lingers.

## Invariants — never violate

1. **Every edge in one deployment advertises the exact same `/32`.** The actual address is consumer-owned. Mixed prefixes break Tailscale failover.
2. **Every DNS edge node has `--accept-dns=false`** on its Tailscale config. The resolver must never depend on the service it is providing.
3. **Consumers prevent wildcard listener collisions.** The producer specifies required DNS behavior, not Pi-hole placement or interface names.
4. **Filtering backends and management listeners bind only to declared addresses**, never an unrestricted wildcard in production. The consumer supplies addresses and ports.
5. **A responsive but unfiltered backend is unhealthy.** Backend admission must test both resolution and the controlled filtering sentinel.
6. **Filtering-policy ownership and replication are consumer deployment concerns.** This repository defines only the required equivalent behavior across backends.
7. **The dnsdist webserver is disabled by default**. If you need it for debugging, follow the credentials-from-file pattern documented in `BLUEPRINT.md` §Appendix B — never re-enable with placeholder passwords.
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
