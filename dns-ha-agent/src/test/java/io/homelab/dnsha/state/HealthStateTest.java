package io.homelab.dnsha.state;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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
        state.recordPeerObservation(new PeerObservation("dns-iom-01", true, FsmState.HEALTHY, false, null));
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
}
