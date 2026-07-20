package io.homelab.dnsha.state;

import java.time.Instant;

/**
 * This agent's last direct-probe result for one configured AGH backend
 * (Phase 2 — BackendProbe). Purely diagnostic: never consulted by
 * FsmReducer or Decider (see BackendProbe's javadoc "IMPORTANT non-effect").
 * Backs the `backends[]` array in the /health JSON response
 * (BLUEPRINT.md §6) and the per-backend gauges in /metrics.
 */
public record BackendProbeResult(
    String name,
    String address,
    Instant lastProbeTs,
    boolean resolveOk,
    boolean filterOk
) {
}
