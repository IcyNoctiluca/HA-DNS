package io.homelab.dnsha.lifecycle;

import io.homelab.dnsha.FatalStartupException;
import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.decide.Decision;
import io.homelab.dnsha.tailscale.Reconciler;
import io.homelab.dnsha.tailscale.TailscaleClient;
import io.homelab.dnsha.tailscale.TailscaleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.Set;

/**
 * BLUEPRINT.md §8 "Startup" step 4 / PHASES.md Phase 4 acceptance:
 * "Reconcile actual route state with desired state ... If the VIP is
 * already advertised (likely because a previous instance crashed without
 * graceful shutdown), explicitly withdraw it now."
 *
 * Called once from App, AFTER the HTTP server has started (step 3) and
 * BEFORE the probe loop / peer monitor loops start (steps 5-6) — this
 * ordering matters: it's what guarantees a stale advertisement from a dead
 * predecessor is never inherited as "we're healthy," since nothing has had
 * a chance to record a probe result yet when this runs.
 *
 * Always issues an explicit WITHDRAW reconciliation (via the same
 * Reconciler every other decision cycle uses — no separate TailscaleClient
 * call site, preserving AGENTS.md invariant #1). This is idempotent by
 * construction (Reconciler.reconcile is a no-op if actual already equals
 * desired), so it is safe to run unconditionally rather than only when a
 * stale VIP is detected; the WARN log line only fires when there was
 * actually something to withdraw, so a clean first-ever startup does not
 * log a scary line for no reason.
 */
public final class StartupReconciliation {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciliation.class);

    /** BLUEPRINT.md §8 error table: "Tailscale CLI/socket unavailable at startup in production cli mode -> Log fatal error and exit non-zero." */
    static final int EXIT_CODE_TAILSCALE_UNAVAILABLE_AT_STARTUP = 71; // EX_OSERR

    private StartupReconciliation() {
    }

    public static void reconcile(Config config, TailscaleClient tailscaleClient, Reconciler reconciler)
            throws FatalStartupException {
        Set<String> actual;
        try {
            actual = tailscaleClient.currentlyAdvertised();
        } catch (TailscaleException e) {
            throw new FatalStartupException(
                "unable to observe current Tailscale advertised routes at startup: " + e.getMessage()
                    + " -- a production deployment without a working Tailscale observation/mutation path "
                    + "is unsafe, refusing to start",
                EXIT_CODE_TAILSCALE_UNAVAILABLE_AT_STARTUP);
        }

        boolean stalePresent = config.tailscale().managedRoutes().stream().anyMatch(actual::contains);
        if (stalePresent) {
            // Literal phrase matches BLUEPRINT.md §8 step 4's specified log
            // text exactly (PHASES.md Phase 4 acceptance criterion greps
            // container logs for this substring) -- deliberately not the
            // usual short snake_case "msg" convention used elsewhere in this
            // codebase, for that reason.
            log.warn("found stale VIP advertisement at startup, withdrawing pending health verification",
                kv("node", config.node().name()),
                kv("previously_advertised", actual),
                kv("managed_routes", config.tailscale().managedRoutes()));
        }

        // Always withdraw explicitly (idempotent no-op if nothing was
        // advertised) -- the agent never inherits "we were already
        // advertising" from a dead predecessor; STARTING -> HEALTHY via real
        // probes is the only path back to ADVERTISE (BLUEPRINT.md §8 step 8).
        reconciler.reconcile(Decision.WITHDRAW);
    }
}
