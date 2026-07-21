package io.homelab.dnsha.probe;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.decide.Decider;
import io.homelab.dnsha.decide.Decision;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.state.FsmState;
import io.homelab.dnsha.state.HealthState;
import io.homelab.dnsha.tailscale.Reconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Runs in a virtual thread (AGENTS.md invariant #7 — no platform-thread
 * pools for I/O). Wires probe -> HealthState (which drives FsmReducer
 * internally) -> Decider -> Reconciler, on config.probe().interval()
 * cadence. See BLUEPRINT.md §3, §8.
 *
 * Log lines here follow the exact "msg" + flat-field shape shown in
 * BLUEPRINT.md §8 Logging (probe_result, state_transition) — every field
 * must be a separate StructuredArguments.kv(...) so logstash-logback-
 * encoder emits real JSON keys, not a single flattened message string.
 */
public final class ProbeLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ProbeLoop.class);

    /**
     * PHASES.md Phase 4 acceptance criterion: "Integration test: inject
     * Thread.sleep(Long.MAX_VALUE) into probe loop in a test build." This
     * is that hook — gated behind an env var that must be explicitly set
     * (same opt-in pattern as DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE), never
     * read anywhere except here, and read once at class-load like every
     * other startup-only config in this codebase. It exists purely so
     * LifecycleIT can exercise WatchdogThread's hang-detection path against
     * a real running agent process without needing to modify production
     * code paths at runtime.
     */
    static final boolean TEST_HANG_PROBE_LOOP =
        "true".equalsIgnoreCase(System.getenv("DNS_HA_AGENT_TEST_HANG_PROBE_LOOP"));

    private final DnsProbe probe;
    private final HealthState state;
    private final Config config;
    private final Reconciler reconciler;
    private final Metrics metrics;

    /** Last wall-clock time a probe cycle completed; read by WatchdogThread (Phase 4). */
    private final AtomicReference<Instant> lastCycleCompletedAt = new AtomicReference<>(Instant.now());

    private volatile boolean running = true;

    public ProbeLoop(DnsProbe probe, HealthState state, Config config, Reconciler reconciler, Metrics metrics) {
        this.probe = probe;
        this.state = state;
        this.config = config;
        this.reconciler = reconciler;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        log.info("probe_loop_started", kv("node", config.node().name()), kv("interval", config.probe().interval().toString()));
        if (TEST_HANG_PROBE_LOOP) {
            log.warn("probe_loop_test_hang_injected", kv("node", config.node().name()),
                kv("note", "DNS_HA_AGENT_TEST_HANG_PROBE_LOOP=true; lastCycleCompletedAt will never advance again"));
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        while (running) {
            try {
                runOneCycle();
                Thread.sleep(config.probe().interval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("probe_loop_interrupted", kv("node", config.node().name()));
                return;
            } catch (RuntimeException e) {
                // A single bad cycle must not kill the loop silently and
                // leave the agent stuck advertising/withdrawing on stale
                // info — log loudly and keep going; WatchdogThread (Phase 4)
                // is the backstop for a truly hung loop.
                log.error("probe_loop_cycle_error", kv("node", config.node().name()), kv("error", e.toString()), e);
            }
        }
    }

    private void runOneCycle() {
        FsmState before = state.currentState();

        ProbeOutcome outcome = probe.probe();
        state.recordProbeResult(outcome.resolveOk(), outcome.filterOk(), outcome.duration(), outcome.failureReason());

        metrics.probeTotal.labels(outcome.success() ? "success" : "failure").inc();
        metrics.probeDurationSeconds.observe(outcome.duration().toNanos() / 1_000_000_000.0);

        var probeResult = state.snapshot().lastProbeResult();
        if (outcome.success()) {
            log.debug("probe_result",
                kv("node", config.node().name()),
                kv("result", "success"),
                kv("duration_ms", outcome.duration().toMillis()),
                kv("consecutive_successes", probeResult.consecutiveSuccesses()));
        } else {
            log.warn("probe_result",
                kv("node", config.node().name()),
                kv("result", "failure"),
                kv("reason", outcome.failureReason()),
                kv("duration_ms", outcome.duration().toMillis()),
                kv("consecutive_failures", probeResult.consecutiveFailures()));
        }

        FsmState after = state.currentState();
        if (after != before) {
            boolean regressed = after == FsmState.UNHEALTHY || after == FsmState.PARTITIONED
                || after == FsmState.SHUTTING_DOWN;
            String reason = outcome.success() ? "probe_success" : "probe_failures";
            if (regressed) {
                log.warn("state_transition",
                    kv("node", config.node().name()), kv("from", before), kv("to", after), kv("reason", reason));
            } else {
                log.info("state_transition",
                    kv("node", config.node().name()), kv("from", before), kv("to", after), kv("reason", reason));
            }
        }

        updateStateGauge(after);

        Decision decision = Decider.decide(state, config);
        reconciler.reconcile(decision);

        lastCycleCompletedAt.set(Instant.now());
    }

    /**
     * dns_ha_agent_state is a gauge with one time series per label value
     * (BLUEPRINT.md §6) — set the current state's series to 1 and every
     * other tracked series to 0 so exactly one is ever "hot" at a time.
     * SHUTTING_DOWN has no series of its own in the §6 spec; when active,
     * all four tracked series read 0.
     */
    private void updateStateGauge(FsmState current) {
        for (FsmState candidate : new FsmState[] {
            FsmState.STARTING, FsmState.HEALTHY, FsmState.UNHEALTHY, FsmState.PARTITIONED
        }) {
            metrics.state.labels(candidate.name()).set(candidate == current ? 1 : 0);
        }
    }

    public Instant lastCycleCompletedAt() {
        return lastCycleCompletedAt.get();
    }

    public void stop() {
        running = false;
    }
}
