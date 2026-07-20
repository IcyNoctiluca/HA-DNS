package io.homelab.dnsha.config;

/** Prometheus metrics config (Phase 2 — no /metrics server in Phase 1). */
public record MetricsConfig(
    boolean enabled,
    String path
) {
    public MetricsConfig {
        if (path == null || path.isBlank()) {
            path = "/metrics";
        }
    }
}
