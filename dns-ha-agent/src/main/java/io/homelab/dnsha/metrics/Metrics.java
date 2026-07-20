package io.homelab.dnsha.metrics;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

/**
 * Thin wrapper around the Prometheus simpleclient collectors this agent
 * exports. See BLUEPRINT.md §6 for the exact metric names/labels this must
 * match.
 *
 * The /metrics HTTP endpoint itself (MetricsResource) is Phase 2 scope.
 * This class is introduced in Phase 1 because Reconciler needs somewhere
 * to record dns_ha_agent_fail_open and route_mutations_total as it works —
 * per PHASES.md Phase 1 acceptance criteria ("WITHDRAW + observed
 * managed-route still advertised after mutation -> dns_ha_agent_fail_open
 * = 1"), that behaviour must exist and be assertable now, even though
 * nothing serves it over HTTP yet. Registering against a fresh
 * CollectorRegistry per instance (rather than the static default registry)
 * keeps unit tests that construct multiple Reconcilers isolated from each
 * other.
 */
public final class Metrics {

    public final CollectorRegistry registry;
    public final Counter probeTotal;
    public final Gauge vipAdvertised;
    public final Gauge state;
    public final Counter routeMutationsTotal;
    public final Gauge lastRouteMutationTs;
    public final Gauge failOpen;
    public final Gauge failOpenDurationSeconds;

    public Metrics() {
        this(new CollectorRegistry());
    }

    public Metrics(CollectorRegistry registry) {
        this.registry = registry;
        this.probeTotal = Counter.build()
            .name("dns_ha_agent_probe_total")
            .help("Total local probes performed")
            .labelNames("result")
            .register(registry);
        this.vipAdvertised = Gauge.build()
            .name("dns_ha_agent_vip_advertised")
            .help("Currently advertising VIP route (1=yes, 0=no)")
            .register(registry);
        this.state = Gauge.build()
            .name("dns_ha_agent_state")
            .help("Current FSM state")
            .labelNames("state")
            .register(registry);
        this.routeMutationsTotal = Counter.build()
            .name("dns_ha_agent_route_mutations_total")
            .help("Total tailscale set invocations")
            .labelNames("action", "result")
            .register(registry);
        this.lastRouteMutationTs = Gauge.build()
            .name("dns_ha_agent_last_route_mutation_ts")
            .help("Unix timestamp of last tailscale set")
            .register(registry);
        this.failOpen = Gauge.build()
            .name("dns_ha_agent_fail_open")
            .help("1 when desired=WITHDRAW but VIP is still advertised (fail-open condition)")
            .register(registry);
        this.failOpenDurationSeconds = Gauge.build()
            .name("dns_ha_agent_fail_open_duration_seconds")
            .help("Seconds the fail-open condition has been continuously active (0 if not active)")
            .register(registry);
    }
}
