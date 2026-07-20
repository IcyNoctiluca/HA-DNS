package io.homelab.dnsha.http;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of the `peers[]` array in the /health response (BLUEPRINT.md
 * §6). Always empty before Phase 3 — PeerMonitor does not exist yet, so
 * HealthState's peer-observation set is never populated in Phase 1/2 (see
 * Decider/FsmReducer javadocs for the fuller phase-boundary note). This
 * type is defined now so the /health schema is stable and Phase 3 only has
 * to populate it, not invent it.
 */
public record PeerView(
    @JsonProperty("name") String name,
    @JsonProperty("last_seen_ts") String lastSeenTs,
    @JsonProperty("reachable") boolean reachable,
    @JsonProperty("reported_state") String reportedState,
    @JsonProperty("reports_me_unreachable") boolean reportsMeUnreachable
) {
}
