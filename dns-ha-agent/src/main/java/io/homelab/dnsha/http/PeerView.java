package io.homelab.dnsha.http;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of the `peers[]` array in the /health response (BLUEPRINT.md
 * §6). Populated from HealthState's peer-observation set (PeerMonitor,
 * Phase 3) — empty when no peers are configured. {@code lastSeenTs} is
 * when THIS agent last successfully reached the peer, not to be confused
 * with the peer-unreachable-threshold timestamp used internally by
 * FsmReducer.isPartitionConditionMet (PeerObservation.
 * firstReportedUnreachableTs, not exposed in this schema).
 */
public record PeerView(
    @JsonProperty("name") String name,
    @JsonProperty("last_seen_ts") String lastSeenTs,
    @JsonProperty("reachable") boolean reachable,
    @JsonProperty("reported_state") String reportedState,
    @JsonProperty("reports_me_unreachable") boolean reportsMeUnreachable
) {
}
