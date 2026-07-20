package io.homelab.dnsha.state;

import io.homelab.dnsha.config.Config;

import java.util.Objects;
import java.util.Set;

/**
 * Pure state-transition function. See BLUEPRINT.md §4 and §7.
 *
 * Phase 1/2 scope note: this reducer only tracks the local-probe-driven
 * transitions between STARTING / HEALTHY / UNHEALTHY. PARTITIONED entry is
 * peer-driven logic that PHASES.md assigns to the Decider update in
 * Phase 3 (see BLUEPRINT.md §7 Decider pseudocode, which is where the
 * "N peers report me unreachable" condition lives) — FsmReducer never
 * transitions a node INTO PARTITIONED itself. A node that was PARTITIONED
 * (once Phase 3 lands PeerMonitor + the peer-driven path that sets it)
 * returns to HEALTHY here as soon as local probes recover; it is Decider's
 * job, not FsmReducer's, to decide whether the peer condition still holds
 * and push it back to WITHDRAW / PARTITIONED on the next cycle.
 * SHUTTING_DOWN is set directly by the lifecycle/shutdown path (Phase 4),
 * never by this reducer — once in SHUTTING_DOWN, this reducer is sticky
 * and never leaves it based on probe results.
 */
public final class FsmReducer {

    private FsmReducer() {
    }

    public static FsmState reduce(FsmState previous, ProbeResult probe, Set<PeerObservation> peers, Config config) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(config, "config");

        if (previous == FsmState.SHUTTING_DOWN) {
            return FsmState.SHUTTING_DOWN;
        }

        int successThreshold = config.probe().successThreshold();
        int failThreshold = config.probe().failThreshold();

        return switch (previous) {
            case STARTING -> reachedSuccessThreshold(probe, successThreshold) ? FsmState.HEALTHY : FsmState.STARTING;
            case HEALTHY, PARTITIONED -> reachedFailThreshold(probe, failThreshold) ? FsmState.UNHEALTHY : FsmState.HEALTHY;
            case UNHEALTHY -> reachedSuccessThreshold(probe, successThreshold) ? FsmState.HEALTHY : FsmState.UNHEALTHY;
            case SHUTTING_DOWN -> FsmState.SHUTTING_DOWN; // unreachable; guarded above
        };
    }

    private static boolean reachedSuccessThreshold(ProbeResult probe, int successThreshold) {
        return probe.success() && probe.consecutiveSuccesses() >= successThreshold;
    }

    private static boolean reachedFailThreshold(ProbeResult probe, int failThreshold) {
        return !probe.success() && probe.consecutiveFailures() >= failThreshold;
    }
}
