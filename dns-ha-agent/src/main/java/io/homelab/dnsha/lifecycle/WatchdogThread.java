package io.homelab.dnsha.lifecycle;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.probe.ProbeLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * PHASES.md Phase 4 / BLUEPRINT.md §8 "Hang detection and self-exit".
 * Runs as its own thread (deliberately separate from the probe loop it is
 * watching — a hung probe loop must not also hang the thing detecting the
 * hang). Wakes every {@link #wakeInterval} and checks two independent
 * liveness signals:
 *
 * <ol>
 *   <li>{@link ProbeLoop#lastCycleCompletedAt()} — if the probe loop hasn't
 *       completed a cycle (success OR failure — any result counts) within
 *       {@link #maxProbeAge} (default 30s), the probe loop is presumed hung.
 *   <li>An HTTP GET to the agent's OWN {@code /health} endpoint, against
 *       {@code config.http().bindAddress()} — NOT {@code localhost} /
 *       {@code 127.0.0.1}. The agent binds to the node's Tailscale IP in
 *       production, so a loopback probe would falsely report the server
 *       down (BLUEPRINT.md §8 explicit warning). If this fails to return
 *       within {@link #httpTimeout} (default 5s) or returns a non-2xx
 *       status, same self-exit path.
 * </ol>
 *
 * Either failure logs a FATAL-shaped ERROR line and invokes
 * {@link #exitHandler} with exit code 70 (EX_SOFTWARE, matching
 * BLUEPRINT.md's literal "System.exit(70)"). exitHandler defaults to
 * {@code System::exit} in production; tests inject a no-op/recording
 * consumer via the package-visible full constructor so a unit test can
 * assert "would have exited" without actually killing the test JVM.
 *
 * Not synchronized/locked against anything — reads {@code lastCycleCompletedAt()}
 * (itself an AtomicReference read) and makes an independent HTTP call each
 * wake; no shared mutable state of its own beyond the run/stop flag.
 */
public final class WatchdogThread implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(WatchdogThread.class);

    static final Duration DEFAULT_WAKE_INTERVAL = Duration.ofSeconds(10);
    static final Duration DEFAULT_MAX_PROBE_AGE = Duration.ofSeconds(30);
    static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(5);
    static final int EXIT_CODE_HANG_DETECTED = 70; // EX_SOFTWARE

    private final ProbeLoop probeLoop;
    private final URI healthUri;
    private final Duration wakeInterval;
    private final Duration maxProbeAge;
    private final Duration httpTimeout;
    private final HttpClient httpClient;
    private final IntConsumer exitHandler;
    private final String nodeName;

    private volatile boolean running = true;

    public WatchdogThread(ProbeLoop probeLoop, Config config) {
        this(probeLoop, healthUriFor(config), DEFAULT_WAKE_INTERVAL, DEFAULT_MAX_PROBE_AGE, DEFAULT_HTTP_TIMEOUT,
            System::exit, config.node().name());
    }

    /** Package-visible full constructor for tests: injects every timing/URI/exit-behaviour knob. */
    WatchdogThread(ProbeLoop probeLoop, URI healthUri, Duration wakeInterval, Duration maxProbeAge,
                   Duration httpTimeout, IntConsumer exitHandler, String nodeName) {
        this.probeLoop = Objects.requireNonNull(probeLoop, "probeLoop");
        this.healthUri = Objects.requireNonNull(healthUri, "healthUri");
        this.wakeInterval = Objects.requireNonNull(wakeInterval, "wakeInterval");
        this.maxProbeAge = Objects.requireNonNull(maxProbeAge, "maxProbeAge");
        this.httpTimeout = Objects.requireNonNull(httpTimeout, "httpTimeout");
        this.exitHandler = Objects.requireNonNull(exitHandler, "exitHandler");
        this.nodeName = nodeName;
        this.httpClient = HttpClient.newBuilder().connectTimeout(httpTimeout).build();
    }

    /**
     * BLUEPRINT.md §8: "if a /health GET against the configured
     * http.bind_address fails ... Not localhost". Built once from config at
     * construction time (config is read-once at startup — AGENTS.md
     * invariant #10 — so this never needs to be recomputed).
     */
    static URI healthUriFor(Config config) {
        return URI.create("http://" + config.http().bindAddress() + ":" + config.http().port() + "/health");
    }

    @Override
    public void run() {
        log.info("watchdog_started", kv("node", nodeName),
            kv("wake_interval", wakeInterval.toString()), kv("max_probe_age", maxProbeAge.toString()),
            kv("health_uri", healthUri.toString()));
        while (running) {
            try {
                Thread.sleep(wakeInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("watchdog_interrupted", kv("node", nodeName));
                return;
            }
            if (!running) {
                return;
            }
            checkProbeLoopLiveness();
            checkHttpSelfMonitor();
        }
    }

    private void checkProbeLoopLiveness() {
        Instant last = probeLoop.lastCycleCompletedAt();
        Duration age = Duration.between(last, Instant.now());
        if (age.compareTo(maxProbeAge) > 0) {
            log.error("watchdog_probe_loop_hung",
                kv("node", nodeName), kv("age_seconds", age.getSeconds()), kv("max_age_seconds", maxProbeAge.getSeconds()));
            exitHandler.accept(EXIT_CODE_HANG_DETECTED);
        }
    }

    private void checkHttpSelfMonitor() {
        HttpRequest request = HttpRequest.newBuilder(healthUri).timeout(httpTimeout).GET().build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("watchdog_http_self_monitor_failed",
                    kv("node", nodeName), kv("uri", healthUri.toString()), kv("status", response.statusCode()));
                exitHandler.accept(EXIT_CODE_HANG_DETECTED);
            }
        } catch (IOException e) {
            log.error("watchdog_http_self_monitor_failed",
                kv("node", nodeName), kv("uri", healthUri.toString()), kv("error", e.toString()));
            exitHandler.accept(EXIT_CODE_HANG_DETECTED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        running = false;
    }
}
