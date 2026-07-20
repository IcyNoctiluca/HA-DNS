package io.homelab.dnsha.metrics;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.hotspot.BufferPoolsExports;
import io.prometheus.client.hotspot.ClassLoadingExports;
import io.prometheus.client.hotspot.GarbageCollectorExports;
import io.prometheus.client.hotspot.MemoryPoolsExports;
import io.prometheus.client.hotspot.StandardExports;
import io.prometheus.client.hotspot.ThreadExports;
import io.prometheus.client.hotspot.VersionInfoExports;

/**
 * Thin wrapper around the Prometheus simpleclient collectors this agent
 * exports. See BLUEPRINT.md §6 for the exact metric names/labels this must
 * match.
 *
 * The /metrics HTTP endpoint (MetricsResource, Phase 2) reads
 * registry.metricFamilySamples() from the instance registry held here — it
 * never triggers probes itself (AGENTS.md "things that look like good
 * ideas but break the contract": "/metrics reads from HealthState, never
 * triggers probes").
 *
 * Registering against a fresh CollectorRegistry per instance (rather than
 * the static default registry) keeps unit tests that construct multiple
 * Metrics/Reconcilers isolated from each other, and keeps the JVM
 * (hotspot) exporters registered here from colliding with any other
 * Metrics instance's JVM exporters in the same test JVM.
 */
public final class Metrics {

    public final CollectorRegistry registry;
    public final Counter probeTotal;
    public final Histogram probeDurationSeconds;
    public final Gauge vipAdvertised;
    public final Gauge state;
    public final Gauge peerReachable;
    public final Counter routeMutationsTotal;
    public final Gauge lastRouteMutationTs;
    public final Gauge backendResolveOk;
    public final Gauge backendFilterOk;
    public final Counter backendProbeTotal;
    public final Gauge failOpen;
    public final Gauge failOpenDurationSeconds;

    public Metrics() {
        this(new CollectorRegistry(), true);
    }

    /** Package/test visibility knob: skip JVM exporter registration when constructing many instances in one test JVM. */
    public Metrics(CollectorRegistry registry) {
        this(registry, true);
    }

    public Metrics(CollectorRegistry registry, boolean registerJvmMetrics) {
        this.registry = registry;
        this.probeTotal = Counter.build()
            .name("dns_ha_agent_probe_total")
            .help("Total local probes performed")
            .labelNames("result")
            .register(registry);
        this.probeDurationSeconds = Histogram.build()
            .name("dns_ha_agent_probe_duration_seconds")
            .help("Local probe duration")
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
        this.peerReachable = Gauge.build()
            .name("dns_ha_agent_peer_reachable")
            .help("Peer reachability (1=reachable, 0=unreachable)")
            .labelNames("peer")
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
        this.backendResolveOk = Gauge.build()
            .name("dns_ha_agent_backend_resolve_ok")
            .help("Per-backend resolve probe result (1=ok, 0=failing)")
            .labelNames("backend")
            .register(registry);
        this.backendFilterOk = Gauge.build()
            .name("dns_ha_agent_backend_filter_ok")
            .help("Per-backend filter probe result (1=ok, 0=filter broken)")
            .labelNames("backend")
            .register(registry);
        this.backendProbeTotal = Counter.build()
            .name("dns_ha_agent_backend_probe_total")
            .help("Per-backend probe outcome counter")
            .labelNames("backend", "result")
            .register(registry);
        this.failOpen = Gauge.build()
            .name("dns_ha_agent_fail_open")
            .help("1 when desired=WITHDRAW but VIP is still advertised (fail-open condition)")
            .register(registry);
        this.failOpenDurationSeconds = Gauge.build()
            .name("dns_ha_agent_fail_open_duration_seconds")
            .help("Seconds the fail-open condition has been continuously active (0 if not active)")
            .register(registry);

        if (registerJvmMetrics) {
            registerJvmMetrics(registry);
        }
    }

    /**
     * JVM metrics (heap, GC, threads, classloading) — BLUEPRINT.md §6
     * "Standard JVM metrics ... auto-exported by simpleclient_hotspot."
     * simpleclient_hotspot's DefaultExports.initialize() only targets the
     * static CollectorRegistry.defaultRegistry, so with a per-instance
     * registry (see class javadoc) each exporter must be registered
     * explicitly against `registry` instead.
     */
    private static void registerJvmMetrics(CollectorRegistry registry) {
        new StandardExports().register(registry);
        new MemoryPoolsExports().register(registry);
        new BufferPoolsExports().register(registry);
        new GarbageCollectorExports().register(registry);
        new ThreadExports().register(registry);
        new ClassLoadingExports().register(registry);
        new VersionInfoExports().register(registry);
    }
}
