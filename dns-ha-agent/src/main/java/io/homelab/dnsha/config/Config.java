package io.homelab.dnsha.config;

import io.homelab.dnsha.config.ConfigException;

import java.util.List;

/**
 * Top-level immutable config, parsed once at startup from
 * /etc/dns-ha-agent/config.yaml by ConfigLoader. No hot reload — see
 * AGENTS.md invariant #10.
 */
public record Config(
    int version,
    NodeConfig node,
    ProbeConfig probe,
    BackendProbeConfig backendProbe,
    List<PeerConfig> peers,
    SplitBrainConfig splitBrain,
    TailscaleConfig tailscale,
    HttpConfig http,
    LoggingConfig logging,
    MetricsConfig metrics
) {
    public Config {
        if (backendProbe == null) {
            backendProbe = BackendProbeConfig.disabled();
        }
        if (peers == null) {
            peers = List.of();
        }
        if (splitBrain == null) {
            splitBrain = SplitBrainConfig.disabled();
        }
        if (http == null) {
            http = new HttpConfig(null, 0);
        }
        if (logging == null) {
            logging = new LoggingConfig(null, null);
        }
        if (metrics == null) {
            metrics = new MetricsConfig(true, null);
        }
        if (node == null) {
            throw new ConfigException("node section is required");
        }
        if (probe == null) {
            throw new ConfigException("probe section is required");
        }
        if (tailscale == null) {
            throw new ConfigException("tailscale section is required");
        }
    }
}
