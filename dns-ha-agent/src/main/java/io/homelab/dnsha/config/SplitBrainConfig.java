package io.homelab.dnsha.config;

import java.time.Duration;

/**
 * Split-brain (partition) detection config (Phase 3). Parsed now for
 * schema stability; PeerMonitor and the Decider partition logic are not
 * wired in until Phase 3, so this has no observable effect in Phase 1/2
 * beyond being available to Decider's (currently always-empty-peer-set)
 * partition check.
 */
public record SplitBrainConfig(
    boolean enabled,
    Duration peerPollInterval,
    Duration peerPollTimeout,
    Duration peerUnreachableThreshold,
    int minPeerReportsToSelfWithdraw
) {
    public SplitBrainConfig {
        if (peerPollInterval == null) {
            peerPollInterval = Duration.ofSeconds(15);
        }
        if (peerPollTimeout == null) {
            peerPollTimeout = Duration.ofSeconds(5);
        }
        if (peerUnreachableThreshold == null) {
            peerUnreachableThreshold = Duration.ofSeconds(60);
        }
        if (minPeerReportsToSelfWithdraw <= 0) {
            minPeerReportsToSelfWithdraw = 2;
        }
    }

    public static SplitBrainConfig disabled() {
        return new SplitBrainConfig(false, null, null, null, 0);
    }
}
