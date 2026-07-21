package io.homelab.dnsha.state;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.PeerConfig;
import io.homelab.dnsha.config.SplitBrainConfig;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FsmReducerTest {

    private static final Config CONFIG = TestConfigs.minimalBuilder()
        .failThreshold(2)
        .successThreshold(3)
        .build();

    private static ProbeResult success(int consecutiveSuccesses) {
        return new ProbeResult(true, true, true, null, Duration.ofMillis(5), Instant.now(), consecutiveSuccesses, 0);
    }

    private static ProbeResult failure(int consecutiveFailures) {
        return new ProbeResult(false, false, false, "boom", Duration.ofMillis(5), Instant.now(), 0, consecutiveFailures);
    }

    @Test
    void startingStaysStartingBelowSuccessThreshold() {
        FsmState next = FsmReducer.reduce(FsmState.STARTING, success(2), Set.of(), CONFIG);
        assertThat(next).isEqualTo(FsmState.STARTING);
    }

    @Test
    void startingTransitionsToHealthyAtSuccessThreshold() {
        FsmState next = FsmReducer.reduce(FsmState.STARTING, success(3), Set.of(), CONFIG);
        assertThat(next).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void startingStaysStartingOnFailure() {
        FsmState next = FsmReducer.reduce(FsmState.STARTING, failure(1), Set.of(), CONFIG);
        assertThat(next).isEqualTo(FsmState.STARTING);
    }

    @Test
    void healthyStaysHealthyBelowFailThreshold() {
        FsmState next = FsmReducer.reduce(FsmState.HEALTHY, failure(1), Set.of(), CONFIG);
        assertThat(next).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void healthyTransitionsToUnhealthyAtFailThreshold() {
        FsmState next = FsmReducer.reduce(FsmState.HEALTHY, failure(2), Set.of(), CONFIG);
        assertThat(next).isEqualTo(FsmState.UNHEALTHY);
    }

    @Test
    void healthyStaysHealthyOnContinuedSuccess() {
        FsmState next = FsmReducer.reduce(FsmState.HEALTHY, success(500), Set.of(), CONFIG);
        assertThat(next).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void unhealthyStaysUnhealthyBelowSuccessThreshold() {
        FsmState next = FsmReducer.reduce(FsmState.UNHEALTHY, success(1), Set.of(), CONFIG);
        assertThat(next).isEqualTo(FsmState.UNHEALTHY);
    }

    @Test
    void unhealthyTransitionsToHealthyAtSuccessThreshold() {
        FsmState next = FsmReducer.reduce(FsmState.UNHEALTHY, success(3), Set.of(), CONFIG);
        assertThat(next).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void unhealthyStaysUnhealthyOnContinuedFailure() {
        FsmState next = FsmReducer.reduce(FsmState.UNHEALTHY, failure(50), Set.of(), CONFIG);
        assertThat(next).isEqualTo(FsmState.UNHEALTHY);
    }

    @Test
    void partitionedReturnsToHealthyOnSuccessThreshold() {
        FsmState next = FsmReducer.reduce(FsmState.PARTITIONED, success(3), Set.of(), CONFIG);
        assertThat(next).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void partitionedTransitionsToUnhealthyOnFailThreshold() {
        FsmState next = FsmReducer.reduce(FsmState.PARTITIONED, failure(2), Set.of(), CONFIG);
        assertThat(next).isEqualTo(FsmState.UNHEALTHY);
    }

    @Test
    void shuttingDownIsStickyRegardlessOfProbeResult() {
        assertThat(FsmReducer.reduce(FsmState.SHUTTING_DOWN, success(10), Set.of(), CONFIG))
            .isEqualTo(FsmState.SHUTTING_DOWN);
        assertThat(FsmReducer.reduce(FsmState.SHUTTING_DOWN, failure(10), Set.of(), CONFIG))
            .isEqualTo(FsmState.SHUTTING_DOWN);
    }

    @Test
    void emptyPeerSetDoesNotAffectLocalProbeDrivenTransitions() {
        // A reachable, non-reporting-me-unreachable peer observation must
        // never itself change a local-probe-driven outcome (STARTING ->
        // HEALTHY here doesn't even consult peers).
        PeerObservation peer = new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, false, null, null);
        FsmState withPeers = FsmReducer.reduce(FsmState.STARTING, success(3), Set.of(peer), CONFIG);
        FsmState withoutPeers = FsmReducer.reduce(FsmState.STARTING, success(3), Set.of(), CONFIG);
        assertThat(withPeers).isEqualTo(withoutPeers).isEqualTo(FsmState.HEALTHY);
    }

    // --- Phase 3: peer-partition transitions (BLUEPRINT.md §4/§7) ---

    private static Config splitBrainConfig(int minReports) {
        return TestConfigs.minimalBuilder()
            .failThreshold(2)
            .successThreshold(3)
            .splitBrain(new SplitBrainConfig(
                true, Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(60), minReports))
            .peers(List.of(new PeerConfig("dns-iom-01", "http://a/health"), new PeerConfig("dns-tor-01", "http://b/health")))
            .build();
    }

    @Test
    void healthyTransitionsToPartitionedWhenEnoughPeersReportUnreachablePastThreshold() {
        Config config = splitBrainConfig(2);
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        Set<PeerObservation> peers = Set.of(
            new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, true, longAgo, Instant.now()),
            new PeerObservation("dns-tor-01", true, FsmState.HEALTHY, true, longAgo, Instant.now()));

        FsmState next = FsmReducer.reduce(FsmState.HEALTHY, success(500), peers, config);
        assertThat(next).isEqualTo(FsmState.PARTITIONED);
    }

    @Test
    void healthyStaysHealthyWhenBelowMinPeerReports() {
        Config config = splitBrainConfig(2);
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        Set<PeerObservation> peers = Set.of(
            new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, true, longAgo, Instant.now()));

        FsmState next = FsmReducer.reduce(FsmState.HEALTHY, success(500), peers, config);
        assertThat(next).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void healthyStaysHealthyWhenUnreachableReportIsBelowThresholdDuration() {
        Config config = splitBrainConfig(1);
        // Reported unreachable only 1s ago; peer_unreachable_threshold is 60s.
        Instant justNow = Instant.now().minus(Duration.ofSeconds(1));
        Set<PeerObservation> peers = Set.of(
            new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, true, justNow, Instant.now()));

        FsmState next = FsmReducer.reduce(FsmState.HEALTHY, success(500), peers, config);
        assertThat(next).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void partitionedReturnsToHealthyOncePeerConditionClearsAndProbesStillPass() {
        Config config = splitBrainConfig(1);
        // Peer no longer reports us unreachable (reportsMeUnreachable=false).
        Set<PeerObservation> peers = Set.of(
            new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, false, null, Instant.now()));

        FsmState next = FsmReducer.reduce(FsmState.PARTITIONED, success(500), peers, config);
        assertThat(next).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void partitionedStaysPartitionedWhilePeerConditionStillHolds() {
        Config config = splitBrainConfig(1);
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        Set<PeerObservation> peers = Set.of(
            new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, true, longAgo, Instant.now()));

        FsmState next = FsmReducer.reduce(FsmState.PARTITIONED, success(500), peers, config);
        assertThat(next).isEqualTo(FsmState.PARTITIONED);
    }

    @Test
    void localProbeFailureTakesPriorityOverPartitionCondition() {
        // Even with the partition condition met, a local fail-threshold
        // breach must still win -> UNHEALTHY, not PARTITIONED (see
        // FsmReducer.nextFromActiveState javadoc: local failure is treated
        // as strictly more severe).
        Config config = splitBrainConfig(1);
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        Set<PeerObservation> peers = Set.of(
            new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, true, longAgo, Instant.now()));

        FsmState next = FsmReducer.reduce(FsmState.HEALTHY, failure(2), peers, config);
        assertThat(next).isEqualTo(FsmState.UNHEALTHY);
    }

    @Test
    void emptyConfigPeersDisablesPartitionEvenIfSplitBrainEnabledAndObservationsPresent() {
        // split_brain.enabled=true but config.peers() empty -> partition
        // condition can never hold (BLUEPRINT.md §4 note 1: single-node
        // region). A stray peer observation (shouldn't normally exist with
        // no peers configured, since PeerMonitor wouldn't be running) must
        // still not trip PARTITIONED.
        Config config = TestConfigs.minimalBuilder()
            .splitBrain(new SplitBrainConfig(true, Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(60), 1))
            .peers(List.of())
            .build();
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        Set<PeerObservation> peers = Set.of(
            new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, true, longAgo, Instant.now()));

        FsmState next = FsmReducer.reduce(FsmState.HEALTHY, success(500), peers, config);
        assertThat(next).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void isPartitionConditionMetMatchesReduceOutcome() {
        // FsmReducer.isPartitionConditionMet is the same predicate Decider
        // delegates to (see Decider's class javadoc) — assert it agrees
        // with what reduce() actually does for the same inputs.
        Config config = splitBrainConfig(2);
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        Set<PeerObservation> peers = Set.of(
            new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, true, longAgo, Instant.now()),
            new PeerObservation("dns-tor-01", true, FsmState.HEALTHY, true, longAgo, Instant.now()));

        assertThat(FsmReducer.isPartitionConditionMet(peers, config)).isTrue();
        assertThat(FsmReducer.reduce(FsmState.HEALTHY, success(500), peers, config)).isEqualTo(FsmState.PARTITIONED);
    }
}
