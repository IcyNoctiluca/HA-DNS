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
 * Jersey-over-Grizzly HTTP server exposing /health and (unless
 * {@code metrics.enabled: false}, see {@link #buildResourceConfig}) /metrics
 * (BLUEPRINT.md §6, §9). Binds to config.http().bind_address():port() —
 * IMPORTANT: in production this MUST be the node's Tailscale IP, never
 * 0.0.0.0 or 127.0.0.1 (top-level AGENTS.md invariant #8; HttpConfig's
 * javadoc notes the agent cannot itself detect "is this production" from
 * the file alone, so the infrastructure consumer is the actual enforcement
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
        ResourceConfig resourceConfig = buildResourceConfig(config, healthState, reconciler, metrics, startedAt);

        // Explicit 5-arg overload with start=false: some GrizzlyHttpServerFactory
        // overloads auto-start the server as a side effect of creation (the
        // 3-arg (uri, config, secure) form does), which would make this
        // class's start() ambiguous about whether it's starting the server
        // for the first time or double-starting it. Passing start=false here
        // guarantees start() below is the only place the server is started.
        this.server = GrizzlyHttpServerFactory.createHttpServer(uri, resourceConfig, false, null, false);
    }

    /**
     * Builds the Jersey {@link ResourceConfig} for this server. Package-visible
     * (rather than inlined in the constructor) specifically so a unit test can
     * assert on which resources get registered without starting a real Grizzly
     * server / binding a socket — see {@code HealthServerTest}.
     *
     * <p><b>Real bug fixed 2026-07-23</b>: {@code MetricsResource} was
     * registered unconditionally here, so {@code config.metrics().enabled()}
     * — a documented, per-node YAML knob (BLUEPRINT.md §5) — had no effect
     * whatsoever: setting it to {@code false} (or omitting the key from an
     * otherwise-present {@code metrics:} block, which Jackson defaults to the
     * primitive {@code false} rather than the record's {@code true} default
     * that only applies when the whole section is absent) still left
     * {@code /metrics} fully served. Same "config field threaded through
     * convincingly but silently doesn't reach the code that should honor it"
     * shape as the {@code probe.resolve_type} and {@code tailscale.socket_path}
     * findings earlier in PROGRESS.md. Fixed by actually consulting the flag
     * here. Every currently-committed config (ansible template, CI YAML,
     * BLUEPRINT.md example) sets {@code enabled: true} explicitly, so this
     * was never live-broken for the one documented deployment target, but it
     * silently defeated the one way an operator could ever ask for
     * {@code /metrics} to not be exposed on a given node.
     *
     * <p>{@code config.metrics().path()} is NOT similarly wired: JAX-RS
     * {@code @Path} on {@link MetricsResource} is a compile-time-static
     * annotation, so honoring an arbitrary configured path would need a
     * dynamic resource-binding mechanism (e.g. a hand-rolled
     * {@code ResourceConfig} filter or a second endpoint class), which is out
     * of scope for this fix. Rather than leave that as a second silent no-op,
     * {@code ConfigLoader.validate()} now rejects any {@code metrics.path}
     * other than the literal {@code /metrics} at config-load time — see its
     * javadoc.
     */
    static ResourceConfig buildResourceConfig(Config config, HealthState healthState, Reconciler reconciler,
                                               Metrics metrics, Instant startedAt) {
        ResourceConfig resourceConfig = new ResourceConfig();
        resourceConfig.register(new HealthResource(healthState, config, reconciler, startedAt));
        if (config.metrics().enabled()) {
            resourceConfig.register(new MetricsResource(metrics.registry));
        } else {
            log.info("metrics_endpoint_disabled", kv("node", config.node().name()));
        }
        resourceConfig.register(JacksonFeature.class);
        return resourceConfig;
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
     * <p><b>API-shape questions flagged by earlier sessions, now confirmed
     * against upstream source</b> (2026-07-21, no compiler was available so
     * this was verified by reading the actual dependency source rather than
     * compiling against it — see PROGRESS.md for the session that did this):
     * both this method's {@code HttpServer#shutdown(long, TimeUnit)} call
     * and the constructor's 5-arg {@code GrizzlyHttpServerFactory
     * .createHttpServer(uri, resourceConfig, secure, sslEngineConfigurator,
     * start)} overload were checked directly against
     * {@code eclipse-ee4j/glassfish-grizzly}'s {@code HttpServer.java}
     * (method signature: {@code public synchronized GrizzlyFuture<HttpServer>
     * shutdown(final long gracePeriod, final TimeUnit timeUnit)}, and
     * {@code GrizzlyFuture<V>} extends {@code java.util.concurrent.Future<V>},
     * so the {@code .get(timeout, unit)} call below is valid) and against
     * Jersey's published {@code GrizzlyHttpServerFactory} javadoc for the
     * 5-arg overload used in the constructor. Both match this class's usage
     * exactly. This resolves the "does this API shape even exist" risk;
     * runtime behaviour (does the drain actually happen cleanly against a
     * real container) is still unverified and needs a real Docker run
     * (`LifecycleIT`/Phase 7), same as before. Falls back to the
     * known-working {@link #stop()} forceful path on any failure regardless,
     * so a wrong assumption here would still degrade to "less graceful," not
     * "shutdown hangs forever."
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
