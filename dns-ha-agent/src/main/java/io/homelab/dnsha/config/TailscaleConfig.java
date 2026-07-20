package io.homelab.dnsha.config;

import io.homelab.dnsha.config.ConfigException;

import java.time.Duration;
import java.util.List;

/**
 * Tailscale interaction config. See BLUEPRINT.md §5 and AGENTS.md
 * invariant #1: all mutations funnel through TailscaleClient, constructed
 * from this config by App at startup.
 *
 * `mode` is "cli" (production) or "noop" (test-only; gated at startup by
 * DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE=true / --allow-noop-tailscale — see
 * App.java). ConfigLoader only validates that the string is one of the two
 * known values; the noop-gate enforcement itself is App's job because it
 * depends on env/flag state, not just the file.
 */
public record TailscaleConfig(
    String mode,
    String cliPath,
    List<String> managedRoutes,
    List<String> preservedRoutes,
    Duration commandTimeout
) {
    public TailscaleConfig {
        if (mode == null || mode.isBlank()) {
            mode = "cli";
        }
        if (cliPath == null || cliPath.isBlank()) {
            cliPath = "/usr/bin/tailscale";
        }
        if (preservedRoutes == null) {
            preservedRoutes = List.of();
        }
        if (commandTimeout == null) {
            commandTimeout = Duration.ofSeconds(10);
        }
        if (managedRoutes == null || managedRoutes.isEmpty()) {
            throw new ConfigException("tailscale.managed_routes must have at least one entry");
        }
    }
}
