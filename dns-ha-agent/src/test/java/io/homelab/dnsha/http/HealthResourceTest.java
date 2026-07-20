package io.homelab.dnsha.http;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.state.HealthState;
import io.homelab.dnsha.tailscale.Reconciler;
import io.homelab.dnsha.tailscale.TailscaleClient;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises HealthResource.get() as a plain method call — no Jersey/Grizzly
 * server needed (AGENTS.md/BLUEPRINT.md §10 "Unit tests (fast, no I/O)").
 * The HTTP wiring itself (HealthServer binding, Jersey routing, Jackson
 * serialization to real JSON bytes) is only exercised by EndToEndIT, which
 * needs a real Docker daemon and has not been run in this sandbox — see
 * PROGRESS.md.
 */
class HealthResourceTest {

    private static final Config CONFIG = TestConfigs.minimal();

    /** In-memory TailscaleClient double, local to this test (mirrors tailscale/FakeTailscaleClient's shape). */
    private static final class FakeClient implements TailscaleClient {
        private final AtomicReference<Set<String>> advertised = new AtomicReference<>(Set.of());

        @Override
        public void advertiseRoutes(Set<String> routes) {
            advertised.set(Set.copyOf(routes));
        }

        @Override
        public Set<String> currentlyAdvertised() {
            return advertised.get();
        }
    }

    @Test
    void reportsStartingStateWithVipNotAdvertisedBeforeFirstReconcile() {
        HealthState state = new HealthState(CONFIG);
        Reconciler reconciler = new Reconciler(new FakeClient(), new Metrics(), CONFIG);
        HealthResource resource = new HealthResource(state, CONFIG, reconciler, Instant.now());

        HealthResponse response = resource.get();

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.node()).isEqualTo("test-node");
        assertThat(response.region()).isEqualTo("eu");
        assertThat(response.state()).isEqualTo("STARTING");
        assertThat(response.vipAdvertised()).isFalse();
        assertThat(response.desiredVipAdvertised()).isFalse(); // STARTING -> WITHDRAW
        assertThat(response.failOpen()).isFalse();
        assertThat(response.backends()).isEmpty();
        assertThat(response.peers()).isEmpty();
        assertThat(response.unreachablePeers()).isEmpty();
        assertThat(response.uptimeSeconds()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void reportsHealthyStateAndVipAdvertisedAfterReconcile() {
        HealthState state = new HealthState(CONFIG);
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1));
        state.recordProbeSuccess(Duration.ofMillis(1)); // successThreshold=3 -> HEALTHY

        FakeClient client = new FakeClient();
        Reconciler reconciler = new Reconciler(client, new Metrics(), CONFIG);
        reconciler.reconcile(io.homelab.dnsha.decide.Decision.ADVERTISE);

        HealthResource resource = new HealthResource(state, CONFIG, reconciler, Instant.now());
        HealthResponse response = resource.get();

        assertThat(response.state()).isEqualTo("HEALTHY");
        assertThat(response.vipAdvertised()).isTrue();
        assertThat(response.desiredVipAdvertised()).isTrue();
        assertThat(response.localProbe().resolveCheckOk()).isTrue();
        assertThat(response.localProbe().filterCheckOk()).isTrue();
        assertThat(response.localProbe().consecutiveSuccesses()).isEqualTo(3);
        assertThat(response.localProbe().lastResult()).isEqualTo("HEALTHY");
    }

    @Test
    void reportsFailOpenWhenWithdrawDidNotTakeEffect() {
        HealthState state = new HealthState(CONFIG);

        // A client that "succeeds" but never actually drops the managed route.
        TailscaleClient stuckClient = new TailscaleClient() {
            private Set<String> advertised = Set.of("10.253.53.53/32");

            @Override
            public void advertiseRoutes(Set<String> routes) {
                // pretend the call succeeded but the route stays up
            }

            @Override
            public Set<String> currentlyAdvertised() {
                return advertised;
            }
        };
        Reconciler reconciler = new Reconciler(stuckClient, new Metrics(), CONFIG);
        reconciler.reconcile(io.homelab.dnsha.decide.Decision.WITHDRAW);

        HealthResource resource = new HealthResource(state, CONFIG, reconciler, Instant.now());
        HealthResponse response = resource.get();

        assertThat(response.failOpen()).isTrue();
        assertThat(response.vipAdvertised()).isTrue(); // still advertised, per the fail-open scenario
    }

    @Test
    void backendResultsAppearInBackendsArraySortedByName() {
        HealthState state = new HealthState(CONFIG);
        state.recordBackendProbeResult("agh-iom-01", "100.64.1.2:5353", true, false);
        state.recordBackendProbeResult("agh-ams-01", "127.0.0.1:5353", true, true);

        Reconciler reconciler = new Reconciler(new FakeClient(), new Metrics(), CONFIG);
        HealthResource resource = new HealthResource(state, CONFIG, reconciler, Instant.now());

        var backends = resource.get().backends();
        assertThat(backends).hasSize(2);
        assertThat(backends.get(0).name()).isEqualTo("agh-ams-01");
        assertThat(backends.get(0).filterOk()).isTrue();
        assertThat(backends.get(1).name()).isEqualTo("agh-iom-01");
        assertThat(backends.get(1).filterOk()).isFalse();
    }
}
