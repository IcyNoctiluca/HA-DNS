package io.homelab.dnsha.tailscale;

import java.util.Set;

/**
 * Abstracts the Tailscale mutation. AGENTS.md invariant #1: this is the
 * ONLY interface anything in this codebase uses to touch `tailscale set`.
 * AGENTS.md invariant #2: both methods must be idempotent — calling either
 * twice in a row with the same target state is a no-op the second time.
 * (For advertiseRoutes, the "no second call" idempotency is actually
 * enforced one layer up, by Reconciler comparing desired vs
 * currentlyAdvertised() before calling — see Reconciler.reconcile.)
 */
public interface TailscaleClient {

    void advertiseRoutes(Set<String> routes) throws TailscaleException;

    Set<String> currentlyAdvertised() throws TailscaleException;
}
