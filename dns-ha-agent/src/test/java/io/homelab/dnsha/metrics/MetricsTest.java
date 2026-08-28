package io.homelab.dnsha.metrics;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

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
    void allVersionedContractMetricFamiliesAreRegistered() throws IOException {
        Metrics metrics = new Metrics();

        Set<String> registeredApplicationNames = collectorMetricFamilyNames(metrics.registry).stream()
            .filter(name -> name.startsWith("dns_ha_agent_"))
            .collect(Collectors.toSet());
        Set<String> contractedNames = Files.readAllLines(
                Path.of("contract", "metrics-contract.v1.txt"))
            .stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .map(line -> line.replaceFirst("\\{.*$", ""))
            .collect(Collectors.toSet());

        assertThat(registeredApplicationNames).isEqualTo(contractedNames);
    }

    @Test
    void jvmMetricsAreRegistered() {
        Metrics metrics = new Metrics();

        Set<String> names = collectorMetricFamilyNames(metrics.registry);

        assertThat(names.stream().anyMatch(n -> n.startsWith("jvm_"))).isTrue();
    }

    private static Set<String> collectorMetricFamilyNames(CollectorRegistry registry) {
        // Reconstruct the real exposed name from family.type, not from
        // samples. Prometheus simpleclient's Counter strips a trailing
        // "_total" from the *family* name internally (OpenMetrics
        // convention: the suffix belongs to the exposition layer, not the
        // family identity) and only re-adds it on each emitted Sample — e.g.
        // dns_ha_agent_probe_total registers as family "dns_ha_agent_probe".
        //
        // Reconstruct the exposed name from
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
        }
        return names;
    }
}
