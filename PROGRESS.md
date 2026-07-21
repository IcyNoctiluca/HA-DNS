# HA DNS — Progress Log

> Append-only execution log. Newest entries at the top. Each entry records what was done, what's next, and any decisions/anomalies worth surfacing. Phases are defined in [`PHASES.md`](PHASES.md).
>
> Format: one entry per session of work, dated. Use UTC. Keep entries terse — this is the "what happened" record, not the "why" (which lives in BLUEPRINT.md).

## Status

**Current phase**: Pre-Phase 1 (design complete, awaiting dns-ha-agent v1.0)

**Blockers**: `dns-ha-agent` is unimplemented (in the "never built" sense) and unverified. See [`dns-ha-agent/PHASES.md`](dns-ha-agent/PHASES.md) and [`dns-ha-agent/PROGRESS.md`](dns-ha-agent/PROGRESS.md) — Phases 0 through 3 code has all been written (2026-07-20 and 2026-07-21, across three sessions) but none of it has ever been compiled, tested, or built. No session has had Gradle/Maven Central/Docker access; the 2026-07-21 session found a way to get a real JDK 21 *runtime* into the sandbox via `pip install jdk4py`, but that runtime has no `javac` (its `jdk.compiler` module is absent), so it does not unblock a real build. It needs a real build before any of it can be trusted as "implemented."

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
