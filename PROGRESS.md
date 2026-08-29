# HA DNS — Progress Log

> Append-only execution log. Newest entries are added above older dated
> entries. [`PHASES.md`](PHASES.md) defines the producer release sequence;
> agent acceptance criteria live in
> [`dns-ha-agent/PHASES.md`](dns-ha-agent/PHASES.md).
>
> Format: one entry per session of work, dated. Use UTC. Keep entries terse — this is the "what happened" record, not the "why" (which lives in BLUEPRINT.md).

## Status

**Current producer status 2026-08-29:** source revision
`6d19438bf99bbff38692b41bdc0a866e3c124951` passed Woodpecker pipeline 26.
The test, multi-architecture build-and-push, digest smoke-test, and Docker
service steps all succeeded. The published immutable image digest is
`sha256:61bdf3387050f2cacf1bd4f57ef91c527d5eaa5020a0eb26bb963f794dbf94f1`.

The repository now owns only the portable design, service implementation,
versioned contracts, tests, and producer release evidence. Earlier
host-specific log entries remain below as immutable history, not current
desired or live state. Infrastructure consumers own topology, credentials,
deployment adapters, rollout, and live acceptance evidence.

Producer-side Phase 7 still needs explicit tests for the force-withdraw
override and production `tailscale.mode` gate. Consumer admission and live
failure rehearsal are intentionally outside this repository.

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

### 2026-08-29 — Immutable producer release and documentation boundary

- **Phase**: producer release and repository housekeeping
- **Done**:
  - Reconciled GitHub canonical `main` and the Forgejo mirror at source
    revision `6d19438bf99bbff38692b41bdc0a866e3c124951`.
  - Verified Woodpecker pipeline 26 completed successfully across test,
    multi-architecture build-and-push, digest smoke-test, and Docker service.
  - Recorded the published digest
    `sha256:61bdf3387050f2cacf1bd4f57ef91c527d5eaa5020a0eb26bb963f794dbf94f1`.
  - Recast the active blueprint and release plan as portable producer
    documents, removed historical deployment-adapter examples from the active
    agent specification, and retained old deployment entries only as history.
- **Acceptance criteria touched**: producer CI, multi-architecture publication,
  digest smoke-test, contract validation, health/metrics, fail-open, lifecycle,
  route preservation, filtering failure/recovery, and asymmetric partition
  integration tests are green.
- **Next**:
  1. Add explicit producer tests for force-withdraw and production-mode gates.
  2. Let each consumer independently pin this source revision and digest and
     record its own admission, canary, failure-injection, and rollback evidence.
- **Notes / anomalies**: no production host, route, DNS setting, credential, or
  deployment pin changed during this housekeeping.

---

### 2026-08-28 — Producer/consumer boundary made executable

- **Phase**: repository-boundary migration
- **Done**:
  - Added versioned machine-readable config, health, metrics and route-plan
    contracts and embedded their versions plus the source revision in the OCI
    image.
  - Added offline config validation and deterministic route planning; the live
    reconciler consumes the same pure desired-route calculation.
  - Removed the producer-owned Ansible deployment tree and neutralized the
    example config. Battlenet now owns all concrete deployment values.
  - Built the shadow JAR and passed the complete unit suite on Java 21. Both
    offline commands emitted valid JSON for the neutral example.
  - Built a local producer image through Docker's legacy API path, verified
    all five contract labels/files, and exercised both offline commands with
    networking disabled. The build now falls back to the base OS architecture
    when BuildKit does not provide `TARGETARCH`.
  - Updated Testcontainers to 1.21.4 for current Docker API compatibility and
    made integration Dockerfile paths explicit. Local integration attempts
    reached the test task but its Gradle daemon repeatedly disappeared; no
    integration result is claimed and Woodpecker remains the release gate.
- **Acceptance criteria touched**: producer contract implementation complete;
  immutable release publication still open.
- **Next**:
  1. Commit and push HA-DNS, then require a green Woodpecker unit, integration,
     multi-architecture build and digest smoke run.
  2. Admit that revision and digest in Battlenet as a separate change.
