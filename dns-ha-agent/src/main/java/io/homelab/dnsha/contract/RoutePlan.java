package io.homelab.dnsha.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.decide.Decision;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Side-effect-free view of the complete route sets the agent will reconcile.
 * Deployment code consumes this before starting the agent so it can prove
 * that environment-owned routes survive both ADVERTISE and WITHDRAW.
 */
public record RoutePlan(
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("config_schema_version") int configSchemaVersion,
    @JsonProperty("node") String node,
    @JsonProperty("managed_routes") List<String> managedRoutes,
    @JsonProperty("preserved_routes") List<String> preservedRoutes,
    @JsonProperty("advertise_routes") List<String> advertiseRoutes,
    @JsonProperty("withdraw_routes") List<String> withdrawRoutes
) {
    public static final int SCHEMA_VERSION = 1;

    public static RoutePlan from(Config config) {
        return new RoutePlan(
            SCHEMA_VERSION,
            config.version(),
            config.node().name(),
            sorted(config.tailscale().managedRoutes()),
            sorted(config.tailscale().preservedRoutes()),
            sorted(desiredRoutes(config, Decision.ADVERTISE)),
            sorted(desiredRoutes(config, Decision.WITHDRAW)));
    }

    /** The same desired-set calculation used by the live reconciler. */
    public static Set<String> desiredRoutes(Config config, Decision decision) {
        Set<String> preserved = new LinkedHashSet<>(config.tailscale().preservedRoutes());
        if (decision == Decision.WITHDRAW) {
            return Set.copyOf(preserved);
        }

        Set<String> advertised = new LinkedHashSet<>(config.tailscale().managedRoutes());
        advertised.addAll(preserved);
        return Set.copyOf(advertised);
    }

    private static List<String> sorted(Iterable<String> routes) {
        List<String> result = new ArrayList<>();
        routes.forEach(result::add);
        result.sort(String::compareTo);
        return List.copyOf(result);
    }
}
