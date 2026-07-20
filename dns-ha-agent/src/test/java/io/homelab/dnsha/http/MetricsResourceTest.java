package io.homelab.dnsha.http;

import io.homelab.dnsha.metrics.Metrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises MetricsResource.get() as a plain method call (see
 * HealthResourceTest's javadoc for why — no embedded HTTP server needed for
 * a unit test).
 */
class MetricsResourceTest {

    @Test
    void rendersAllDeclaredMetricFamiliesInPrometheusTextFormat() {
        Metrics metrics = new Metrics();
        metrics.probeTotal.labels("success").inc();
        metrics.state.labels("HEALTHY").set(1);
        metrics.vipAdvertised.set(1);
        metrics.backendResolveOk.labels("agh-ams-01").set(1);
        metrics.backendFilterOk.labels("agh-ams-01").set(1);
        metrics.backendProbeTotal.labels("agh-ams-01", "success").inc();
        metrics.failOpen.set(0);

        String body = new MetricsResource(metrics.registry).get();

        // Deliberately loose on exact label-serialization punctuation/decimal
        // formatting (simpleclient's TextFormat has its own quirks not
        // worth pinning byte-for-byte here) — assert the metric names, HELP/
        // TYPE lines, and label key/value substrings are all present.
        assertThat(body).contains("# TYPE dns_ha_agent_probe_total counter");
        assertThat(body).contains("dns_ha_agent_probe_total");
        assertThat(body).contains("result=\"success\"");
        assertThat(body).contains("# TYPE dns_ha_agent_state gauge");
        assertThat(body).contains("dns_ha_agent_state");
        assertThat(body).contains("state=\"HEALTHY\"");
        assertThat(body).contains("dns_ha_agent_vip_advertised");
        assertThat(body).contains("dns_ha_agent_backend_resolve_ok");
        assertThat(body).contains("dns_ha_agent_backend_filter_ok");
        assertThat(body).contains("dns_ha_agent_backend_probe_total");
        assertThat(body).contains("backend=\"agh-ams-01\"");
        assertThat(body).contains("dns_ha_agent_fail_open");
        // JVM metrics (simpleclient_hotspot) must be present — BLUEPRINT.md §6.
        assertThat(body).contains("jvm_threads");
    }

    @Test
    void emptyRegistryStillProducesValidTextOutput() {
        Metrics metrics = new Metrics();

        String body = new MetricsResource(metrics.registry).get();

        // No exception, and the always-present JVM exporters still emit something.
        assertThat(body).isNotBlank();
        assertThat(body).contains("jvm_");
    }
}
