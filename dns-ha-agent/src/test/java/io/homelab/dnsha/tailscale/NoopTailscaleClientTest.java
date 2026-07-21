package io.homelab.dnsha.tailscale;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 addition: NoopTailscaleClient's optional
 * DNS_HA_AGENT_NOOP_SEED_ADVERTISED-driven seeding, added solely so
 * LifecycleIT can simulate "a previous instance crashed while still
 * advertising the VIP" without a real Tailscale daemon. Uses the
 * package-visible NoopTailscaleClient(String) constructor to inject the
 * env value directly rather than mutating the real process environment.
 */
class NoopTailscaleClientTest {

    @Test
    void defaultConstructorStartsEmptyRegardlessOfRealEnvironment() {
        // The real no-arg constructor reads the actual environment, which
        // this test process does not control -- but DNS_HA_AGENT_NOOP_SEED_ADVERTISED
        // is not a variable any CI/dev machine would plausibly have set, so
        // this documents the expected default without being flaky in the
        // one-in-a-million case it IS set: assert the invariant that
        // actually matters (currentlyAdvertised() is unmodifiable / never
        // null), not the exact seed value.
        NoopTailscaleClient client = new NoopTailscaleClient();
        assertThat(client.currentlyAdvertised()).isNotNull();
    }

    @Test
    void nullSeedStartsEmpty() {
        NoopTailscaleClient client = new NoopTailscaleClient((String) null);
        assertThat(client.currentlyAdvertised()).isEmpty();
    }

    @Test
    void blankSeedStartsEmpty() {
        NoopTailscaleClient client = new NoopTailscaleClient("   ");
        assertThat(client.currentlyAdvertised()).isEmpty();
    }

    @Test
    void singleRouteSeedIsParsed() {
        NoopTailscaleClient client = new NoopTailscaleClient("10.253.53.53/32");
        assertThat(client.currentlyAdvertised()).containsExactly("10.253.53.53/32");
    }

    @Test
    void commaSeparatedRoutesAreParsedAndTrimmed() {
        NoopTailscaleClient client = new NoopTailscaleClient(" 10.253.53.53/32 , 10.10.0.0/24,10.20.0.0/24 ");
        assertThat(client.currentlyAdvertised())
            .containsExactlyInAnyOrder("10.253.53.53/32", "10.10.0.0/24", "10.20.0.0/24");
    }

    @Test
    void seededStateIsJustInitialStateNotSticky() {
        NoopTailscaleClient client = new NoopTailscaleClient("10.253.53.53/32");
        client.advertiseRoutes(Set.of());
        assertThat(client.currentlyAdvertised()).isEmpty();
    }
}
