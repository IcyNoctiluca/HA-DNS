package io.homelab.dnsha.state;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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
        // FsmReducer does not itself consult peers in Phase 1/2 (see class
        // javadoc) — passing a non-empty peer set must not change the
        // local-probe-driven outcome.
        PeerObservation peer = new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, false, null);
        FsmState withPeers = FsmReducer.reduce(FsmState.STARTING, success(3), Set.of(peer), CONFIG);
        FsmState withoutPeers = FsmReducer.reduce(FsmState.STARTING, success(3), Set.of(), CONFIG);
        assertThat(withPeers).isEqualTo(withoutPeers).isEqualTo(FsmState.HEALTHY);
    }
}
