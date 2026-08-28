package io.homelab.dnsha.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASES.md Phase 3 acceptance criteria: the two-agent "asymmetric inbound"
 * and "fully isolated" partition scenarios.
 *
 * NOT EXECUTED / NOT VERIFIED IN THIS SANDBOX — same caveat as EndToEndIT
 * and FakeAghContainer (see their javadocs): there is no Docker daemon in
 * the environment this was written in, so none of this has been run.
 * Unlike those classes (written purely from the Testcontainers API surface
 * remembered/guessed at the time), the Toxiproxy usage here
 * (ToxiproxyContainer#getProxy(GenericContainer, int) returning a
 * ContainerProxy with getContainerIpAddress()/getProxyPort()/
 * setConnectionCut(boolean)) was checked against the published javadoc for
 * org.testcontainers:toxiproxy:1.19.7 (this project's pinned testcontainers
 * line) before being used — see build.gradle.kts's comment on the
 * dependency. That reduces API-guessing risk but does not substitute for
 * an actual run against Docker. Treat every assertion here as unverified
 * until it has been.
 *
 * Also NOTE: unlike EndToEndIT's existing pattern of calling
 * {@code agent.waitingFor(...)} AFTER {@code agent.start()} (which does not
 * retroactively block — Testcontainers wait strategies only apply to the
 * container's own startup), this class waits by polling
 * {@link GenericContainer#getLogs()} in a loop via
 * {@link #waitForLogContains}, which does actually block until the target
 * log line appears or the timeout elapses.
 *
 * Topology: two agent containers (agent-a, agent-b) share one FakeAgh
 * backend for local DNS probing — this suite exercises peer-to-peer
 * partition logic, not local probing, so a shared backend is sufficient.
 * Each agent's peer entry for the OTHER agent is routed through a
 * dedicated Toxiproxy proxy (proxy-to-a fronts agent-a:8091, proxy-to-b
 * fronts agent-b:8091) instead of a direct network alias, so either
 * direction of A&lt;-&gt;B peer-poll traffic can be cut independently: the
 * asymmetric-inbound test cuts proxy-to-a only (B can no longer poll A;
 * A can still poll B directly through proxy-to-b), and the fully-isolated
 * test cuts both.
 */
@Testcontainers
class PartitionIT {

    private static final String BLOCK_NAME = "dns-filter-sentinel.example.com";
    private static final String FAKE_AGH_ALIAS = "fake-agh";
    private static final String AGENT_A_ALIAS = "agent-a";
    private static final String AGENT_B_ALIAS = "agent-b";
    private static final int HEALTH_PORT = 8091;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    private Network network;
    private FakeAghContainer fakeAgh;
    private ToxiproxyContainer toxiproxy;
    private GenericContainer<?> agentA;
    private GenericContainer<?> agentB;
    private ToxiproxyContainer.ContainerProxy proxyToA;
    private ToxiproxyContainer.ContainerProxy proxyToB;

    @BeforeEach
    void setUp() throws IOException {
        network = Network.newNetwork();
        fakeAgh = new FakeAghContainer(network, FAKE_AGH_ALIAS, BLOCK_NAME);
        fakeAgh.start();

        toxiproxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
            .withNetwork(network);
        toxiproxy.start();
    }

    @AfterEach
    void tearDown() {
        if (agentA != null) {
            agentA.stop();
        }
        if (agentB != null) {
            agentB.stop();
        }
        toxiproxy.stop();
        fakeAgh.stop();
        network.close();
    }

    @Test
    void asymmetricInboundPartitionTriggersWithdrawOnTheUnreachableSide() throws Exception {
        startBothAgents();
        waitForLogContains(agentA, "\"action\":\"advertise\"", Duration.ofSeconds(10));
        waitForLogContains(agentB, "\"action\":\"advertise\"", Duration.ofSeconds(10));

        // Cut B's ability to reach A. A's ability to reach B is untouched.
        proxyToA.setConnectionCut(true);

        // peer_poll_interval=2s + peer_unreachable_threshold=3s (see the
        // config template in attachConfigAndStart) + slack for a couple of
        // failed polls to register and the withdraw to land.
        Thread.sleep(Duration.ofSeconds(10).toMillis());

        JsonNode aHealth = jsonMapper.readTree(curlInContainer(agentA, "/health"));
        assertThat(aHealth.get("state").asText()).isEqualTo("PARTITIONED");
        assertThat(aHealth.get("vip_advertised").asBoolean()).isFalse();
        assertThat(unreachablePeerNames(aHealth)).doesNotContain(AGENT_B_ALIAS);

        String aLogs = agentA.getLogs();
        assertThat(aLogs).contains("\"to\":\"PARTITIONED\"");
        assertThat(aLogs).contains("\"action\":\"withdraw\"");
    }

    @Test
    void fullyIsolatedNodeStaysHealthyNotCoveredByPartitionDetection() throws Exception {
        startBothAgents();
        waitForLogContains(agentA, "\"action\":\"advertise\"", Duration.ofSeconds(10));
        waitForLogContains(agentB, "\"action\":\"advertise\"", Duration.ofSeconds(10));

        // Cut both directions: neither agent can reach the other at all.
        proxyToA.setConnectionCut(true);
        proxyToB.setConnectionCut(true);

        Thread.sleep(Duration.ofSeconds(10).toMillis());

        JsonNode aHealth = jsonMapper.readTree(curlInContainer(agentA, "/health"));
        // BLUEPRINT.md §4: "stay HEALTHY if local probes pass but all
        // peers are unreachable, because there is no quorum to declare
        // partition" -- explicitly NOT covered by PARTITIONED. A single
        // node with no peer able to corroborate "I am unreachable" must
        // not self-withdraw, or a plain network blip that also happens to
        // sever peer connectivity would take DNS down for no local reason.
        assertThat(aHealth.get("state").asText()).isEqualTo("HEALTHY");
        assertThat(aHealth.get("vip_advertised").asBoolean()).isTrue();
        // A does notice B is unreachable from its own side...
        assertThat(unreachablePeerNames(aHealth)).contains(AGENT_B_ALIAS);
        // ...but that alone (with no peer to corroborate it) must never trigger PARTITIONED.
        assertThat(agentA.getLogs()).doesNotContain("\"to\":\"PARTITIONED\"");
    }

    private static java.util.List<String> unreachablePeerNames(JsonNode health) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonNode n : health.get("unreachable_peers")) {
            names.add(n.asText());
        }
        return names;
    }

    private void waitForLogContains(GenericContainer<?> container, String needle, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (container.getLogs().contains(needle)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(
            "timed out after " + timeout + " waiting for log to contain: " + needle
                + "\nlogs so far:\n" + container.getLogs());
    }

    private String curlInContainer(GenericContainer<?> container, String path) throws Exception {
        var execResult = container.execInContainer("wget", "-qO-", "http://127.0.0.1:" + HEALTH_PORT + path);
        if (execResult.getExitCode() != 0) {
            throw new IllegalStateException(
                "wget " + path + " failed (exit " + execResult.getExitCode() + "): " + execResult.getStderr());
        }
        return execResult.getStdout();
    }

    /**
     * Builds both (unstarted) agent containers, registers a Toxiproxy
     * proxy in front of each one's :8091 (registering a proxy is expected
     * to only require the target container's configured network alias,
     * not for it to be running yet -- see class javadoc on why this is
     * flagged, not assumed silently), writes each agent's config.yaml with
     * its peer entry pointing at the OTHER agent's proxy, then starts both.
     */
    private void startBothAgents() {
        agentA = newUnstartedAgentContainer(AGENT_A_ALIAS);
        agentB = newUnstartedAgentContainer(AGENT_B_ALIAS);

        proxyToA = toxiproxy.getProxy(agentA, HEALTH_PORT);
        proxyToB = toxiproxy.getProxy(agentB, HEALTH_PORT);

        String aHealthUrlViaProxy = "http://" + proxyToA.getContainerIpAddress() + ":" + proxyToA.getProxyPort() + "/health";
        String bHealthUrlViaProxy = "http://" + proxyToB.getContainerIpAddress() + ":" + proxyToB.getProxyPort() + "/health";

        attachConfigAndStart(agentA, AGENT_A_ALIAS, AGENT_B_ALIAS, bHealthUrlViaProxy);
        attachConfigAndStart(agentB, AGENT_B_ALIAS, AGENT_A_ALIAS, aHealthUrlViaProxy);
    }

    private GenericContainer<?> newUnstartedAgentContainer(String alias) {
        return new GenericContainer<>(
            new ImageFromDockerfile("dns-ha-agent-it/agent", false)
                .withDockerfile(Path.of("Dockerfile").toAbsolutePath()))
            .withNetwork(network)
            .withNetworkAliases(alias)
            .withEnv("DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE", "true")
            .waitingFor(Wait.forLogMessage(".*dns_ha_agent_started.*\\n", 1).withStartupTimeout(Duration.ofSeconds(30)));
    }

    private void attachConfigAndStart(GenericContainer<?> agent, String nodeName, String peerName, String peerHealthUrl) {
        String configYaml = """
            version: 1
            node: { name: %s, region: ci, vip: 10.253.53.53/32 }
            probe:
              target: %s
              resolve_names: [cloudflare.com]
              resolve_type: A
              block_name: %s
              block_expectation:
                mode: must_not_return_real_ip
                allowed_rcodes: [NXDOMAIN, REFUSED, NOERROR]
                allowed_ips: ["0.0.0.0"]
              interval: 2s
              query_timeout: 2s
              fail_threshold: 2
              success_threshold: 3
            peers:
              - name: %s
                health_url: %s
            split_brain:
              enabled: true
              peer_poll_interval: 2s
              peer_poll_timeout: 2s
              peer_unreachable_threshold: 3s
              min_peer_reports_to_self_withdraw: 1
            tailscale:
              mode: noop
              cli_path: /usr/bin/tailscale
              managed_routes: [10.253.53.53/32]
              preserved_routes: []
              command_timeout: 10s
            http: { bind_address: 127.0.0.1, port: 8091 }
            logging: { format: JSON, level: INFO }
            metrics: { enabled: true, path: /metrics }
            """.formatted(nodeName, FAKE_AGH_ALIAS, BLOCK_NAME, peerName, peerHealthUrl);

        Path configFile;
        try {
            configFile = Files.createTempFile("it-" + nodeName + "-config", ".yaml");
            Files.writeString(configFile, configYaml);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write IT agent config for " + nodeName, e);
        }
        agent.withCopyFileToContainer(MountableFile.forHostPath(configFile), "/etc/dns-ha-agent/config.yaml");
        agent.start();
    }
}
