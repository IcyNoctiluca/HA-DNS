package io.homelab.dnsha.tailscale;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.decide.Decision;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcilerTest {

    @Test
    void advertiseCallsAdvertiseRoutesWithManagedUnionPreserved() {
        Config config = TestConfigs.minimalBuilder()
            .managedRoutes(List.of("10.253.53.53/32"))
            .preservedRoutes(List.of("10.10.0.0/24"))
            .build();
        FakeTailscaleClient client = new FakeTailscaleClient();
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);

        reconciler.reconcile(Decision.ADVERTISE);

        assertThat(client.calls).hasSize(1);
        assertThat(client.calls.get(0).routes()).containsExactlyInAnyOrder("10.253.53.53/32", "10.10.0.0/24");
    }

    @Test
    void withdrawCallsAdvertiseRoutesWithPreservedRoutesOnly() {
        Config config = TestConfigs.minimalBuilder()
            .managedRoutes(List.of("10.253.53.53/32"))
            .preservedRoutes(List.of("10.10.0.0/24"))
            .build();
        FakeTailscaleClient client = new FakeTailscaleClient();
        client.seedAdvertised(Set.of("10.253.53.53/32", "10.10.0.0/24"));
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);

        reconciler.reconcile(Decision.WITHDRAW);

        assertThat(client.calls).hasSize(1);
        assertThat(client.calls.get(0).routes()).containsExactly("10.10.0.0/24");
    }

    @Test
    void noCallWhenActualAlreadyMatchesDesired_idempotent() {
        Config config = TestConfigs.minimalBuilder()
            .managedRoutes(List.of("10.253.53.53/32"))
            .preservedRoutes(List.of())
            .build();
        FakeTailscaleClient client = new FakeTailscaleClient();
        client.seedAdvertised(Set.of("10.253.53.53/32"));
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);

        reconciler.reconcile(Decision.ADVERTISE);

        assertThat(client.calls).isEmpty();
    }

    @Test
    void observedRoutesAreNeverTreatedAsDesiredState() {
        // Even if the observed/actual set is something totally unrelated to
        // config, the desired set computed and sent to advertiseRoutes must
        // come from config alone.
        Config config = TestConfigs.minimalBuilder()
            .managedRoutes(List.of("10.253.53.53/32"))
            .preservedRoutes(List.of())
            .build();
        FakeTailscaleClient client = new FakeTailscaleClient();
        client.seedAdvertised(Set.of("192.168.99.0/24")); // unrelated to config entirely
        Reconciler reconciler = new Reconciler(client, new Metrics(), config);

        reconciler.reconcile(Decision.ADVERTISE);

        assertThat(client.calls).hasSize(1);
        assertThat(client.calls.get(0).routes()).containsExactly("10.253.53.53/32");
    }

    @Test
    void changingManagedRoutesInConfigChangesDesiredSetWithNoStateMigration() {
        FakeTailscaleClient client = new FakeTailscaleClient();
        client.seedAdvertised(Set.of("10.253.53.53/32"));

        Config configV1 = TestConfigs.minimalBuilder().managedRoutes(List.of("10.253.53.53/32")).build();
        new Reconciler(client, new Metrics(), configV1).reconcile(Decision.ADVERTISE);
        assertThat(client.calls).isEmpty(); // already matches

        // Simulate a restart with a changed config — a brand new Reconciler,
        // no persisted state carried over, just the new config.
        Config configV2 = TestConfigs.minimalBuilder()
            .managedRoutes(List.of("10.253.53.53/32", "10.20.0.0/24"))
            .build();
        new Reconciler(client, new Metrics(), configV2).reconcile(Decision.ADVERTISE);

        assertThat(client.calls).hasSize(1);
        assertThat(client.calls.get(0).routes()).containsExactlyInAnyOrder("10.253.53.53/32", "10.20.0.0/24");
    }

    @Test
    void withdrawFailingToTakeEffectSetsFailOpen() {
        Config config = TestConfigs.minimalBuilder()
            .managedRoutes(List.of("10.253.53.53/32"))
            .preservedRoutes(List.of())
            .build();
        FakeTailscaleClient client = new FakeTailscaleClient();
        client.seedAdvertised(Set.of("10.253.53.53/32"));
        client.simulateStuckAdvertised(); // mutation "succeeds" but route stays up

        Metrics metrics = new Metrics();
        Reconciler reconciler = new Reconciler(client, metrics, config);
        reconciler.reconcile(Decision.WITHDRAW);

        assertThat(reconciler.isFailOpen()).isTrue();
        assertThat(metrics.failOpen.get()).isEqualTo(1.0);
    }

    @Test
    void successfulWithdrawClearsFailOpen() {
        Config config = TestConfigs.minimalBuilder()
            .managedRoutes(List.of("10.253.53.53/32"))
            .preservedRoutes(List.of())
            .build();
        FakeTailscaleClient client = new FakeTailscaleClient();
        client.seedAdvertised(Set.of("10.253.53.53/32"));

        Metrics metrics = new Metrics();
        Reconciler reconciler = new Reconciler(client, metrics, config);
        reconciler.reconcile(Decision.WITHDRAW);

        assertThat(reconciler.isFailOpen()).isFalse();
        assertThat(metrics.failOpen.get()).isEqualTo(0.0);
    }

    @Test
    void advertiseDecisionNeverTriggersFailOpenCheck() {
        Config config = TestConfigs.minimalBuilder()
            .managedRoutes(List.of("10.253.53.53/32"))
            .preservedRoutes(List.of())
            .build();
        FakeTailscaleClient client = new FakeTailscaleClient();
        Metrics metrics = new Metrics();
        Reconciler reconciler = new Reconciler(client, metrics, config);

        reconciler.reconcile(Decision.ADVERTISE);

        assertThat(reconciler.isFailOpen()).isFalse();
        assertThat(metrics.failOpen.get()).isEqualTo(0.0);
    }
}
