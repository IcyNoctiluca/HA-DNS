package io.homelab.dnsha.http;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The full /health response body, matching BLUEPRINT.md §6 exactly
 * (including field names/order). Built fresh per request by HealthResource
 * — this type carries no behaviour, only data.
 */
public record HealthResponse(
    @JsonProperty("version") int version,
    @JsonProperty("node") String node,
    @JsonProperty("region") String region,
    @JsonProperty("state") String state,
    @JsonProperty("vip_advertised") boolean vipAdvertised,
    @JsonProperty("desired_vip_advertised") boolean desiredVipAdvertised,
    @JsonProperty("fail_open") boolean failOpen,
    @JsonProperty("force_withdraw_active") boolean forceWithdrawActive,
    @JsonProperty("local_probe") LocalProbeView localProbe,
    @JsonProperty("backends") List<BackendView> backends,
    @JsonProperty("peers") List<PeerView> peers,
    @JsonProperty("unreachable_peers") List<String> unreachablePeers,
    @JsonProperty("uptime_seconds") long uptimeSeconds
) {
}
