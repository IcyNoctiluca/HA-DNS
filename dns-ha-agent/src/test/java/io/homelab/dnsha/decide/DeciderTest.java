package io.homelab.dnsha.decide;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.PeerConfig;
import io.homelab.dnsha.config.SplitBrainConfig;
import io.homelab.dnsha.state.FsmState;
import io.homelab.dnsha.state.HealthSnapshot;
import io.homelab.dnsha.state.HealthState;
import io.homelab.dnsha.state.PeerObservation;
import io.homelab.dnsha.state.ProbeResult;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeciderTest {

    private static HealthState fakeState(FsmState fsm) {
        return fakeState(fsm, Set.of());
    }

    private static HealthState fakeState(FsmState fsm, Set<PeerObservation> peers) {
        HealthState state = mock(HealthState.class);
        HealthSnapshot snapshot = new HealthSnapshot(fsm, ProbeResult.initial(), peers, Instant.now());
        when(state.snapshot()).thenReturn(snapshot);
        return state;
    }

    @Test
    void startingWithdraws() {
        Decision decision = Decider.decide(fakeState(FsmState.STARTING), TestConfigs.minimal(), () -> false);
        assertThat(decision).isEqualTo(Decision.WITHDRAW);
    }

    @Test
    void unhealthyWithdraws() {
        Decision decision = Decider.decide(fakeState(FsmState.UNHEALTHY), TestConfigs.minimal(), () -> false);
        assertThat(decision).isEqualTo(Decision.WITHDRAW);
    }

    @Test
    void partitionedWithdraws() {
        Decision decision = Decider.decide(fakeState(FsmState.PARTITIONED), TestConfigs.minimal(), () -> false);
        assertThat(decision).isEqualTo(Decision.WITHDRAW);
    }

    @Test
    void shuttingDownWithdraws() {
        Decision decision = Decider.decide(fakeState(FsmState.SHUTTING_DOWN), TestConfigs.minimal(), () -> false);
        assertThat(decision).isEqualTo(Decision.WITHDRAW);
    }

    @Test
    void healthyWithNoPartitionConditionAdvertises() {
        Decision decision = Decider.decide(fakeState(FsmState.HEALTHY), TestConfigs.minimal(), () -> false);
        assertThat(decision).isEqualTo(Decision.ADVERTISE);
    }

    @Test
    void forceWithdrawOverridesHealthy() {
        Decision decision = Decider.decide(fakeState(FsmState.HEALTHY), TestConfigs.minimal(), () -> true);
        assertThat(decision).isEqualTo(Decision.WITHDRAW);
    }

    @Test
    void forceWithdrawDoesNotOverrideShuttingDown() {
        // SHUTTING_DOWN always wins regardless of force-withdraw file state
        // (there's nothing "more withdrawn" than SHUTTING_DOWN, but this
        // documents that the precedence check order is SHUTTING_DOWN first).
        Decision decision = Decider.decide(fakeState(FsmState.SHUTTING_DOWN), TestConfigs.minimal(), () -> true);
        assertThat(decision).isEqualTo(Decision.WITHDRAW);
    }

    @Test
    void emptyPeerListDisablesPartitionLogicEvenWhenSplitBrainEnabled() {
        Config config = TestConfigs.minimalBuilder()
            .splitBrain(new SplitBrainConfig(true, Duration.ofSeconds(15), Duration.ofSeconds(5),
                Duration.ofSeconds(60), 1))
            .peers(List.of())
            .build();

        // Even with a peer observation reporting unreachable somehow present
        // (shouldn't happen with an empty peers config, but exercises the
        // "empty config.peers() short-circuits" branch specifically).
        PeerObservation unreachable = new PeerObservation(
            "dns-iom-01", true, FsmState.HEALTHY, true, Instant.now().minus(Duration.ofMinutes(5)));

        Decision decision = Decider.decide(fakeState(FsmState.HEALTHY, Set.of(unreachable)), config, () -> false);
        assertThat(decision).isEqualTo(Decision.ADVERTISE);
    }

    @Test
    void partitionConditionMetWhenEnoughPeersReportUnreachablePastThreshold() {
        Config config = TestConfigs.minimalBuilder()
            .splitBrain(new SplitBrainConfig(true, Duration.ofSeconds(15), Duration.ofSeconds(5),
                Duration.ofSeconds(60), 2))
            .peers(List.of(new PeerConfig("dns-iom-01", "http://a/health"), new PeerConfig("dns-tor-01", "http://b/health")))
            .build();

        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        Set<PeerObservation> peers = Set.of(
            new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, true, longAgo),
            new PeerObservation("dns-tor-01", true, FsmState.HEALTHY, true, longAgo));

        Decision decision = Decider.decide(fakeState(FsmState.HEALTHY, peers), config, () -> false);
        assertThat(decision).isEqualTo(Decision.WITHDRAW);
    }

    @Test
    void partitionConditionNotMetBelowMinPeerReports() {
        Config config = TestConfigs.minimalBuilder()
            .splitBrain(new SplitBrainConfig(true, Duration.ofSeconds(15), Duration.ofSeconds(5),
                Duration.ofSeconds(60), 2))
            .peers(List.of(new PeerConfig("dns-iom-01", "http://a/health"), new PeerConfig("dns-tor-01", "http://b/health")))
            .build();

        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        Set<PeerObservation> peers = Set.of(
            new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, true, longAgo));

        Decision decision = Decider.decide(fakeState(FsmState.HEALTHY, peers), config, () -> false);
        assertThat(decision).isEqualTo(Decision.ADVERTISE);
    }

    @Test
    void publicDecideUsesRealForceWithdrawFileCheck() {
        // Smoke test for the single-arg public overload: on a machine
        // without /var/lib/dns-ha-agent/force-withdraw, a HEALTHY node
        // with no peers configured should ADVERTISE.
        Decision decision = Decider.decide(fakeState(FsmState.HEALTHY), TestConfigs.minimal());
        assertThat(decision).isEqualTo(Decision.ADVERTISE);
    }
}
