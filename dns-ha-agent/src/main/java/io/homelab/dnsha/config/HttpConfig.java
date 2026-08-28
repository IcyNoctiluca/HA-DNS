package io.homelab.dnsha.config;

/**
 * HTTP server bind config (Phase 2 — no server is started in Phase 1).
 *
 * IMPORTANT: production MUST set bind_address to the node's Tailscale IP,
 * never 0.0.0.0 — see BLUEPRINT.md §5 and AGENTS.md invariant #8 in the
 * top-level AGENTS.md. ConfigLoader does not currently reject 0.0.0.0
 * because that check requires knowing "is this a production deploy",
 * which the agent cannot determine from the file alone; the infrastructure
 * consumer is the enforcement point when it renders and admits the config.
 */
public record HttpConfig(
    String bindAddress,
    int port
) {
    public HttpConfig {
        if (bindAddress == null || bindAddress.isBlank()) {
            bindAddress = "127.0.0.1";
        }
        if (port <= 0) {
            port = 8091;
        }
    }
}
