# dns-ha-agent — Progress Log

> Append-only execution log. Newest entries at the top. Each entry records what was done, what's next, and any decisions/anomalies worth surfacing. Phases are defined in [`PHASES.md`](PHASES.md).
>
> Format: one entry per session of work, dated. Use UTC. Keep entries terse — this is the "what happened" record, not the "why" (which lives in BLUEPRINT.md).

## Status

**Current phase**: Phase 1 (Core probe + single-node advertise) — code complete, unverified (see below). Phase 0 scaffolding also complete in the same session.

**Blockers**: none code-wise. This sandbox has no JDK 21, no Gradle, no Docker daemon, and no root/apt — so nothing here has actually been compiled, tested, or built. The next session (or a developer with a real toolchain) needs to run `./gradlew check` and `docker build .` for the first time and fix whatever that surfaces. See "Notes / anomalies" for specifics.

---

## Log

### YYYY-MM-DD — Template entry

- **Phase**: (e.g. "Phase 1 — Core probe + single-node advertise")
- **Done**:
  - (one-line bullets)
- **Acceptance criteria touched**: (checklist items completed)
- **Next**:
  - (what comes next, in order)
- **Notes / anomalies**:
  - (anything surprising, decisions made, deviations from BLUEPRINT)

---

### 2026-07-20 — Phase 0 + Phase 1 — initial implementation (dns-ha-agent)

