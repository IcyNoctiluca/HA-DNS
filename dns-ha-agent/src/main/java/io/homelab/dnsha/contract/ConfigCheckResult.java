package io.homelab.dnsha.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Machine-readable success response for --check-config. */
public record ConfigCheckResult(
    @JsonProperty("valid") boolean valid,
    @JsonProperty("config_schema_version") int configSchemaVersion,
    @JsonProperty("node") String node
) {
}
