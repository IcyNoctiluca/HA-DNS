package io.homelab.dnsha.tailscale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Test-only in-memory TailscaleClient. Never calls the real `tailscale`
 * CLI. Gated at startup by App — see AGENTS.md / BLUEPRINT.md §5
 * "tailscale.mode: noop is test-only and requires
 * DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE=true or --allow-noop-tailscale".
 * Used by the CI smoke test (no real Tailscale interface available there)
 * and by local development.
 *
 * Phase 4 addition: optionally seeds the initial "already advertised" set
 * from {@code DNS_HA_AGENT_NOOP_SEED_ADVERTISED} (comma-separated CIDRs) at
 * construction time. This exists solely so LifecycleIT's startup-
 * reconciliation test can simulate "a previous instance crashed while still
 * advertising the VIP" WITHOUT a real Tailscale daemon/socket (which this
 * whole client already can't touch by design). Same gating as noop mode
 * itself — never read unless tailscale.mode=noop, which already requires
 * DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE=true / --allow-noop-tailscale in App.
 * Absent/blank env var (the overwhelmingly common case, including every
 * non-startup-reconciliation IT test) preserves the original empty-start
 * behaviour exactly.
 */
public final class NoopTailscaleClient implements TailscaleClient {

    private static final Logger log = LoggerFactory.getLogger(NoopTailscaleClient.class);
    static final String SEED_ENV_VAR = "DNS_HA_AGENT_NOOP_SEED_ADVERTISED";

    private final AtomicReference<Set<String>> advertised;

    public NoopTailscaleClient() {
        this(System.getenv(SEED_ENV_VAR));
    }

    /** Package-visible for tests: injects the seed value directly instead of reading the real environment. */
    NoopTailscaleClient(String seedEnvValue) {
        Set<String> seed = parseSeed(seedEnvValue);
        if (!seed.isEmpty()) {
            log.warn("noop_tailscale_client_seeded_advertised",
                kv("routes", seed), kv("note", "test-only startup state, from " + SEED_ENV_VAR));
        }
        this.advertised = new AtomicReference<>(seed);
    }

    private static Set<String> parseSeed(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

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
