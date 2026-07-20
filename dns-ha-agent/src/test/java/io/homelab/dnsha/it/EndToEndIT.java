package io.homelab.dnsha.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 integration tests, per BLUEPRINT.md §10 and PHASES.md Phase 1
 * acceptance criteria: "EndToEndIT.healthyProbeAdvertises" and
 * "EndToEndIT.filterBreakWithdraws".
 *
 * NOT EXECUTED / NOT VERIFIED IN THIS SANDBOX: there is no Docker daemon
 * available in the environment this was written in (no `docker` binary at
 * all — see PROGRESS.md). This class is written from the spec and from
 * Testcontainers/docker-java documentation, but has never actually been
 * run. Do not treat a green checkmark on this class as proof it works
 * until it has been run against a real Docker daemon (e.g. in Forgejo CI,
 * once Phase 5 wires that up, or by a developer locally).
 *
 * The agent runs tailscale.mode=noop (NoopTailscaleClient) — no real
 * Tailscale CLI/socket is involved. HEALTHY/UNHEALTHY transitions and
 * route mutations are observed via the agent's JSON stdout logs (Phase 1
 * has no /health endpoint yet — that's Phase 2).
 */
@Testcontainers
class EndToEndIT {

    private static final String BLOCK_NAME = "dns-filter-sentinel.example.com";
    private static final String FAKE_AGH_ALIAS = "fake-agh";

    private Network network;
    private FakeAghContainer fakeAgh;
    private GenericContainer<?> agent;

    @BeforeEach
    void setUp() throws IOException {
        network = Network.newNetwork();
        fakeAgh = new FakeAghContainer(network, FAKE_AGH_ALIAS, BLOCK_NAME);
        fakeAgh.start();
    }

    @AfterEach
    void tearDown() {
        if (agent != null) {
            agent.stop();
        }
        fakeAgh.stop();
        network.close();
    }

    @Test
    void healthyProbeAdvertises() {
        agent = startAgent();

        // Phase 1 AC: transitions STARTING -> HEALTHY and calls advertise within 10s.
        agent.waitingFor(Wait.forLogMessage(".*\"action\":\"advertise\".*\"result\":\"success\".*\\n", 1)
            .withStartupTimeout(Duration.ofSeconds(10)));

        String logs = agent.getLogs();
        assertThat(logs).contains("\"action\":\"advertise\"");
        assertThat(logs).contains("\"result\":\"success\"");
    }

    @Test
    void filterBreakWithdraws() throws Exception {
        agent = startAgent();

        // Let it reach HEALTHY (and therefore advertise) first, same as
        // healthyProbeAdvertises, before breaking the filter — otherwise
        // there is nothing to withdraw (STARTING's desired route set is
        // already "nothing advertised").
        agent.waitingFor(Wait.forLogMessage(".*\"action\":\"advertise\".*\"result\":\"success\".*\\n", 1)
            .withStartupTimeout(Duration.ofSeconds(10)));

        fakeAgh.setFilterBypassed(true);

        // fail_threshold=2 * probe.interval=2s + a little slack.
        Thread.sleep(Duration.ofSeconds(8).toMillis());

        String logs = agent.getLogs();
        assertThat(logs).contains("\"action\":\"withdraw\"");
        assertThat(logs).contains("\"to\":\"UNHEALTHY\"");
    }

    private GenericContainer<?> startAgent() {
        String configYaml = """
            version: 1
            node: { name: it-agent, region: ci, vip: 10.253.53.53/32 }
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
            peers: []
            split_brain: { enabled: false }
            tailscale:
              mode: noop
              cli_path: /usr/bin/tailscale
              managed_routes: [10.253.53.53/32]
              preserved_routes: []
              command_timeout: 10s
            http: { bind_address: 127.0.0.1, port: 8091 }
            logging: { format: JSON, level: INFO }
            metrics: { enabled: true, path: /metrics }
            """.formatted(FAKE_AGH_ALIAS, BLOCK_NAME);

        Path configFile;
        try {
            configFile = Files.createTempFile("it-agent-config", ".yaml");
            Files.writeString(configFile, configYaml);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write IT agent config", e);
        }

        GenericContainer<?> container = new GenericContainer<>(
            new ImageFromDockerfile("dns-ha-agent-it/agent", false)
                .withDockerfile(Path.of("Dockerfile")))
            .withNetwork(network)
            .withEnv("DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE", "true")
            .withCopyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(configFile),
                "/etc/dns-ha-agent/config.yaml")
            .waitingFor(Wait.forLogMessage(".*dns_ha_agent_started.*\\n", 1)
                .withStartupTimeout(Duration.ofSeconds(30)));

        container.start();
        return container;
    }
}
