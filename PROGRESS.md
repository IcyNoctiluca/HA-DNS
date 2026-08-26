# HA DNS — Progress Log

> Append-only execution log. Newest entries at the top. Each entry records what was done, what's next, and any decisions/anomalies worth surfacing. Phases are defined in [`PHASES.md`](PHASES.md).
>
> Format: one entry per session of work, dated. Use UTC. Keep entries terse — this is the "what happened" record, not the "why" (which lives in BLUEPRINT.md).

## Status

**Current phase**: Phase 1 in progress — the **backend half** (Unbound + AGH) is deployed in Amsterdam as of 2026-08-23; the edge half (dnsdist, VIP, `tag:dns-edge`, the agent) is not. `dns-ha-agent` Phase 5 proved live; its Phase 6/7 remain.

**Update 2026-08-22**: Liam stood up real infra (Forgejo + Woodpecker at `git.battlenet.internal` / `ci.battlenet.internal`), and `dns-ha-agent`'s CI pipeline ran green end-to-end for the first time ever — Phase 5 (CI/CD + digest pinning) acceptance criteria are now all met, closing out a blocker that had stood since design. Two real bugs (Woodpecker's compile-time `${VAR}` substitution silently blanking the digest; per-step-container registry auth) were found and fixed live, not by further static review. See `dns-ha-agent/PROGRESS.md`'s 2026-08-22 entry for full detail and `dns-ha-agent/PHASES.md` for the closed-out checklist.

**Update 2026-08-23**: Pushed Phase 6 (Ansible role) as far as it goes without a real target host: `ansible-lint` now passes clean at the `production` profile (7 real `fqcn` violations fixed), `--syntax-check` passes, and both Jinja templates were confirmed to render correctly against representative values. See `dns-ha-agent/PROGRESS.md`'s 2026-08-23 entry for what that did and didn't verify.

**Update 2026-08-23 (later)**: the backend half of Phase 1 is **deployed and proved on real hardware** in Amsterdam — both Unbound and AGH live on `dns-ams-01` (`battlenet-4`) and a newly-added second AMS backend `agh-ams-02` (`battlenet-3`). This partly supersedes the blocker note below: the "real Pi 5 edge hardware" prerequisite **is** in place and is now carrying the backend plane. The public domain for the filter sentinel is still outstanding (an interim `example.com` sentinel is in use). See the 2026-08-23 log entry.

**Blockers now**: `dns-ha-agent` Phase 6 (an actual Ansible run/deploy) and Phase 7 (full v1.0 production-acceptance gate) still need a real *target* host running the agent, and Phase 7 additionally needs an edge node with dnsdist and the VIP — which the backend-only deployment deliberately does not provide yet. This repo's Phase 0 prerequisite (a public domain for the filter sentinel) also remains open.

