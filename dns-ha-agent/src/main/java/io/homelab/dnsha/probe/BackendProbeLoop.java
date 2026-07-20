package io.homelab.dnsha.probe;

import io.homelab.dnsha.config.BackendProbeConfig;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.state.HealthState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Runs one BackendProbe on its own virtual thread (PHASES.md Phase 2:
 * "One virtual thread per backend"), on backend_probe.interval cadence.
 *
 * Deliberately independent of ProbeLoop/Decider/Reconciler: this loop only
 * writes into HealthState.recordBackendProbeResult(...) and updates
 * per-backend metrics. It never calls Decider.decide(...) or
 * Reconciler.reconcile(...) — see BackendProbe's javadoc "IMPORTANT
 * non-effect" and PHASES.md Phase 2 acceptance criterion "BackendProbe does
 * not drive FSM".
 */
public final class BackendProbeLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(BackendProbeLoop.class);

    private final BackendProbe probe;
    private final HealthState state;
    private final BackendProbeConfig config;
    private final Metrics metrics;
    private final String node;

    private volatile boolean running = true;

    public BackendProbeLoop(BackendProbe probe, HealthState state, BackendProbeConfig config,
                             Metrics metrics, String node) {
        this.probe = probe;
        this.state = state;
        this.config = config;
        this.metrics = metrics;
        this.node = node;
    }

    @Override
    public void run() {
        log.info("backend_probe_loop_started",
            kv("node", node), kv("backend", probe.name()), kv("address", probe.address()),
            kv("interval", config.interval().toString()));
        while (running) {
            try {
                runOneCycle();
                Thread.sleep(config.interval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("backend_probe_loop_interrupted", kv("node", node), kv("backend", probe.name()));
                return;
            } catch (RuntimeException e) {
                log.error("backend_probe_loop_cycle_error",
                    kv("node", node), kv("backend", probe.name()), kv("error", e.toString()), e);
            }
        }
    }

    private void runOneCycle() {
        ProbeOutcome outcome = probe.probe();
        state.recordBackendProbeResult(probe.name(), probe.address(), outcome.resolveOk(), outcome.filterOk());

        metrics.backendResolveOk.labels(probe.name()).set(outcome.resolveOk() ? 1 : 0);
        metrics.backendFilterOk.labels(probe.name()).set(outcome.filterOk() ? 1 : 0);
        metrics.backendProbeTotal.labels(probe.name(), outcome.success() ? "success" : "failure").inc();

        if (outcome.success()) {
            log.debug("backend_probe_result",
                kv("node", node), kv("backend", probe.name()), kv("result", "success"),
                kv("duration_ms", outcome.duration().toMillis()));
        } else {
            log.warn("backend_probe_result",
                kv("node", node), kv("backend", probe.name()), kv("result", "failure"),
                kv("reason", outcome.failureReason()), kv("duration_ms", outcome.duration().toMillis()));
        }
    }

    public void stop() {
        running = false;
    }
}
