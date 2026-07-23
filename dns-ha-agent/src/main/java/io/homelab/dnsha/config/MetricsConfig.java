package io.homelab.dnsha.config;

/**
 * Prometheus metrics config (Phase 2 — no /metrics server in Phase 1).
 *
 * {@code enabled} gates whether {@code /metrics} is registered at all — see
 * {@code io.homelab.dnsha.http.HealthServer#buildResourceConfig} (fixed
 * 2026-07-23: it was previously read here but never actually consulted by
 * HealthServer, so the endpoint was always served regardless of this value).
 *
 * {@code path} is currently required to be exactly {@code /metrics} —
 * {@code ConfigLoader.validate()} rejects any other value at load time,
 * because {@code MetricsResource}'s {@code @Path} is a compile-time-static
 * JAX-RS annotation with nothing currently wired to make it configurable.
 */
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
