package io.homelab.dnsha.http;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.state.HealthState;
import io.homelab.dnsha.tailscale.Reconciler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Jersey-over-Grizzly HTTP server exposing /health and /metrics
 * (BLUEPRINT.md §6, §9). Binds to config.http().bind_address():port() —
 * IMPORTANT: in production this MUST be the node's Tailscale IP, never
 * 0.0.0.0 or 127.0.0.1 (top-level AGENTS.md invariant #8; HttpConfig's
 * javadoc notes the agent cannot itself detect "is this production" from
 * the file alone, so the ansible role, Phase 6, is the actual enforcement
 * point). Local dev/CI override to 127.0.0.1 is expected and fine.
 */
public final class HealthServer {

    private static final Logger log = LoggerFactory.getLogger(HealthServer.class);

    private final HttpServer server;
    private final String bindAddress;
    private final int port;

    public HealthServer(Config config, HealthState healthState, Reconciler reconciler, Metrics metrics,
                         Instant startedAt) {
        this.bindAddress = config.http().bindAddress();
        this.port = config.http().port();

        URI uri = URI.create("http://" + bindAddress + ":" + port + "/");
        ResourceConfig resourceConfig = new ResourceConfig();
        resourceConfig.register(new HealthResource(healthState, config, reconciler, startedAt));
        resourceConfig.register(new MetricsResource(metrics.registry));
        resourceConfig.register(JacksonFeature.class);

        // Explicit 5-arg overload with start=false: some GrizzlyHttpServerFactory
        // overloads auto-start the server as a side effect of creation (the
        // 3-arg (uri, config, secure) form does), which would make this
        // class's start() ambiguous about whether it's starting the server
        // for the first time or double-starting it. Passing start=false here
        // guarantees start() below is the only place the server is started.
        this.server = GrizzlyHttpServerFactory.createHttpServer(uri, resourceConfig, false, null, false);
    }

    public void start() {
        try {
            server.start();
            log.info("health_server_started", kv("bind_address", bindAddress), kv("port", port));
        } catch (IOException e) {
            throw new IllegalStateException("failed to start HTTP server on " + bindAddress + ":" + port, e);
        }
    }

    public void stop() {
        if (server.isStarted()) {
            server.shutdownNow();
            log.info("health_server_stopped", kv("bind_address", bindAddress), kv("port", port));
        }
    }
}
