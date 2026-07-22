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
 *
 * `socketPath` (optional, `tailscale.socket_path` in YAML): explicit path to
 * the tailscaled LocalAPI unix socket, passed to the CLI as `--socket=...`.
 * Left null/blank, CliTailscaleClient omits the flag and relies on the
 * `tailscale` binary's own compiled-in default socket path. This exists
 * because the container's `TS_SOCKET` env var (see ansible
 * docker-compose.yml.j2) does NOT configure the `tailscale` CLI client
 * itself — only `tailscaled`/the official image's `containerboot` entrypoint
 * read it (confirmed against tailscale/tailscale upstream issue #14406, "FR:
 * Make TS_SOCKET work with the `tailscale` CLI" — still open upstream as of
 * this writing). This deployment doesn't run containerboot at all: it bind-
 * mounts the host's existing tailscaled.sock straight into the container and
 * subprocesses the CLI against it, so TS_SOCKET was always inert here, not
 * just inapplicable-but-harmless. Without this field, a node whose
 * tailscaled.sock lives anywhere other than the CLI's hardcoded default
 * (`/var/run/tailscale/tailscaled.sock` on Linux) would silently fail every
 * `tailscale set`/`debug prefs` call despite `dns_ha_agent_tailscale_socket`
 * being documented (ansible defaults/main.yml) as a per-node override point.
 */
public record TailscaleConfig(
    String mode,
    String cliPath,
    String socketPath,
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
        if (socketPath != null && socketPath.isBlank()) {
            socketPath = null;
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
