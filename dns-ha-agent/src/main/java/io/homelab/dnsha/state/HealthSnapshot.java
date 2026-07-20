package io.homelab.dnsha.state;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable point-in-time view of HealthState, produced via the
 * atomic-ref-swap pattern (AGENTS.md invariant #6 — no synchronized on
 * read paths). This is the type that will back the /health JSON response
 * in Phase 2 (HealthResource); Phase 1 only consumes it internally
 * (Decider, Reconciler-adjacent decision logic).
 */
public record HealthSnapshot(
    FsmState fsmState,
    ProbeResult lastProbeResult,
    Set<PeerObservation> peerObservations,
    Instant startedAt
) {
    public static HealthSnapshot initial(Instant startedAt) {
        return new HealthSnapshot(FsmState.STARTING, ProbeResult.initial(), Set.of(), startedAt);
    }

    public HealthSnapshot withProbeResult(ProbeResult result, FsmState nextState) {
        return new HealthSnapshot(nextState, result, peerObservations, startedAt);
    }

    public HealthSnapshot withPeerObservations(Set<PeerObservation> observations, FsmState nextState) {
        return new HealthSnapshot(nextState, lastProbeResult, observations, startedAt);
    }
}
