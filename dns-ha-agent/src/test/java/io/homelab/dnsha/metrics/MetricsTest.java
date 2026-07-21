package io.homelab.dnsha.metrics;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity checks that every metric family required by BLUEPRINT.md §6 is
 * actually registered (construction doesn't throw duplicate-registration
 * errors, and JVM exporters are present) — the fine-grained "does the value
 * come out right" behaviour is covered where each metric is driven
 * (ReconcilerTest for fail_open/route_mutations_total; MetricsResourceTest
 * for text-format rendering).
 */
class MetricsTest {

    @Test
    void constructingMultipleInstancesWithSeparateRegistriesDoesNotThrow() {
        // Each Metrics() uses its own fresh CollectorRegistry (see class
        // javadoc) — constructing several in the same JVM (as many test
        // classes do, one per test method) must never collide, including
        // the JVM (hotspot) exporters registered in each one.
        Metrics a = new Metrics();
        Metrics b = new Metrics();
        Metrics c = new Metrics();

        assertThat(a.registry).isNotSameAs(b.registry);
        assertThat(b.registry).isNotSameAs(c.registry);
    }

    @Test
    void allBlueprintSection6MetricFamiliesAreRegistered() {
        Metrics metrics = new Metrics();

        Set<String> names = collectorMetricNames(metrics.registry);

        assertThat(names).contains(
            "dns_ha_agent_probe_total",
            "dns_ha_agent_probe_duration_seconds",
            "dns_ha_agent_vip_advertised",
            "dns_ha_agent_state",
            "dns_ha_agent_peer_reachable",
            "dns_ha_agent_route_mutations_total",
            "dns_ha_agent_last_route_mutation_ts",
            "dns_ha_agent_backend_resolve_ok",
            "dns_ha_agent_backend_filter_ok",
            "dns_ha_agent_backend_probe_total",
            "dns_ha_agent_fail_open",
            "dns_ha_agent_fail_open_duration_seconds");
    }

    @Test
    void jvmMetricsAreRegistered() {
        Metrics metrics = new Metrics();

        Set<String> names = collectorMetricNames(metrics.registry);

        assertThat(names.stream().anyMatch(n -> n.startsWith("jvm_"))).isTrue();
    }

    private static Set<String> collectorMetricNames(CollectorRegistry registry) {
        // Reconstruct the real exposed name from family.type, not from
        // samples. Prometheus simpleclient's Counter strips a trailing
        // "_total" from the *family* name internally (OpenMetrics
        // convention: the suffix belongs to the exposition layer, not the
        // family identity) and only re-adds it on each emitted Sample — e.g.
        // dns_ha_agent_probe_total registers as family "dns_ha_agent_probe".
        //
        // A first fix attempt (2026-07-21, see PROGRESS.md) unioned in
        // sample.name alongside family.name, on the assumption a sample
        // would always be there to carry the suffixed name. That's true for
        // *unlabelled* counters only: SimpleCollector auto-creates a
        // zero-value "no labels" child at construction time, so an unlabelled
        // Counter has one sample immediately. But dns_ha_agent_probe_total,
        // dns_ha_agent_route_mutations_total, and
        // dns_ha_agent_backend_probe_total all declare labelNames(...), and
        // labelled children are created lazily on first .labels(...) call —
        // which nothing does at construction time. So those three still had
        // zero samples, meaning neither family.name (stripped) nor any
        // sample.name (none existed) carried the "_total" suffix, and the
        // test still failed on exactly those three even after the first fix
        // (confirmed by a second real ./gradlew check run, still 2026-07-21).
        //
        // Fixing this for real: reconstruct the exposed name from
        // family.type instead, which is known at registration time
        // regardless of whether any child/sample exists yet. This matches
        // what MetricsResource/text-format rendering does for real request
        // traffic (see MetricsResourceTest) without depending on a metric
        // having been touched first.
        Set<String> names = new HashSet<>();
        Enumeration<Collector.MetricFamilySamples> families = registry.metricFamilySamples();
        for (var family : Collections.list(families)) {
            String exposedName =
                family.type == Collector.Type.COUNTER && !family.name.endsWith("_total")
                    ? family.name + "_total"
                    : family.name;
            names.add(exposedName);
            for (var sample : family.samples) {
                names.add(sample.name);
            }
        }
        return names;
    }
}
