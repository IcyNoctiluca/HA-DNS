package io.homelab.dnsha.http;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One entry of the `backends[]` array in the /health response (BLUEPRINT.md §6). */
public record BackendView(
    @JsonProperty("name") String name,
    @JsonProperty("address") String address,
    @JsonProperty("last_probe_ts") String lastProbeTs,
    @JsonProperty("resolve_ok") boolean resolveOk,
    @JsonProperty("filter_ok") boolean filterOk
) {
}
