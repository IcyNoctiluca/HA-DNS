package io.homelab.dnsha.probe;

import java.time.Duration;

/** Result of one DnsProbe.probe() cycle. See BLUEPRINT.md §3 DnsProbe. */
public record ProbeOutcome(
    boolean resolveOk,
    boolean filterOk,
    String failureReason,
    Duration duration
) {
    public boolean success() {
        return resolveOk && filterOk;
    }
}