- **Phase**: Phase 0 (Project scaffolding) and Phase 1 (Core probe + single-node advertise), `dns-ha-agent/`.
- **Done**:
  - Full Gradle project scaffold: `build.gradle.kts`, `settings.gradle.kts`, `gradlew`/`gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, multi-stage `Dockerfile` (per BLUEPRINT.md §11), `config/example.yaml`, `.forgejo/workflows/build.yml`.
  - Config layer: `Config` + 10 nested records (`NodeConfig`, `ProbeConfig`, `BlockExpectation`, `BackendProbeConfig`/`BackendConfig`, `PeerConfig`, `SplitBrainConfig`, `TailscaleConfig`, `HttpConfig`, `LoggingConfig`, `MetricsConfig`), `ConfigLoader` (Jackson YAML, snake_case, custom human-duration deserializer for "2s"/"60s"-style values), `ConfigException` → exit 78.
  - `DnsProbe` (dnsjava dual resolve+filter check) and `ProbeLoop` (virtual thread).
  - `HealthState` (atomic-ref-swap), `FsmState`, `ProbeResult`, `PeerObservation`, `HealthSnapshot`, `FsmReducer` (pure).
  - `Decider` (pure, matches BLUEPRINT.md §7 pseudocode including the partition/force-withdraw branches).
  - `TailscaleClient` interface, `CliTailscaleClient` (subprocess), `NoopTailscaleClient` (test-only, gated by `DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE`/`--allow-noop-tailscale`), `Reconciler` (fail-open detection + metrics).
  - `Metrics` (Prometheus simpleclient wrapper — introduced early because Reconciler needs it for `dns_ha_agent_fail_open`; no HTTP exposition yet, that's Phase 2).
  - `App` wiring (probe → FsmReducer(inside HealthState) → Decider → Reconciler), JSON logging via logback + logstash-logback-encoder, `LoggingBootstrap` for `logging.level`.
  - Unit tests: `ConfigLoaderTest`, `FsmReducerTest`, `DeciderTest`, `HealthStateTest` (including a concurrency test), `CliTailscaleClientTest` (fake shell-script CLI, never the real `tailscale` binary), `ReconcilerTest`, `DnsProbeTest` (mocked dnsjava `Resolver`). ~60 test methods total.
  - Integration test scaffolding: `EndToEndIT` (Testcontainers) + `FakeAghContainer` + a dependency-free Python UDP DNS responder (`src/test/resources/fake-agh/fake_agh.py`) with an HTTP control API, covering `healthyProbeAdvertises` and `filterBreakWithdraws`.
- **Acceptance criteria touched** (PHASES.md):
  - Phase 0: none can be marked done — see "Notes / anomalies", all four require a real JDK 21 + Gradle + Docker toolchain this sandbox doesn't have.
  - Phase 1: all listed unit/integration tests exist as real code (not stubs). None can be marked done — nothing has been compiled or executed. "No HTTP server, no metrics" criterion is satisfied by construction (no `http`/`HealthResource` package exists yet).
- **Next**:
  - **First priority, before any further phase work**: get this compiling and passing in an environment with JDK 21 + Gradle + a real network (`./gradlew check`), then `docker build .`. This has never been attempted — see below. Fix whatever surfaces; there will almost certainly be at least minor errors (Jackson record-binding edge cases, Prometheus/Testcontainers API details, etc.) that only a real compiler will catch.
  - Once compiling: run `EndToEndIT` against a real Docker daemon and fix the FakeAgh/agent-container wiring (UDP port exposure in `FakeAghContainer` is the highest-risk part — see below).
  - Then continue to Phase 2 (HTTP server + observability + BackendProbe).
- **Notes / anomalies**:
  - **No build/test verification was possible this session.** This sandbox has: no JDK 21 (only OpenJDK 11, which can't even parse this code — records and other Java 21 syntax aren't available), no Gradle install, no Docker (`docker` binary absent), and no root/apt (can't install any of the above). Network egress is allowlisted and blocks `services.gradle.org`, `repo.maven.apache.org`/`repo1.maven.org`, `raw.githubusercontent.com`, and `codeload.github.com` — so I could not download a JDK, Gradle, or Maven Central artifacts to attempt a manual build either. `github.com` (HTML), `objects.githubusercontent.com`, `pypi.org`, and `registry.npmjs.org` are reachable, but that wasn't enough to assemble a working JVM build toolchain. **Every file in this session was written by hand and reviewed by eye/grep (brace-balance check, YAML validation, `py_compile` on the Python test double) — none of it has been compiled.** Treat this as a first draft that needs a real build before being trusted.
  - **`gradle/wrapper/gradle-wrapper.jar` is missing.** I wrote `gradlew`, `gradlew.bat`, and `gradle-wrapper.properties` by hand (standard boilerplate), but the wrapper's actual jar is a compiled binary I have no way to produce or fetch here (see network restrictions above). Someone with normal internet access needs to run `gradle wrapper --gradle-version 8.7` once from the project root to generate it, or copy it from another Gradle 8.x project. Until then `./gradlew` will fail with a `ClassNotFoundException` for `org.gradle.wrapper.GradleWrapperMain`.
  - **OPEN QUESTION / flagged, not guessed past**: `CliTailscaleClient.currentlyAdvertised()` reads `tailscale debug prefs` (undocumented but widely-relied-upon subcommand) and parses its `AdvertiseRoutes` JSON field to determine what's currently advertised. This is the read side of the fail-open detection in `Reconciler` — the single most safety-critical metric per BLUEPRINT.md §6 ("the most dangerous failure class"). I have no real `tailscale` binary or `tailscaled` socket in this sandbox to verify actual `debug prefs` output against, so this is a documented assumption, not a verified fact. **Please verify against a real Tailscale install before Phase 7's production gate**, and if the field name/shape is wrong, fix `CliTailscaleClient` (it's the one interface implementation designed to absorb exactly this kind of correction — see AGENTS.md "the interface is the choke point").
  - **`EndToEndIT`/`FakeAghContainer` are unverified Testcontainers code**, written from documentation, not from a working run (no Docker here at all). The riskiest part is `FakeAghContainer`'s manual UDP port exposure (`GenericContainer#addExposedPorts` only handles TCP; I used `withCreateContainerCmdModifier` + raw docker-java `ExposedPort`/`Ports` to bind UDP/53, which needs to be validated against a real Docker daemon). Do not treat a green run of this class as proven without independently checking the UDP wiring actually works, and do not treat a *red* run as proof the agent is broken vs. proof the test harness has a bug — investigate before assuming either.
  - **API shape deviation from BLUEPRINT.md §9 "Key class signatures"**: `HealthState` exposes a single `recordProbeResult(resolveOk, filterOk, duration, failureReason)` instead of the two separate `recordProbeSuccess(Duration)`/`recordProbeFailure(String)` methods sketched in the blueprint (both convenience wrappers still exist and delegate to it, so nothing sketched is actually missing). This is a shape-only change — the FSM contract, thresholds, and transitions are unchanged — made because `ProbeLoop` always has both the resolve and filter booleans available from `DnsProbe` and deriving "success" in one place avoids duplicating that logic at each call site.
  - **Phase-boundary interpretation, not a guess**: `FsmReducer` only drives STARTING/HEALTHY/UNHEALTHY transitions from local probes; it never emits PARTITIONED. Per PHASES.md, Phase 3 explicitly owns "add PARTITIONED state to FSM" + "update Decider to apply partition logic" + PeerMonitor wiring — so `Decider` already implements the *full* partition/force-withdraw pseudocode from BLUEPRINT.md §7 now (unit-testable today via synthetic `HealthState`/`PeerObservation` fixtures), while `FsmReducer` stays local-probe-only until Phase 3 wires real peer observations in. Both classes' javadoc explains this split in detail. Flagging this interpretation explicitly in case a future session reads the blueprint differently.
  - Logging: initially wrote log statements with `key={}` baked into the message string (e.g. `"route_mutation node={} action={}..."`). Caught and fixed before finishing — `logstash-logback-encoder` does NOT parse that into separate JSON fields, which would have violated AGENTS.md invariant #9 ("structured fields") and silently broken the `EndToEndIT` log-content assertions. All log call sites now use `StructuredArguments.kv(...)` and match the exact `msg`/field shape shown in BLUEPRINT.md §8 (`probe_result`, `state_transition`, `route_mutation`).
  - Added a minimal best-effort JVM shutdown hook in `App` (stops the probe loop, attempts one `Reconciler.reconcile(WITHDRAW)`) even though the hardened graceful-SIGTERM handler (SHUTTING_DOWN state, 5s confirmation wait, HTTP drain) is explicitly Phase 4 scope. Justification is in the code comment: strictly better than doing nothing, and doesn't weaken the fail-closed startup-reconciliation story since a new instance always re-validates via STARTING regardless of whether this hook ran.
  - `git commit` was made for this session's work; per the scheduled-task instructions, nothing was pushed and no production/Tailscale/Docker commands were run against real infrastructure.

<!-- Append real entries below this line, newest first. Remove this comment when first entry is added. -->
