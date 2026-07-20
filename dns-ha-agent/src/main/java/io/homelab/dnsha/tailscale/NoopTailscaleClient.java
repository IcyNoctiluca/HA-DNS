package io.homelab.dnsha.tailscale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only in-memory TailscaleClient. Never calls the real `tailscale`
 * CLI. Gated at startup by App — see AGENTS.md / BLUEPRINT.md §5
 * "tailscale.mode: noop is test-only and requires
 * DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE=true or --allow-noop-tailscale".
 * Used by the CI smoke test (no real Tailscale interface available there)
 * and by local development.
 */
public final class NoopTailscaleClient implements TailscaleClient {

    private static final Logger log = LoggerFactory.getLogger(NoopTailscaleClient.class);

    private final AtomicReference<Set<String>> advertised = new AtomicReference<>(Set.of());

    @Override
    public void advertiseRoutes(Set<String> routes) {
        log.warn("noop_tailscale_client_advertise", kv("routes", routes),
            kv("note", "tailscale.mode=noop; NOT calling real tailscale CLI"));
        advertised.set(Set.copyOf(routes));
    }

    @Override
    public Set<String> currentlyAdvertised() {
        return advertised.get();
    }
}
