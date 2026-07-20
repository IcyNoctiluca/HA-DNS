package io.homelab.dnsha.http;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;

import java.io.IOException;
import java.io.StringWriter;

/**
 * `GET /metrics` — Prometheus text-format exposition. See BLUEPRINT.md §6.
 *
 * Reads snapshots from the CollectorRegistry only (Counter/Gauge/Histogram
 * values already maintained by Metrics, ProbeLoop, BackendProbeLoop, and
 * Reconciler as they run). Never triggers a probe or a Tailscale CLI call
 * itself — AGENTS.md: "'Add a /metrics endpoint that also runs probes on
 * demand' — /metrics reads from HealthState, never triggers probes."
 */
@Path("/metrics")
public final class MetricsResource {

    private final CollectorRegistry registry;

    public MetricsResource(CollectorRegistry registry) {
        this.registry = registry;
    }

    @GET
    @Produces(TextFormat.CONTENT_TYPE_004)
    public String get() {
        StringWriter writer = new StringWriter();
        try {
            TextFormat.write004(writer, registry.metricFamilySamples());
        } catch (IOException e) {
            // StringWriter never throws IOException in practice, but
            // TextFormat.write004's signature declares it.
            throw new WebApplicationException("failed to render metrics", e);
        }
        return writer.toString();
    }
}
