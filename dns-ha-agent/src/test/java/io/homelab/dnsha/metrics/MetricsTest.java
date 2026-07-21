package io.homelab.dnsha.metrics;

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
        // Union of family names AND individual sample names, not just family
        // names. Prometheus simpleclient's Counter strips a trailing "_total"
        // from the *family* name internally (OpenMetrics convention: the
        // suffix belongs to the exposition layer, not the family identity)
        // and only re-adds it on each emitted Sample. So for a Counter named
        // "..._total" at build time, family.name comes back WITHOUT the
        // suffix while sample.name has it back on — e.g.
        // dns_ha_agent_probe_total registers as family "dns_ha_agent_probe"
        // with a sample named "dns_ha_agent_probe_total". Checking only
        // family names (as this test originally did) made every "_total"
        // counter look unregistered even though /metrics renders it
        // correctly (see MetricsResourceTest, which reads sample text and
        // passes). Discovered by the first real test run of this file
        // (2026-07-21) — see PROGRESS.md.
        Set<String> names = new HashSet<>();
        Enumeration<io.prometheus.client.Collector.MetricFamilySamples> families =
            registry.metricFamilySamples();
        for (var family : Collections.list(families)) {
            names.add(family.name);
            for (var sample : family.samples) {
                names.add(sample.name);
            }
        }
        return names;
    }
}
