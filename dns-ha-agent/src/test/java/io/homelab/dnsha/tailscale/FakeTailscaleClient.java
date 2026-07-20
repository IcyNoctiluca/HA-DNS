package io.homelab.dnsha.tailscale;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * In-memory TailscaleClient test double for ReconcilerTest. Not a
 * production class — the production noop client is NoopTailscaleClient.
 * This one additionally records every mutation call for assertions and
 * supports simulating "the mutation call succeeds but doesn't actually
 * change advertised state" (stuckAdvertised) to exercise the fail-open path.
 */
final class FakeTailscaleClient implements TailscaleClient {

    record Call(Set<String> routes) {
    }

    final List<Call> calls = new ArrayList<>();
    private Set<String> advertised = Set.of();
    private boolean stuckAdvertised = false;
    private RuntimeException throwOnAdvertise;

    @Override
    public void advertiseRoutes(Set<String> routes) throws TailscaleException {
        calls.add(new Call(routes));
        if (throwOnAdvertise != null) {
            throw new TailscaleException("simulated failure", throwOnAdvertise);
        }
        if (!stuckAdvertised) {
            advertised = Set.copyOf(routes);
        }
        // else: pretend the CLI call "succeeded" but the route stayed up
        // (e.g. tailscaled ignored it / socket wedged) — this is exactly
        // the scenario Reconciler's fail-open check must catch.
    }

    @Override
    public Set<String> currentlyAdvertised() {
        return advertised;
    }

    void seedAdvertised(Set<String> routes) {
        this.advertised = Set.copyOf(routes);
    }

    void simulateStuckAdvertised() {
        this.stuckAdvertised = true;
    }
}
