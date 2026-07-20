package io.homelab.dnsha.config;

/**
 * This node's identity. Used in logs, metrics, /health output.
 * See BLUEPRINT.md §5.
 */
public record NodeConfig(
    String name,
    String region,
    String vip
) {
}
