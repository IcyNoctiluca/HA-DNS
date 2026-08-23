# HA DNS — Progress Log

> Append-only execution log. Newest entries at the top. Each entry records what was done, what's next, and any decisions/anomalies worth surfacing. Phases are defined in [`PHASES.md`](PHASES.md).
>
> Format: one entry per session of work, dated. Use UTC. Keep entries terse — this is the "what happened" record, not the "why" (which lives in BLUEPRINT.md).

## Status

**Current phase**: Pre-Phase 1 (design complete, `dns-ha-agent` Phase 5 now proved live; Phase 6/7 remain)

**Update 2026-08-22**: Liam stood up real infra (Forgejo + Woodpecker at `git.battlenet.internal` / `ci.battlenet.internal`), and `dns-ha-agent`'s CI pipeline ran green end-to-end for the first time ever — Phase 5 (CI/CD + digest pinning) acceptance criteria are now all met, closing out a blocker that had stood since design. Two real bugs (Woodpecker's compile-time `${VAR}` substitution silently blanking the digest; per-step-container registry auth) were found and fixed live, not by further static review. See `dns-ha-agent/PROGRESS.md`'s 2026-08-22 entry for full detail and `dns-ha-agent/PHASES.md` for the closed-out checklist.

**Blockers now**: Phase 6 (Ansible role deploy) and Phase 7 (full v1.0 production-acceptance gate) both need a real *target* host, and Phase 7 additionally needs real Tailscale-connected hardware behaving like an edge node — this repo's own Phase 0/1 prerequisites (a public domain for the filter sentinel, real Pi 5 edge hardware) aren't in place yet either. These are infrastructure/hardware decisions for Liam, not something to resolve from static review or CI work alone.

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
