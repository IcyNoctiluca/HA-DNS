package io.homelab.dnsha.http;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The `local_probe` object in the /health response (BLUEPRINT.md §6).
 *
 * Field types are pre-formatted (Instant -> ISO-8601 String) rather than
 * left as java.time types, so serialization does not depend on whatever
 * ObjectMapper configuration Jersey's Jackson provider happens to pick up
 * (no jackson-datatype-jsr310 module registration required for this
 * endpoint) — see HealthResource's javadoc for the fuller rationale.
 *
 * Interpretation note: `last_result` mirrors the BLUEPRINT.md §6 example
 * ("HEALTHY"), which uses FSM-state-shaped strings rather than
 * "success"/"failure". This implementation reports "HEALTHY" when the last
 * probe cycle's resolve+filter checks both passed and "UNHEALTHY"
 * otherwise — i.e. it reflects the last probe outcome itself, not the
 * agent's current (possibly-different, e.g. still-STARTING) FsmState. This
 * is a documented interpretation of an underspecified display field, not a
 * change to the FSM/schema contract itself.
 */
public record LocalProbeView(
    @JsonProperty("last_probe_ts") String lastProbeTs,
    @JsonProperty("last_result") String lastResult,
    @JsonProperty("consecutive_successes") int consecutiveSuccesses,
    @JsonProperty("consecutive_failures") int consecutiveFailures,
    @JsonProperty("resolve_check_ok") boolean resolveCheckOk,
    @JsonProperty("filter_check_ok") boolean filterCheckOk,
    @JsonProperty("last_probe_duration_ms") long lastProbeDurationMs
) {
}
