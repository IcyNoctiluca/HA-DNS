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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /**
     * Phase 4 / BLUEPRINT.md §8 graceful-shutdown step 3: "Stop accepting
     * new HTTP requests; allow in-flight to complete (with 2s deadline)."
     * Unlike {@link #stop()} (immediate/forceful, used by the generic
     * best-effort shutdown path and by tests), this stops accepting new
     * connections immediately but gives in-flight requests up to
     * {@code drainTimeout} to finish before forcing a stop.
     *
     * <p><b>Open question, flagged not guessed past</b> (same category as
     * this class's other Grizzly-API assumption on the constructor's 5-arg
     * {@code createHttpServer} overload): this calls
     * {@code HttpServer#shutdown(long, TimeUnit)}, which per Grizzly's
     * documented API returns a {@code GrizzlyFuture<HttpServer>} that
     * completes once the grace period elapses or all in-flight requests
     * finish, whichever is first. This session has no working compiler for
     * this source (see PROGRESS.md) and could not verify the exact method
     * signature/behaviour against a real build. If wrong, this is the one
     * method designed to absorb that correction — falls back to the
     * known-working {@link #stop()} forceful path on any failure so a bad
     * assumption here degrades to "less graceful," not "shutdown hangs
     * forever."
     */
    public void stopGracefully(Duration drainTimeout) {
        if (!server.isStarted()) {
            return;
        }
        try {
            server.shutdown(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .get(drainTimeout.plus(Duration.ofSeconds(1)).toMillis(), TimeUnit.MILLISECONDS);
            log.info("health_server_stopped_gracefully",
                kv("bind_address", bindAddress), kv("port", port), kv("drain_timeout", drainTimeout.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.error("health_server_graceful_shutdown_failed_falling_back_to_immediate",
                kv("bind_address", bindAddress), kv("port", port), kv("error", e.toString()));
            server.shutdownNow();
        }
    }
}