**Prior update, 2026-07-21 20:44 UTC**: the "unconfirmed fresh run" blocker is now cleared — 126/126 unit tests confirmed green (0 failures, 0 errors) at commit `e12bafd`, verified this session by reading the JUnit XML reports directly (no sandboxed session has ever had Docker access to produce this run itself; the run happened just before this session started and was cross-checked for freshness against HEAD). Treat Phases 0-4 as build-and-unit-test-verified; the Testcontainers/Docker-dependent integration-test suites remain unrun in any sandbox. This session additionally found and fixed a real bug in the Ansible role (missing `ansible.cfg` meant the role couldn't be found via its own documented invocation) plus two `ansible-lint` nits; see `dns-ha-agent/PROGRESS.md` 2026-07-21 (20:44 UTC) entry for detail.

---

## Log

### YYYY-MM-DD — Template entry

- **Phase**: (e.g. "Phase 1 — Bootstrap dns-ams-01")
- **Done**:
  - (one-line bullets)
- **Acceptance criteria touched**: (checklist items completed)
- **Next**:
  - (what comes next, in order)
- **Notes / anomalies**:
  - (anything surprising, decisions made, deviations from BLUEPRINT)

---

<!-- Append real entries below this line, newest first. Remove this comment when first entry is added. -->

### 2026-08-26 — Repository review and deployment-role hardening

- **Phase**: `dns-ha-agent` Phase 6 — static implementation hardening
- **Done**:
  - Confirmed the working Git/CI path is local Forgejo (`git.battlenet.internal`) plus Woodpecker (`ci.battlenet.internal`); GitHub remains a mirror only.
  - Corrected stale top-level and agent README status text: the agent is implemented and locally CI-tested, but production deployment remains gated on a real edge canary and Phase 0's controlled filter sentinel.
  - Added an idempotent `Ensure compose directory exists` task to the Ansible role. This closes the first-deploy failure when `dns_ha_agent_compose_dir` is overridden independently of `dns_ha_agent_config_dir`.
  - Corrected the agent convention that inaccurately banned all VIP queries: only the intentional local-stack health probe may query the VIP; no bootstrap or ordinary resolver dependency may do so.
- **Acceptance criteria touched**: none claimed complete — Phase 6 still requires an actual target-host run.
- **Next**:
  1. Configure a real controlled filter-sentinel domain and AGH block rule (top-level Phase 0).
  2. Run the digest-pinned Ansible canary against `dns-ams-01`, then execute Phase 6/7 acceptance checks.
- **Notes / anomalies**:
  - Local Gradle verification remains unavailable in this sandbox because the Gradle wrapper cannot resolve `services.gradle.org`; Woodpecker is already the proven, local build/test authority.

### 2026-08-23 — Amsterdam backend plane (Phase 1, steps 0-4 only)

- **Phase**: Phase 1 — Bootstrap first edge (`dns-ams-01`), **backend half only**
- **Done**:
  - Deployed Unbound + AdGuard Home on `dns-ams-01` (= `battlenet-4`) and on a **second Amsterdam backend**, `agh-ams-02` (= `battlenet-3`), giving the EU edge a local pool of two — the same shape this design already gives NA with `tor-02`.
  - Both Unbound and AGH live and proved on both nodes. AGH binds exactly `127.0.0.1:5353`, its node's Tailscale address `:5353`, and that address `:3000`; filtering proved by sentinel asymmetry (recursor answers, AGH returns `0.0.0.0`).
  - Deliberately stopped before dnsdist, the `10.253.53.53` VIP, `tag:dns-edge`, and the agent. Nothing on the network resolves through the new plane: Pi-hole still serves the LAN and Tailscale DNS is untouched, so this phase is invisible to every device.
- **Acceptance criteria touched**: the Unbound and local-resolution items of Phase 1, verified more strictly than the checklist asked (see the step 2a correction below). Everything gated on dnsdist, the VIP or `/health` remains open.
- **Next**:
  1. Phase 0: replace the interim `example.com` filter sentinel with a subdomain of a controlled domain carrying a published A record.
  2. Deploy `adguardhome-sync` on the Mac Studio; until then the two AMS instances drift independently.
  3. Close the missing third protection layer (below) before dnsdist `:1053` or agent `:8091` exist.
- **Notes / anomalies**:
  - **Orchestrator.** The backends run as **kubelet static pods** with `hostNetwork: true`, not plain Docker containers. `AGENTS.md`'s blanket "not under k3s" rule was this repo overstepping into deployment-layer territory; it is now restated as the dependency constraint it was actually protecting — no API server, scheduler, datastore, overlay or cluster DNS in the start-or-serve path — which static pods satisfy and a Deployment/DaemonSet would not. `dns-ha-agent` still cannot be one: it needs the host's `tailscaled` socket.
  - **PHASES.md step 2a was wrong** and is corrected in this commit. `dig +trace` cannot prove a resolver is recursing rather than forwarding — it queries the server under test only for the initial root-NS lookup, then walks the delegation from the client host, so it passes identically against a pure forwarder. The DNSSEC `ad` flag is the real evidence; `dnssec-failed.org` returning SERVFAIL proves it fails closed.
  - **Deviation from invariant 8.** UFW is not installed on the Amsterdam rack Pis, and adding a default-deny firewall to live k3s nodes was judged the larger risk in this phase. Two of three layers are in place (bind + Tailscale ACLs); nothing listens on the LAN NIC. Acceptable for AGH admin today, but it must be closed before `:8091`/`:1053` exist, since Tailscale ACLs do not see LAN traffic.
  - **Deviation from `AdGuardHome.yaml` as declared state.** The deployment seeds the config once and never re-asserts it, because AGH rewrites the file itself and `adguardhome-sync` will drive it; the bind/port/upstream contract is live-asserted instead.
  - **Live finding, port 5353.** `avahi-daemon` holds `0.0.0.0:5353` on every rack Pi. A specific-address bind to 5353 fails without `SO_REUSEADDR` and succeeds with it; dnsproxy — which AGH uses for all DNS listeners — sets `SO_REUSEADDR` and `SO_REUSEPORT` unconditionally, so the two coexist and avahi needs no change. If a future AGH release drops those options, this is the first thing to check.
  - **Live finding, unbound config.** Unbound *accumulates* `interface:` directives rather than replacing them, so a drop-in narrowing the bind to loopback leaves an inherited `0.0.0.0` in place and the resolver binds the LAN NIC too. Caught by a live bind assertion, not by review. The deployment now owns the whole `unbound.conf`.
  - Deployment-layer detail lives in the homelab repo: `docs/operations/tailnet-ha-dns.md`.

---

