package io.homelab.dnsha.state;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Immutable point-in-time view of HealthState, produced via the
 * atomic-ref-swap pattern (AGENTS.md invariant #6 — no synchronized on
 * read paths). This is the type that backs the /health JSON response
 * (Phase 2 — HealthResource).
 *
 * backendResults (Phase 2) is keyed by backend name and updated
 * independently of fsmState/lastProbeResult — see
 * HealthState.recordBackendProbeResult and BackendProbe's javadoc: backend
 * probe results never influence the FSM.
 */
public record HealthSnapshot(
    FsmState fsmState,
    ProbeResult lastProbeResult,
    Set<PeerObservation> peerObservations,
    Map<String, BackendProbeResult> backendResults,
    Instant startedAt
) {
    public static HealthSnapshot initial(Instant startedAt) {
        return new HealthSnapshot(FsmState.STARTING, ProbeResult.initial(), Set.of(), Map.of(), startedAt);
    }

    public HealthSnapshot withProbeResult(ProbeResult result, FsmState nextState) {
        return new HealthSnapshot(nextState, result, peerObservations, backendResults, startedAt);
    }

    public HealthSnapshot withPeerObservations(Set<PeerObservation> observations, FsmState nextState) {
        return new HealthSnapshot(nextState, lastProbeResult, observations, backendResults, startedAt);
    }

    public HealthSnapshot withBackendResult(BackendProbeResult result) {
        Map<String, BackendProbeResult> next = new java.util.LinkedHashMap<>(backendResults);
        next.put(result.name(), result);
        return new HealthSnapshot(fsmState, lastProbeResult, peerObservations, Map.copyOf(next), startedAt);
    }
}
