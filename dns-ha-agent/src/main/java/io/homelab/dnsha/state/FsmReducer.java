package io.homelab.dnsha.state;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.SplitBrainConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Pure state-transition function. See BLUEPRINT.md §4 and §7.
 *
 * Local-probe-driven transitions (STARTING/HEALTHY/UNHEALTHY) are unchanged
 * since Phase 1. As of Phase 3, the HEALTHY/PARTITIONED branch also
 * consults {@code peers} + {@code config.splitBrain()} via
 * {@link #isPartitionConditionMet(Set, Config)} on every call, so a node
 * already at HEALTHY moves to PARTITIONED the moment enough peers report it
 * unreachable past {@code peer_unreachable_threshold}, and a PARTITIONED
 * node returns to HEALTHY once that condition clears AND local probes are
 * still passing (BLUEPRINT.md §4 transition table). This runs on every
 * HealthState mutation that can affect either input — both
 * recordProbeResult and recordPeerPollResult/recordPeerObservation/
 * recordPeerUnreachable pass the full current peer set through here (see
 * HealthState), so the label is re-evaluated promptly regardless of which
 * one changed.
 *
 * {@link #isPartitionConditionMet(Set, Config)} is exposed (not private) so
 * Decider can reuse the exact same predicate instead of maintaining a
 * second copy that could drift out of sync — Decider's own WITHDRAW
 * decision for HEALTHY nodes and this reducer's HEALTHY-&gt;PARTITIONED
 * transition must always agree, since Decider is called immediately after
 * this reducer runs on the same HealthState snapshot (see ProbeLoop).
 *
 * SHUTTING_DOWN is set directly by the lifecycle/shutdown path (Phase 4),
 * never by this reducer — once in SHUTTING_DOWN, this reducer is sticky
 * and never leaves it based on probe or peer results.
 */
public final class FsmReducer {

    private FsmReducer() {
    }

    public static FsmState reduce(FsmState previous, ProbeResult probe, Set<PeerObservation> peers, Config config) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(config, "config");

        if (previous == FsmState.SHUTTING_DOWN) {
            return FsmState.SHUTTING_DOWN;
        }

        int successThreshold = config.probe().successThreshold();
        int failThreshold = config.probe().failThreshold();

        return switch (previous) {
            case STARTING -> reachedSuccessThreshold(probe, successThreshold) ? FsmState.HEALTHY : FsmState.STARTING;
            case HEALTHY, PARTITIONED -> nextFromActiveState(probe, peers, config, failThreshold);
            case UNHEALTHY -> reachedSuccessThreshold(probe, successThreshold) ? FsmState.HEALTHY : FsmState.UNHEALTHY;
            case SHUTTING_DOWN -> FsmState.SHUTTING_DOWN; // unreachable; guarded above
        };
    }

    private static FsmState nextFromActiveState(
            ProbeResult probe, Set<PeerObservation> peers, Config config, int failThreshold) {
        // Local-probe failure always wins over the partition condition:
        // UNHEALTHY (withdraw due to broken local filtering/resolution) is
        // strictly more severe than PARTITIONED, and the Decider precedence
        // order (BLUEPRINT.md §7) checks PARTITIONED before UNHEALTHY/
        // STARTING, so the label here must not contradict that by claiming
        // PARTITIONED when local probes have actually failed.
        if (reachedFailThreshold(probe, failThreshold)) {
            return FsmState.UNHEALTHY;
        }
        return isPartitionConditionMet(peers, config) ? FsmState.PARTITIONED : FsmState.HEALTHY;
    }

    private static boolean reachedSuccessThreshold(ProbeResult probe, int successThreshold) {
        return probe.success() && probe.consecutiveSuccesses() >= successThreshold;
    }

    private static boolean reachedFailThreshold(ProbeResult probe, int failThreshold) {
        return !probe.success() && probe.consecutiveFailures() >= failThreshold;
    }

    /**
     * Peer-partition predicate from BLUEPRINT.md §7's Decider pseudocode:
     * true once at least {@code min_peer_reports_to_self_withdraw} peers
     * have reported this node unreachable for longer than
     * {@code peer_unreachable_threshold}. Empty {@code config.peers()} (or
     * split_brain disabled) always short-circuits to false — "single-node
     * region" per BLUEPRINT.md §4 note 1 and PHASES.md Phase 3's "empty
     * peer list disables peer monitoring entirely" acceptance criterion.
     */
    public static boolean isPartitionConditionMet(Set<PeerObservation> peers, Config config) {
        SplitBrainConfig splitBrain = config.splitBrain();
        if (splitBrain == null || !splitBrain.enabled() || config.peers() == null || config.peers().isEmpty()) {
            return false;
        }
        Instant now = Instant.now();
        long unreachableCount = peers.stream()
            .filter(p -> p.reportsMeUnreachable() && p.firstReportedUnreachableTs() != null)
            .filter(p -> !Duration.between(p.firstReportedUnreachableTs(), now)
                .minus(splitBrain.peerUnreachableThreshold()).isNegative())
            .count();
        return unreachableCount >= splitBrain.minPeerReportsToSelfWithdraw();
    }
}
