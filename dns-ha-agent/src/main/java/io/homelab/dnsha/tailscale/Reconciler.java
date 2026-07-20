package io.homelab.dnsha.tailscale;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.decide.Decision;
import io.homelab.dnsha.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The ONLY component that calls TailscaleClient for mutation (AGENTS.md
 * invariant #1, #3). Computes the desired route set from config + Decision,
 * compares against TailscaleClient.currentlyAdvertised(), and mutates only
 * on drift. See BLUEPRINT.md §7.
 *
 * AGENTS.md invariant #3: no persisted RouteStateStore. Desired state is
 * recomputed from config on every call to reconcile() — there is no field
 * here that remembers "what we last decided" beyond what's needed to track
 * fail-open duration (failOpenSince), which is diagnostic, not intent.
 */
public final class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final TailscaleClient client;
    private final Metrics metrics;
    private final Config config;

    private volatile Instant failOpenSince;

    public Reconciler(TailscaleClient client, Metrics metrics, Config config) {
        this.client = client;
        this.metrics = metrics;
        this.config = config;
    }

    public synchronized void reconcile(Decision decision) {
        Set<String> managed = Set.copyOf(config.tailscale().managedRoutes());
        Set<String> preserved = Set.copyOf(config.tailscale().preservedRoutes());
        Set<String> desired = decision == Decision.ADVERTISE ? union(managed, preserved) : preserved;

        Set<String> actual;
        try {
            actual = client.currentlyAdvertised();
        } catch (TailscaleException e) {
            // Can't safely tell what's actually advertised; skip mutation
            // this cycle rather than guess. Next probe cycle retries.
            log.error("tailscale_observe_failed", kv("node", config.node().name()), kv("error", e.getMessage()));
            return;
        }

        if (!actual.equals(desired)) {
            String action = decision == Decision.ADVERTISE ? "advertise" : "withdraw";
            Instant start = Instant.now();
            try {
                client.advertiseRoutes(desired);
                long durationMs = Duration.between(start, Instant.now()).toMillis();
                metrics.routeMutationsTotal.labels(action, "success").inc();
                metrics.lastRouteMutationTs.set(Instant.now().toEpochMilli() / 1000.0);
                log.info("route_mutation",
                    kv("node", config.node().name()),
                    kv("action", action),
                    kv("result", "success"),
                    kv("duration_ms", durationMs),
                    kv("desired", desired));
            } catch (TailscaleException e) {
                metrics.routeMutationsTotal.labels(action, "failure").inc();
                log.error("route_mutation",
                    kv("node", config.node().name()),
                    kv("action", action),
                    kv("result", "failure"),
                    kv("error", e.getMessage()));
            }
        }

        updateFailOpenMetric(decision, managed);
    }

    private void updateFailOpenMetric(Decision decision, Set<String> managed) {
        Set<String> postActual;
        try {
            postActual = client.currentlyAdvertised();
        } catch (TailscaleException e) {
            log.error("tailscale_observe_failed_post_mutation",
                kv("node", config.node().name()), kv("error", e.getMessage()));
            // Unknown post-state after a mutation attempt is itself a
            // fail-open-shaped risk (we cannot prove the route is down).
            // Treat conservatively as fail-open if we were trying to withdraw.
            postActual = managed;
        }

        boolean failOpen = decision == Decision.WITHDRAW && managed.stream().anyMatch(postActual::contains);

        if (failOpen) {
            if (failOpenSince == null) {
                failOpenSince = Instant.now();
                log.error("fail_open_detected", kv("node", config.node().name()), kv("managed_routes", managed));
            }
            long seconds = Duration.between(failOpenSince, Instant.now()).getSeconds();
            metrics.failOpen.set(1);
            metrics.failOpenDurationSeconds.set(seconds);
        } else {
            failOpenSince = null;
            metrics.failOpen.set(0);
            metrics.failOpenDurationSeconds.set(0);
        }
    }

    public boolean isFailOpen() {
        return failOpenSince != null;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new LinkedHashSet<>(a);
        result.addAll(b);
        return Set.copyOf(result);
    }
}
