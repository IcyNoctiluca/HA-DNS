package io.homelab.dnsha.state;

import java.time.Instant;

/**
 * This agent's current view of one peer, as last reported by PeerMonitor
 * (Phase 3). Not populated before Phase 3 — HealthState's peer-observation
 * set is empty in Phase 1/2, which is what makes the Decider partition
 * check an inert no-op until PeerMonitor exists.
 */
public record PeerObservation(
    String peerName,
    boolean reachable,
    FsmState reportedState,
    boolean reportsMeUnreachable,
    Instant firstReportedUnreachableTs
) {
}
