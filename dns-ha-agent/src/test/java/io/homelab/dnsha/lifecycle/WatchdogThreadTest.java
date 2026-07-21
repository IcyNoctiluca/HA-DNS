package io.homelab.dnsha.lifecycle;

import com.sun.net.httpserver.HttpServer;
import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.HttpConfig;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.probe.DnsProbe;
import io.homelab.dnsha.probe.ProbeLoop;
import io.homelab.dnsha.state.HealthState;
import io.homelab.dnsha.tailscale.NoopTailscaleClient;
import io.homelab.dnsha.tailscale.Reconciler;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASES.md Phase 4: WatchdogThread. Uses a REAL loopback
 * com.sun.net.httpserver.HttpServer (JDK-bundled, no Docker/Testcontainers
 * needed) for the HTTP self-monitor checks, and a real (never-started)
 * ProbeLoop whose lastCycleCompletedAt() naturally ages past maxProbeAge
 * for the probe-hang checks -- both exercised as real end-to-end behaviour
 * rather than mocks of WatchdogThread's own internals.
 */
class WatchdogThreadTest {

    private final List<HttpServer> serversToStop = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        serversToStop.forEach(s -> s.stop(0));
        serversToStop.clear();
    }

    @Test
    void healthUriForUsesConfiguredBindAddressNotLocalhost() {
        Config config = configWithHttp("10.253.53.9", 9091);
        URI uri = WatchdogThread.healthUriFor(config);

        assertThat(uri.getHost()).isEqualTo("10.253.53.9");
        assertThat(uri.getPort()).isEqualTo(9091);
        assertThat(uri.getPath()).isEqualTo("/health");
        assertThat(uri.toString()).doesNotContain("localhost").doesNotContain("127.0.0.1");
    }

    @Test
    void probeLoopStalenessTriggersExit() throws Exception {
        Config config = configWithHttp("127.0.0.1", freePort());
        ProbeLoop probeLoop = newUnstartedProbeLoop(config);
        HttpServer healthyServer = startTrivialHealthServer(config, 200);

        CountDownLatch exited = new CountDownLatch(1);
        List<Integer> exitCodes = new CopyOnWriteArrayList<>();
        WatchdogThread watchdog = new WatchdogThread(
            probeLoop,
            WatchdogThread.healthUriFor(config),
            Duration.ofMillis(30),   // wake interval
            Duration.ofMillis(50),   // max probe age -- ProbeLoop is never run, so its
                                      // lastCycleCompletedAt() never advances past construction time
            Duration.ofSeconds(2),
            code -> {
                exitCodes.add(code);
                exited.countDown();
            },
            "test-node");

        Thread t = Thread.ofVirtual().start(watchdog);
        try {
            assertThat(exited.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(exitCodes).contains(70);
        } finally {
            watchdog.stop();
            t.interrupt();
            healthyServer.stop(0);
        }
    }

    @Test
    void healthyHttpSelfMonitorDoesNotTriggerExit() throws Exception {
        Config config = configWithHttp("127.0.0.1", freePort());
        ProbeLoop probeLoop = newUnstartedProbeLoop(config);
        HttpServer healthyServer = startTrivialHealthServer(config, 200);

        CountDownLatch exited = new CountDownLatch(1);
        WatchdogThread watchdog = new WatchdogThread(
            probeLoop,
            WatchdogThread.healthUriFor(config),
            Duration.ofMillis(50),
            Duration.ofSeconds(30), // long max probe age -- only exercising the HTTP path here
            Duration.ofSeconds(2),
            code -> exited.countDown(),
            "test-node");

        Thread t = Thread.ofVirtual().start(watchdog);
        try {
            // Give it a couple of wake cycles against the healthy server.
            assertThat(exited.await(300, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            watchdog.stop();
            t.interrupt();
            healthyServer.stop(0);
        }
    }

    @Test
    void failingHttpSelfMonitorTriggersExit() throws Exception {
        Config config = configWithHttp("127.0.0.1", freePort());
        ProbeLoop probeLoop = newUnstartedProbeLoop(config);
        HttpServer failingServer = startTrivialHealthServer(config, 503);

        CountDownLatch exited = new CountDownLatch(1);
        List<Integer> exitCodes = new CopyOnWriteArrayList<>();
        WatchdogThread watchdog = new WatchdogThread(
            probeLoop,
            WatchdogThread.healthUriFor(config),
            Duration.ofMillis(30),
            Duration.ofSeconds(30), // long max probe age -- only exercising the HTTP path here
            Duration.ofSeconds(2),
            code -> {
                exitCodes.add(code);
                exited.countDown();
            },
            "test-node");

        Thread t = Thread.ofVirtual().start(watchdog);
        try {
            assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(exitCodes).contains(70);
        } finally {
            watchdog.stop();
            t.interrupt();
            failingServer.stop(0);
        }
    }

    @Test
    void unreachableHttpSelfMonitorTriggersExit() throws Exception {
        int deadPort = freePort(); // nothing bound here at all
        Config config = configWithHttp("127.0.0.1", deadPort);
        ProbeLoop probeLoop = newUnstartedProbeLoop(config);

        CountDownLatch exited = new CountDownLatch(1);
        WatchdogThread watchdog = new WatchdogThread(
            probeLoop,
            WatchdogThread.healthUriFor(config),
            Duration.ofMillis(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(1),
            code -> exited.countDown(),
            "test-node");

        Thread t = Thread.ofVirtual().start(watchdog);
        try {
            assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            watchdog.stop();
            t.interrupt();
        }
    }

    private static Config configWithHttp(String bindAddress, int port) {
        Config base = TestConfigs.minimal();
        return new Config(
            base.version(), base.node(), base.probe(), base.backendProbe(), base.peers(), base.splitBrain(),
            base.tailscale(), new HttpConfig(bindAddress, port), base.logging(), base.metrics());
    }

    private static ProbeLoop newUnstartedProbeLoop(Config config) {
        DnsProbe probe = new DnsProbe(config.probe());
        HealthState healthState = new HealthState(config);
        Reconciler reconciler = new Reconciler(new NoopTailscaleClient(), new Metrics(), config);
        // Never started (no Thread.start) -- lastCycleCompletedAt() stays
        // fixed at construction time, which is exactly the "hung" scenario
        // this test needs, without actually hanging a real thread forever.
        return new ProbeLoop(probe, healthState, config, reconciler, new Metrics());
    }

    private HttpServer startTrivialHealthServer(Config config, int statusCode) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.http().bindAddress(), config.http().port()), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        serversToStop.add(server);
        return server;
    }

    private static int freePort() throws IOException {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
