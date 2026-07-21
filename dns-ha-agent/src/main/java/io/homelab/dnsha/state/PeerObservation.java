package io.homelab.dnsha.state;

import java.time.Instant;

/**
 * This agent's current view of one peer, as last reported by PeerMonitor
 * (Phase 3 — see io.homelab.dnsha.peer.PeerMonitor).
 *
 * Two independent signals are tracked, per BLUEPRINT.md §6/§7:
 *  - {@code reachable} / {@code lastSeenTs}: whether THIS agent could reach
 *    the peer's /health as of its most recent poll, and when that last
 *    succeeded. Feeds this node's own {@code unreachable_peers} list.
 *  - {@code reportsMeUnreachable} / {@code firstReportedUnreachableTs}:
 *    whether the PEER's own /health.unreachable_peers list contained THIS
 *    node's name as of the last successful poll of that peer, and when that
 *    condition first became true (sticky until the peer stops reporting us
 *    unreachable). This is the asymmetric-inbound signal
 *    FsmReducer.isPartitionConditionMet consumes.
 *
 * On a failed poll (peer unreachable), {@code reportedState},
 * {@code reportsMeUnreachable}, and {@code firstReportedUnreachableTs} are
 * carried forward unchanged by HealthState.recordPeerPollResult — a single
 * missed poll is not new information about what the peer last said.
 */
public record PeerObservation(
    String peerName,
    boolean reachable,
    FsmState reportedState,
    boolean reportsMeUnreachable,
    Instant firstReportedUnreachableTs,
    Instant lastSeenTs
) {
}
