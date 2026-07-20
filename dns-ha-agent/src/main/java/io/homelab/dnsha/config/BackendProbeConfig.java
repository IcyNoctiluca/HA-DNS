package io.homelab.dnsha.config;

import java.time.Duration;
import java.util.List;

/**
 * Per-backend filter probe config (Phase 2 — BackendProbe is not
 * implemented until then; this record is parsed and validated now so the
 * schema is stable, but `enabled` has no effect before Phase 2 lands).
 */
public record BackendProbeConfig(
    boolean enabled,
    Duration interval,
    Duration queryTimeout,
    List<BackendConfig> backends
) {
    public BackendProbeConfig {
        if (interval == null) {
            interval = Duration.ofSeconds(30);
        }
        if (queryTimeout == null) {
            queryTimeout = Duration.ofSeconds(2);
        }
        if (backends == null) {
            backends = List.of();
        }
    }

    public static BackendProbeConfig disabled() {
        return new BackendProbeConfig(false, null, null, null);
    }
}
