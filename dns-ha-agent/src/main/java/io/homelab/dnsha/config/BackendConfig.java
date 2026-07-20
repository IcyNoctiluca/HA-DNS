package io.homelab.dnsha.config;

/** A single AGH backend to direct-probe. Used by BackendProbe (Phase 2). */
public record BackendConfig(
    String name,
    String address
) {
}
