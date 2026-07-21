package io.homelab.dnsha.peer;

import com.sun.net.httpserver.HttpServer;
import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.PeerConfig;
import io.homelab.dnsha.config.SplitBrainConfig;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.state.FsmState;
import io.homelab.dnsha.state.HealthState;
import io.homelab.dnsha.state.PeerObservation;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises PeerMonitor against a real loopback HTTP server (JDK's
 * built-in com.sun.net.httpserver.HttpServer — no new test dependency, and
 * runnable without Docker/Testcontainers) standing in for a peer's
 * /health endpoint. Uses PeerMonitor.pollOnceForTest() so each test drives
 * exactly one deterministic poll cycle rather than racing a background
 * virtual thread against assertions.
 */
class PeerMonitorTest {

    private HttpServer server;
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"state\":\"HEALTHY\",\"unreachable_peers\":[]}");
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicInteger responseDelayMillis = new AtomicInteger(0);

    private HealthState healthState;
    private Metrics metrics;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            int delay = responseDelayMillis.get();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        metrics = new Metrics();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String healthUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/health";
    }

    private PeerMonitor newMonitor(Config config) {
        healthState = new HealthState(config);
        return new PeerMonitor(new PeerConfig("dns-peer-01", healthUrl()), config, healthState, metrics);
    }

    private Config configWithPeer() {
        return TestConfigs.minimalBuilder()
            .splitBrain(new SplitBrainConfig(true, Duration.ofSeconds(15), Duration.ofMillis(500), Duration.ofSeconds(60), 1))
            .peers(List.of(new PeerConfig("dns-peer-01", "http://placeholder/health")))
            .build();
    }

    @Test
    void successfulPollRecordsReachableAndReportedState() {
        responseBody.set("{\"state\":\"HEALTHY\",\"unreachable_peers\":[]}");
        PeerMonitor monitor = newMonitor(configWithPeer());

        monitor.pollOnceForTest();

        PeerObservation obs = healthState.snapshot().peerObservations().iterator().next();
        assertThat(obs.reachable()).isTrue();
        assertThat(obs.reportedState()).isEqualTo(FsmState.HEALTHY);
        assertThat(obs.reportsMeUnreachable()).isFalse();
        assertThat(obs.lastSeenTs()).isNotNull();
    }

    @Test
    void pollDetectsThisNodeInPeersUnreachableList() {
        // "test-node" is TestConfigs' fixed node().name() — see TestConfigs.Builder.build().
        responseBody.set("{\"state\":\"HEALTHY\",\"unreachable_peers\":[\"test-node\"]}");
        PeerMonitor monitor = newMonitor(configWithPeer());

        monitor.pollOnceForTest();

        PeerObservation obs = healthState.snapshot().peerObservations().iterator().next();
        assertThat(obs.reachable()).isTrue();
        assertThat(obs.reportsMeUnreachable()).isTrue();
        assertThat(obs.firstReportedUnreachableTs()).isNotNull();
    }

    @Test
    void pollIgnoresOtherNodesInPeersUnreachableList() {
        responseBody.set("{\"state\":\"HEALTHY\",\"unreachable_peers\":[\"some-other-node\"]}");
        PeerMonitor monitor = newMonitor(configWithPeer());

        monitor.pollOnceForTest();

        PeerObservation obs = healthState.snapshot().peerObservations().iterator().next();
        assertThat(obs.reportsMeUnreachable()).isFalse();
    }

    @Test
    void nonTwoHundredStatusRecordsUnreachable() {
        responseStatus.set(500);
        PeerMonitor monitor = newMonitor(configWithPeer());

        monitor.pollOnceForTest();

        PeerObservation obs = healthState.snapshot().peerObservations().iterator().next();
        assertThat(obs.reachable()).isFalse();
    }

    @Test
    void timeoutRecordsUnreachableAndPreservesLastKnownState() {
        responseBody.set("{\"state\":\"HEALTHY\",\"unreachable_peers\":[]}");
        Config config = configWithPeer(); // peer_poll_timeout=500ms
        PeerMonitor monitor = newMonitor(config);

        // First poll succeeds normally, establishing a known reportedState.
        monitor.pollOnceForTest();
        assertThat(healthState.snapshot().peerObservations().iterator().next().reachable()).isTrue();

        // Second poll: server now sleeps longer than the 500ms poll timeout.
        responseDelayMillis.set(2000);
        monitor.pollOnceForTest();

        PeerObservation obs = healthState.snapshot().peerObservations().iterator().next();
        assertThat(obs.reachable()).isFalse();
        // Carried forward from the prior successful poll, not nulled out.
        assertThat(obs.reportedState()).isEqualTo(FsmState.HEALTHY);
    }

    @Test
    void malformedJsonBodyRecordsUnreachable() {
        responseBody.set("not valid json{{{");
        PeerMonitor monitor = newMonitor(configWithPeer());

        monitor.pollOnceForTest();

        PeerObservation obs = healthState.snapshot().peerObservations().iterator().next();
        assertThat(obs.reachable()).isFalse();
    }

    @Test
    void unknownStateStringIsParsedAsNullReportedStateRatherThanThrowing() {
        responseBody.set("{\"state\":\"SOME_FUTURE_STATE\",\"unreachable_peers\":[]}");
        PeerMonitor monitor = newMonitor(configWithPeer());

        monitor.pollOnceForTest();

        PeerObservation obs = healthState.snapshot().peerObservations().iterator().next();
        assertThat(obs.reachable()).isTrue();
        assertThat(obs.reportedState()).isNull();
    }
}
