package io.homelab.dnsha.state;

import java.time.Duration;
import java.time.Instant;

/**
 * The outcome of a single local probe cycle, including the
 * consecutive-success/failure counters as of THIS result (i.e. already
 * incremented/reset by HealthState before FsmReducer sees it). FsmReducer
 * compares these counters against config thresholds — it does not own
 * counter state itself, which is what keeps it a pure function of
 * (previous FsmState, this ProbeResult, peers, config).
 */
public record ProbeResult(
    boolean success,
    boolean resolveOk,
    boolean filterOk,
    String failureReason,
    Duration duration,
    Instant timestamp,
    int consecutiveSuccesses,
    int consecutiveFailures
) {
    public static ProbeResult initial() {
        return new ProbeResult(false, false, false, "no probe yet", Duration.ZERO, null, 0, 0);
    }
}
