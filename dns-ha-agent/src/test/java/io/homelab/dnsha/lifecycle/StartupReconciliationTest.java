package io.homelab.dnsha.lifecycle;

import io.homelab.dnsha.FatalStartupException;
import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.tailscale.Reconciler;
import io.homelab.dnsha.tailscale.TailscaleClient;
import io.homelab.dnsha.tailscale.TailscaleException;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHASES.md Phase 4: startup reconciliation (BLUEPRINT.md §8 step 4). */
class StartupReconciliationTest {

    @Test
    void withdrawsWhenStaleManagedRouteIsAlreadyAdvertised() throws Exception {
        Config config = TestConfigs.minimalBuilder().managedRoutes(List.of("10.253.53.53/32")).build();
        RecordingTailscaleClient client = new RecordingTailscaleClient();
        client.seed(Set.of("10.253.53.53/32"));
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);

        StartupReconciliation.reconcile(config, client, reconciler);

        assertThat(client.advertiseCalls).hasSize(1);
        assertThat(client.advertiseCalls.get(0)).doesNotContain("10.253.53.53/32");
        assertThat(client.currentlyAdvertised()).doesNotContain("10.253.53.53/32");
    }

    @Test
    void noMutationCallWhenNothingWasAdvertised() throws Exception {
        Config config = TestConfigs.minimalBuilder().managedRoutes(List.of("10.253.53.53/32")).build();
        RecordingTailscaleClient client = new RecordingTailscaleClient();
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);

        StartupReconciliation.reconcile(config, client, reconciler);

        // Reconciler.reconcile is idempotent -- actual (empty) already
        // equals desired (empty, since decision is WITHDRAW and there are
        // no preserved_routes), so no advertiseRoutes call is made at all.
        assertThat(client.advertiseCalls).isEmpty();
    }

    @Test
    void preservedRoutesSurviveStartupWithdraw() throws Exception {
        Config config = TestConfigs.minimalBuilder()
            .managedRoutes(List.of("10.253.53.53/32"))
            .preservedRoutes(List.of("10.10.0.0/24"))
            .build();
        RecordingTailscaleClient client = new RecordingTailscaleClient();
        client.seed(Set.of("10.253.53.53/32", "10.10.0.0/24"));
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);

        StartupReconciliation.reconcile(config, client, reconciler);

        assertThat(client.currentlyAdvertised()).containsExactly("10.10.0.0/24");
    }

    @Test
    void unobservableTailscaleStateAtStartupIsFatal() {
        Config config = TestConfigs.minimal();
        TailscaleClient client = new TailscaleClient() {
            @Override
            public void advertiseRoutes(Set<String> routes) {
                throw new AssertionError("must not attempt a mutation when the observation itself failed");
            }

            @Override
            public Set<String> currentlyAdvertised() throws TailscaleException {
                throw new TailscaleException("simulated: tailscaled socket unreachable");
            }
        };
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);

        assertThatThrownBy(() -> StartupReconciliation.reconcile(config, client, reconciler))
            .isInstanceOf(FatalStartupException.class);
    }

    @Test
    void fatalExceptionCarriesNonZeroExitCode() {
        Config config = TestConfigs.minimal();
        TailscaleClient client = new TailscaleClient() {
            @Override
            public void advertiseRoutes(Set<String> routes) {
            }

            @Override
            public Set<String> currentlyAdvertised() throws TailscaleException {
                throw new TailscaleException("simulated");
            }
        };
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);

        try {
            StartupReconciliation.reconcile(config, client, reconciler);
            org.junit.jupiter.api.Assertions.fail("expected FatalStartupException");
        } catch (FatalStartupException e) {
            assertThat(e.exitCode()).isNotZero();
        }
    }

    /** Minimal local TailscaleClient test double -- avoids depending on the package-private FakeTailscaleClient in io.homelab.dnsha.tailscale. */
    private static final class RecordingTailscaleClient implements TailscaleClient {
        final List<Set<String>> advertiseCalls = new ArrayList<>();
        private Set<String> advertised = Set.of();

        void seed(Set<String> routes) {
            this.advertised = Set.copyOf(routes);
        }

        @Override
        public void advertiseRoutes(Set<String> routes) {
            advertiseCalls.add(Set.copyOf(routes));
            this.advertised = Set.copyOf(routes);
        }

        @Override
        public Set<String> currentlyAdvertised() {
            return advertised;
        }
    }
}
