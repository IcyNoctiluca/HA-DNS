package io.homelab.dnsha.state;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.PeerConfig;
import io.homelab.dnsha.config.SplitBrainConfig;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HealthStateTest {

    private static final Config CONFIG = TestConfigs.minimalBuilder()
        .failThreshold(2)
        .successThreshold(3)
        .build();

    /** Same thresholds as CONFIG, plus two peers + split_brain enabled with min_peer_reports_to_self_withdraw=1. */
    private static final Config PARTITION_CONFIG = TestConfigs.minimalBuilder()
        .failThreshold(2)
        .successThreshold(3)
        .splitBrain(new SplitBrainConfig(true, Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(60), 1))
        .peers(List.of(new PeerConfig("dns-iom-01", "http://a/health")))
        .build();

    @Test
    void startsInStartingState() {
        HealthState state = new HealthState(CONFIG);
        assertThat(state.currentState()).isEqualTo(FsmState.STARTING);
        assertThat(state.snapshot().lastProbeResult().consecutiveSuccesses()).isZero();
    }

    @Test
    void consecutiveSuccessesIncrementAndResetOnFailure() {
        HealthState state = new HealthState(CONFIG);
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1));
        assertThat(state.snapshot().lastProbeResult().consecutiveSuccesses()).isEqualTo(2);

        state.recordProbeFailure("boom");
        assertThat(state.snapshot().lastProbeResult().consecutiveSuccesses()).isZero();
        assertThat(state.snapshot().lastProbeResult().consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void transitionsToHealthyAtSuccessThreshold() {
        HealthState state = new HealthState(CONFIG);
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1));
        assertThat(state.currentState()).isEqualTo(FsmState.STARTING);
        state.recordProbeSuccess(Duration.ofMillis(1));
        assertThat(state.currentState()).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void transitionsToUnhealthyAtFailThreshold() {
        HealthState state = new HealthState(CONFIG);
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1)); // -> HEALTHY

        state.recordProbeFailure("dns timeout");
        assertThat(state.currentState()).isEqualTo(FsmState.HEALTHY);
        state.recordProbeFailure("dns timeout");
        assertThat(state.currentState()).isEqualTo(FsmState.UNHEALTHY);
    }

    @Test
    void recordProbeResultDerivesSuccessFromResolveAndFilterBothOk() {
        HealthState state = new HealthState(CONFIG);
        state.recordProbeResult(true, false, Duration.ofMillis(3), "filter broken");
        assertThat(state.snapshot().lastProbeResult().success()).isFalse();
        assertThat(state.snapshot().lastProbeResult().resolveOk()).isTrue();
        assertThat(state.snapshot().lastProbeResult().filterOk()).isFalse();
    }

    @Test
    void peerObservationsAreTrackedByName() {
        HealthState state = new HealthState(CONFIG);
        state.recordPeerObservation(new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, false, null, null));
        assertThat(state.snapshot().peerObservations()).hasSize(1);

        state.recordPeerUnreachable("dns-iom-01");
        assertThat(state.snapshot().peerObservations()).hasSize(1);
        PeerObservation updated = state.snapshot().peerObservations().iterator().next();
        assertThat(updated.reportsMeUnreachable()).isTrue();
        assertThat(updated.firstReportedUnreachableTs()).isNotNull();
    }

    @Test
    void recordPeerUnreachablePreservesFirstReportedTimestampAcrossRepeatedCalls() {
        HealthState state = new HealthState(CONFIG);
        state.recordPeerUnreachable("dns-iom-01");
        Instant first = state.snapshot().peerObservations().iterator().next().firstReportedUnreachableTs();

        state.recordPeerUnreachable("dns-iom-01");
        Instant second = state.snapshot().peerObservations().iterator().next().firstReportedUnreachableTs();

        assertThat(second).isEqualTo(first);
    }

    // --- Phase 3: recordPeerPollResult (PeerMonitor's entry point) ---

    @Test
    void recordPeerPollResultTracksReachability() {
        HealthState state = new HealthState(PARTITION_CONFIG);
        state.recordPeerPollResult("dns-iom-01", true, FsmState.HEALTHY, false);

        PeerObservation obs = state.snapshot().peerObservations().iterator().next();
        assertThat(obs.reachable()).isTrue();
        assertThat(obs.reportedState()).isEqualTo(FsmState.HEALTHY);
        assertThat(obs.reportsMeUnreachable()).isFalse();
        assertThat(obs.lastSeenTs()).isNotNull();
    }

    @Test
    void recordPeerPollResultPreservesLastKnownStateAcrossFailedPoll() {
        HealthState state = new HealthState(PARTITION_CONFIG);
        state.recordPeerPollResult("dns-iom-01", true, FsmState.HEALTHY, false);
        Instant lastSeenBeforeFailure = state.snapshot().peerObservations().iterator().next().lastSeenTs();

        // Simulate a failed poll: PeerMonitor passes reachable=false but
        // carries forward the last-known reportedState/reportsMeUnreachable
        // (see PeerMonitor's lastKnownReportedState/lastKnownReportsMeUnreachable).
        state.recordPeerPollResult("dns-iom-01", false, FsmState.HEALTHY, false);

        PeerObservation obs = state.snapshot().peerObservations().iterator().next();
        assertThat(obs.reachable()).isFalse();
        assertThat(obs.reportedState()).isEqualTo(FsmState.HEALTHY); // carried forward, not nulled out
        assertThat(obs.lastSeenTs()).isEqualTo(lastSeenBeforeFailure); // not advanced on a failed poll
    }

    @Test
    void recordPeerPollResultSetsFirstReportedUnreachableTimestampOnFalseToTrueTransition() {
        HealthState state = new HealthState(PARTITION_CONFIG);
        state.recordPeerPollResult("dns-iom-01", true, FsmState.HEALTHY, false);
        assertThat(state.snapshot().peerObservations().iterator().next().firstReportedUnreachableTs()).isNull();

        state.recordPeerPollResult("dns-iom-01", true, FsmState.HEALTHY, true);
        Instant first = state.snapshot().peerObservations().iterator().next().firstReportedUnreachableTs();
        assertThat(first).isNotNull();

        // Repeated true->true calls must not move the timestamp forward.
        state.recordPeerPollResult("dns-iom-01", true, FsmState.HEALTHY, true);
        Instant second = state.snapshot().peerObservations().iterator().next().firstReportedUnreachableTs();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void recordPeerPollResultClearsFirstReportedUnreachableTimestampOnTrueToFalseTransition() {
        HealthState state = new HealthState(PARTITION_CONFIG);
        state.recordPeerPollResult("dns-iom-01", true, FsmState.HEALTHY, true);
        assertThat(state.snapshot().peerObservations().iterator().next().firstReportedUnreachableTs()).isNotNull();

        state.recordPeerPollResult("dns-iom-01", true, FsmState.HEALTHY, false);
        assertThat(state.snapshot().peerObservations().iterator().next().firstReportedUnreachableTs()).isNull();
        assertThat(state.snapshot().peerObservations().iterator().next().reportsMeUnreachable()).isFalse();
    }

    @Test
    void recordPeerPollResultCanDriveHealthyToPartitionedTransition() {
        // peer_unreachable_threshold=0 so the partition condition trips
        // immediately on the same call that sets reportsMeUnreachable=true,
        // without needing to sleep in a unit test.
        Config zeroThreshold = TestConfigs.minimalBuilder()
            .failThreshold(2)
            .successThreshold(3)
            .splitBrain(new SplitBrainConfig(true, Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ZERO, 1))
            .peers(List.of(new PeerConfig("dns-iom-01", "http://a/health")))
            .build();
        HealthState state = new HealthState(zeroThreshold);
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1)); // -> HEALTHY
        assertThat(state.currentState()).isEqualTo(FsmState.HEALTHY);

        state.recordPeerPollResult("dns-iom-01", true, FsmState.HEALTHY, true);
        assertThat(state.currentState()).isEqualTo(FsmState.PARTITIONED);

        // Peer stops reporting us unreachable -> back to HEALTHY (local probes still passing).
        state.recordPeerPollResult("dns-iom-01", true, FsmState.HEALTHY, false);
        assertThat(state.currentState()).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void recordPeerPollResultWithEmptyConfiguredPeersNeverPartitions() {
        // Defensive: even if something called recordPeerPollResult with
        // config.peers() empty (shouldn't happen -- PeerMonitor only runs
        // when peers are configured, see App.startPeerMonitors), the
        // partition condition must still be structurally unreachable.
        HealthState state = new HealthState(CONFIG); // CONFIG has no peers configured
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1)); // -> HEALTHY

        state.recordPeerPollResult("dns-iom-01", true, FsmState.HEALTHY, true);
        assertThat(state.currentState()).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void recordBackendProbeResultDoesNotAffectFsmState() {
        HealthState state = new HealthState(CONFIG);
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1)); // -> HEALTHY
        assertThat(state.currentState()).isEqualTo(FsmState.HEALTHY);

        // A backend probe failure must never move the FSM — see
        // BackendProbe's javadoc "IMPORTANT non-effect".
        state.recordBackendProbeResult("agh-iom-01", "100.64.1.2:5353", false, false);

        assertThat(state.currentState()).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void recordBackendProbeResultIsTrackedByName() {
        HealthState state = new HealthState(CONFIG);

        state.recordBackendProbeResult("agh-ams-01", "127.0.0.1:5353", true, true);
        state.recordBackendProbeResult("agh-iom-01", "100.64.1.2:5353", true, false);

        var backends = state.snapshot().backendResults();
        assertThat(backends).hasSize(2);
        assertThat(backends.get("agh-ams-01").resolveOk()).isTrue();
        assertThat(backends.get("agh-ams-01").filterOk()).isTrue();
        assertThat(backends.get("agh-iom-01").filterOk()).isFalse();
    }

    @Test
    void recordBackendProbeResultOverwritesPreviousResultForSameName() {
        HealthState state = new HealthState(CONFIG);

        state.recordBackendProbeResult("agh-ams-01", "127.0.0.1:5353", true, true);
        state.recordBackendProbeResult("agh-ams-01", "127.0.0.1:5353", false, false);

        var backends = state.snapshot().backendResults();
        assertThat(backends).hasSize(1);
        assertThat(backends.get("agh-ams-01").resolveOk()).isFalse();
    }

    @Test
    void snapshotIsConsistentUnderConcurrentUpdates() throws InterruptedException {
        HealthState state = new HealthState(CONFIG);
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        state.recordProbeSuccess(Duration.ofMillis(1));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // Every recordProbeSuccess call is a single atomic updateAndGet, so
        // the final consecutive-success count must equal the total number
        // of successful calls made — no update should be lost to a race.
        assertThat(state.snapshot().lastProbeResult().consecutiveSuccesses()).isEqualTo(threads * perThread);
        assertThat(state.currentState()).isEqualTo(FsmState.HEALTHY);
    }

    // --- Phase 4: transitionToShuttingDown ---

    @Test
    void transitionToShuttingDownSetsFsmStateDirectly() {
        HealthState state = new HealthState(CONFIG);
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1)); // -> HEALTHY

        state.transitionToShuttingDown();

        assertThat(state.currentState()).isEqualTo(FsmState.SHUTTING_DOWN);
    }

    @Test
    void transitionToShuttingDownIsStickyAgainstSubsequentProbeResults() {
        // FsmReducer treats SHUTTING_DOWN as terminal/sticky (see its
        // javadoc) -- this is the HealthState-level regression test for
        // that contract, since GracefulShutdown stops the probe loop only
        // AFTER calling transitionToShuttingDown(), so a probe cycle that
        // was already in flight could race a recordProbeResult call in
        // after SHUTTING_DOWN is set.
        HealthState state = new HealthState(CONFIG);
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1)); // -> HEALTHY

        state.transitionToShuttingDown();
        assertThat(state.currentState()).isEqualTo(FsmState.SHUTTING_DOWN);

        // A late-arriving in-flight probe result (success or failure) must
        // not move the FSM away from SHUTTING_DOWN.
        state.recordProbeSuccess(Duration.ofMillis(1));
        assertThat(state.currentState()).isEqualTo(FsmState.SHUTTING_DOWN);
        state.recordProbeFailure("late in-flight failure");
        assertThat(state.currentState()).isEqualTo(FsmState.SHUTTING_DOWN);
    }

    @Test
    void transitionToShuttingDownCalledTwiceIsIdempotent() {
        HealthState state = new HealthState(CONFIG);
        state.transitionToShuttingDown();
        state.transitionToShuttingDown();
        assertThat(state.currentState()).isEqualTo(FsmState.SHUTTING_DOWN);
    }
}