- **Notes / anomalies**: no production host, route, tag, listener or deployment
  service was changed.

---

### 2026-08-28 — Git remote flip: GitHub canonical, Forgejo mirrored

- **Phase**: Infrastructure / repo hosting (no DNS-design change)
- **Done**:
  - Reversed the remote roles. GitHub (`IcyNoctiluca/HA-DNS`) is now the
    canonical remote; the homelab Forgejo is a mirror, so the homelab is no
    longer the system of record for the source.
  - Seeded GitHub `main` from the true current state (`63e8283`) with a
    one-time force-push, replacing GitHub's stale divergent history (was
    `3387724`, ~5 commits behind on content with its own squash-merge PR
    trail). Histories are unified from here on.
  - `origin` now fetches from GitHub and has two push URLs (GitHub first, then
    Forgejo); one `git push origin` writes to both. `remote.pushDefault` and
    `branch.main.remote` set to `origin`. `battlenet` kept as a fetch-only
    convenience.
  - Woodpecker CI is unchanged — it still watches the Forgejo copy, which
    receives every commit via `origin`'s second push URL. `.woodpecker.yml`,
    the Forgejo container registry, and the dns-ha-agent CI docs were not
    touched.
  - Updated `README.md` and `AGENTS.md` §"Git remotes" for the new model:
    direct pushes to `main` are fine (no more PR-only rule for GitHub); the
    Forgejo push leg can fail independently after GitHub already landed; the
    `git.battlenet.internal` resolver-tunnel workaround now applies to
    re-pushing the Forgejo leg alone.
- **Acceptance criteria touched**: none (infrastructure only).
- **Next**:
  - On the next push, confirm both remotes land at the same SHA
    (`git ls-remote origin main` vs `git ls-remote battlenet main`).
- **Notes / anomalies**:
  - The force-push was the only destructive step and was operator-run;
    the classifier blocks force-push from the agent.
  - Dual-push means topic branches are also created on Forgejo; delete them
    there too after a PR merge if they linger.

---

### 2026-08-27 — Controlled filter sentinel and sync deployment prepared

- **Phase**: Phase 0 / Phase 1 backend readiness
- **Done**:
  - The operator published `dns.l-p.dev` as a DNS-only public A record for
    `192.0.2.53` and added the exact block rule to the primary
    (`agh-ams-01` / `battlenet-4`).
  - Live backend probes proved the intended asymmetry on the primary: local
    Unbound returns `192.0.2.53`; AGH returns `0.0.0.0`. The secondary's AGH
    still returned `192.0.2.53`, correctly exposing the current configuration
    drift rather than falsely treating the sentinel as complete.
  - A checksum-pinned native macOS `adguardhome-sync` LaunchAgent and a
    post-deploy proof were prepared in the Battlenet deployment repository
    (local commit `8abbb63`). The service has no API listener and uses
    MagicDNS only to reach the two tailnet-bound AGH admin endpoints.
  - Read-only Studio probes confirmed ARM64/launchd readiness and both AGH
    admin endpoints return unauthenticated `401`; the transport path is ready.
  - Read-only firewall inspection confirmed UFW/firewalld are absent and the
    two backends already carry k3s-managed nftables/iptables rules, so a
    generic UFW installation was intentionally not attempted.
- **Acceptance criteria touched**: Phase 0 public-record and primary-rule
  prerequisites proved. Replica propagation and shared response-shape proof
  remain open.
- **Next**:
  1. Add `vault_adguard_home_sync_admin_password` to the encrypted Studio
     Ansible vault from the password manager, then deploy and prove sync.
  2. Design and rehearse a narrow persistent firewall mechanism compatible
     with k3s before introducing dnsdist `:1053` or agent `:8091`.
  3. Apply the agent canary only after both gates pass.
- **Notes / anomalies**:
  - The bcrypt hash that seeded AGH is deliberately insufficient for the AGH
    API; it cannot be reversed or re-used by the sync service.
  - No replica was edited directly, preserving the primary-only configuration
    ownership boundary.

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
