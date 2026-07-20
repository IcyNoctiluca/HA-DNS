package io.homelab.dnsha.config;

import io.homelab.dnsha.config.ConfigException;

import java.time.Duration;
import java.util.List;

/**
 * Local DNS probing config — the dual resolve + filter check against the
 * VIP served by the local dnsdist. See BLUEPRINT.md §5.
 */
public record ProbeConfig(
    String target,
    List<String> resolveNames,
    String resolveType,
    String blockName,
    BlockExpectation blockExpectation,
    Duration interval,
    Duration queryTimeout,
    int failThreshold,
    int successThreshold
) {
    public ProbeConfig {
        if (resolveType == null || resolveType.isBlank()) {
            resolveType = "A";
        }
        if (blockExpectation == null) {
            blockExpectation = new BlockExpectation(null, null, null);
        }
        if (interval == null) {
            interval = Duration.ofSeconds(2);
        }
        if (queryTimeout == null) {
            queryTimeout = Duration.ofSeconds(2);
        }
        if (failThreshold <= 0) {
            failThreshold = 2;
        }
        if (successThreshold <= 0) {
            successThreshold = 3;
        }
        if (target == null || target.isBlank()) {
            throw new ConfigException("probe.target is required");
        }
    }
}
