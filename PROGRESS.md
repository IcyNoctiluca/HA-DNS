# HA DNS — Progress Log

> Append-only execution log. Newest entries at the top. Each entry records what was done, what's next, and any decisions/anomalies worth surfacing. Phases are defined in [`PHASES.md`](PHASES.md).
>
> Format: one entry per session of work, dated. Use UTC. Keep entries terse — this is the "what happened" record, not the "why" (which lives in BLUEPRINT.md).

## Status

**Current phase**: Pre-Phase 1 (design complete, awaiting dns-ha-agent v1.0)

**Blockers**: `dns-ha-agent` is unimplemented (in the "never built" sense) and unverified. See [`dns-ha-agent/PHASES.md`](dns-ha-agent/PHASES.md) and [`dns-ha-agent/PROGRESS.md`](dns-ha-agent/PROGRESS.md) — Phase 0, 1, and now 2 code has all been written (2026-07-20, across two sessions) but none of it has ever been compiled, tested, or built (no JDK 21/Gradle/Docker have been available in either session, and this session also confirmed the network allowlist blocks fetching a JDK/Gradle/Testcontainers-image dependencies directly as a workaround). It needs a real build before any of it can be trusted as "implemented."

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
