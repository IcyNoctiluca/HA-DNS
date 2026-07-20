package io.homelab.dnsha.config;

/** A peer agent for split-brain detection (Phase 3). */
public record PeerConfig(
    String name,
    String healthUrl
) {
}
