package io.homelab.dnsha.lifecycle;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.http.HealthServer;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.peer.PeerMonitor;
import io.homelab.dnsha.probe.BackendProbeLoop;
import io.homelab.dnsha.probe.ProbeLoop;
import io.homelab.dnsha.state.FsmState;
import io.homelab.dnsha.state.HealthState;
import io.homelab.dnsha.tailscale.Reconciler;
import io.homelab.dnsha.tailscale.TailscaleClient;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PHASES.md Phase 4: GracefulShutdown. Drives {@code runGracefulShutdown()}
 * directly (package-visible) rather than dispatching a real OS SIGTERM --
 * see the class's javadoc for why sun.misc.Signal is used in production;
 * unit-testing actual signal delivery is out of scope here (and not
 * meaningfully testable without a real process to send a real signal to --
 * that's LifecycleIT's job, blocked on Docker per PROGRESS.md).
 *
 * ProbeLoop / WatchdogThread / HealthServer are Mockito mocks here (same
 * "mock a final production class directly" pattern DeciderTest already
 * uses for HealthState -- Mockito 5.x's default inline mock maker supports
 * this without any extra dependency) so this test exercises GracefulShutdown's
 * own sequencing/timing/exit-code logic in isolation, against a REAL
 * Reconciler + a local TailscaleClient fake for the one collaborator whose
 * actual behaviour (idempotent withdraw, fail-open shape) matters to what's
 * being asserted here.
 */
class GracefulShutdownTest {

    @Test
    void runGracefulShutdownTransitionsToShuttingDownStopsLoopsWithdrawsDrainsAndExitsZero() {
        Config config = TestConfigs.minimalBuilder().managedRoutes(List.of("10.253.53.53/32")).build();
        RecordingTailscaleClient client = new RecordingTailscaleClient();
        client.seed(Set.of("10.253.53.53/32"));
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);
        HealthState healthState = new HealthState(config);

        ProbeLoop probeLoop = mock(ProbeLoop.class);
        WatchdogThread watchdog = mock(WatchdogThread.class);
        HealthServer healthServer = mock(HealthServer.class);
        BackendProbeLoop backendLoop = mock(BackendProbeLoop.class);
        PeerMonitor peerMonitor = mock(PeerMonitor.class);

        List<Integer> exitCodes = new CopyOnWriteArrayList<>();
        GracefulShutdown shutdown = new GracefulShutdown(
            healthState, probeLoop, List.of(backendLoop), List.of(peerMonitor), watchdog, healthServer,
            reconciler, config,
            Duration.ofSeconds(2), Duration.ofMillis(20), Duration.ofMillis(50), exitCodes::add);

        shutdown.runGracefulShutdown();

        assertThat(healthState.currentState()).isEqualTo(FsmState.SHUTTING_DOWN);
        assertThat(client.currentlyAdvertised()).doesNotContain("10.253.53.53/32");
        assertThat(exitCodes).containsExactly(0);

        verify(watchdog).stop();
        verify(probeLoop).stop();
        verify(backendLoop).stop();
        verify(peerMonitor).stop();
        verify(healthServer).stopGracefully(Duration.ofMillis(50));
    }

    @Test
    void runGracefulShutdownStillExitsZeroWhenWithdrawNeverConfirms() {
        Config config = TestConfigs.minimalBuilder().managedRoutes(List.of("10.253.53.53/32")).build();
        RecordingTailscaleClient client = new RecordingTailscaleClient();
        client.seed(Set.of("10.253.53.53/32"));
        client.stickAdvertised(); // mutation "succeeds" but the route never actually clears -- fail-open shape
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);
        HealthState healthState = new HealthState(config);

        ProbeLoop probeLoop = mock(ProbeLoop.class);
        WatchdogThread watchdog = mock(WatchdogThread.class);
        HealthServer healthServer = mock(HealthServer.class);

        List<Integer> exitCodes = new CopyOnWriteArrayList<>();
        // Short confirm timeout so the test doesn't wait the real 5s default.
        GracefulShutdown shutdown = new GracefulShutdown(
            healthState, probeLoop, List.of(), List.of(), watchdog, healthServer, reconciler, config,
            Duration.ofMillis(150), Duration.ofMillis(20), Duration.ofMillis(50), exitCodes::add);

        shutdown.runGracefulShutdown();

        // Graceful shutdown still completes and exits 0 even if the
        // withdraw-confirmation loop times out -- BLUEPRINT.md §8 doesn't
        // make exit conditional on confirmation succeeding; the fail-open
        // metric (Reconciler, unaffected by this class) is what surfaces
        // the danger to n8n/monitoring, not the process's own exit code.
        assertThat(exitCodes).containsExactly(0);
        assertThat(client.advertiseCalls.size()).isGreaterThan(1); // retried more than once within the budget
    }

    @Test
    void secondConcurrentSigtermIsIgnoredOnlyOneShutdownRuns() {
        Config config = TestConfigs.minimal();
        RecordingTailscaleClient client = new RecordingTailscaleClient();
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);
        HealthState healthState = new HealthState(config);

        ProbeLoop probeLoop = mock(ProbeLoop.class);
        WatchdogThread watchdog = mock(WatchdogThread.class);
        HealthServer healthServer = mock(HealthServer.class);

        AtomicInteger exitCount = new AtomicInteger(0);
        GracefulShutdown shutdown = new GracefulShutdown(
            healthState, probeLoop, List.of(), List.of(), watchdog, healthServer, reconciler, config,
            Duration.ofMillis(50), Duration.ofMillis(10), Duration.ofMillis(20),
            code -> {
                exitCount.incrementAndGet();
                sleepQuietly(Duration.ofMillis(150)); // hold the "in progress" window open
            });

        shutdown.onSigterm();
        shutdown.onSigterm(); // must be a no-op -- the AtomicBoolean guard should block this

        sleepQuietly(Duration.ofMillis(500));
        assertThat(exitCount.get()).isEqualTo(1);
        verify(probeLoop, times(1)).stop();
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Minimal local TailscaleClient test double (see StartupReconciliationTest for the same pattern/rationale). */
    private static final class RecordingTailscaleClient implements TailscaleClient {
        final List<Set<String>> advertiseCalls = new ArrayList<>();
        private Set<String> advertised = Set.of();
        private boolean stickAdvertised = false;

        void seed(Set<String> routes) {
            this.advertised = Set.copyOf(routes);
        }

        void stickAdvertised() {
            this.stickAdvertised = true;
        }

        @Override
        public void advertiseRoutes(Set<String> routes) {
            advertiseCalls.add(Set.copyOf(routes));
            if (!stickAdvertised) {
                this.advertised = Set.copyOf(routes);
            }
        }

        @Override
        public Set<String> currentlyAdvertised() {
            return advertised;
        }
    }
}
