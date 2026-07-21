package io.homelab.dnsha.it;

import com.github.dockerjava.api.command.InspectContainerResponse;
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
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASES.md Phase 4 acceptance criteria: startup reconciliation, hang
 * detection + self-exit, and graceful SIGTERM shutdown.
 *
 * NOT EXECUTED / NOT VERIFIED IN THIS SANDBOX — same "no Docker daemon
 * available" constraint as every other *IT class in this repo (see
 * PROGRESS.md). Written from PHASES.md/BLUEPRINT.md §8 and from the
 * behaviour this session implemented in io.homelab.dnsha.lifecycle, but has
 * never actually been run. Do not treat a green checkmark here as proof
 * until it has been run against a real Docker daemon.
 *
 * Uses tailscale.mode=noop (same as EndToEndIT/PartitionIT — no real
 * Tailscale CLI/socket in a Testcontainers network) plus two Phase-4-only
 * test-only env hooks that exist SPECIFICALLY so these scenarios are
 * exercisable without real Tailscale/real crash-and-restart infra:
 *   - DNS_HA_AGENT_NOOP_SEED_ADVERTISED (NoopTailscaleClient) -- simulates
 *     "a previous instance crashed while still advertising the VIP" for
 *     staleVipAdvertisementIsWithdrawnAtStartup.
 *   - DNS_HA_AGENT_TEST_HANG_PROBE_LOOP (ProbeLoop) -- simulates a hung
 *     probe loop for watchdogForceExitsOnHungProbeLoop, per PHASES.md's own
 *     "inject Thread.sleep(Long.MAX_VALUE) into probe loop in a test build"
 *     wording.
 *
 * Reuses FakeAghContainer (same as EndToEndIT) as the probe target so the
 * agent actually reaches HEALTHY and advertises — without a real backend
 * DNS responder, probes would fail forever and none of these scenarios
 * (which all depend on first reaching an advertised state) could exercise
 * anything beyond STARTING.
 */
@Testcontainers
class LifecycleIT {

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

    /**
     * PHASES.md Phase 4 AC: "pre-advertise VIP manually, then start agent.
     * Agent's first action is to withdraw; it then goes through STARTING ->
     * HEALTHY before re-advertising. Log line 'found stale VIP
     * advertisement at startup' is present." Substitutes
     * DNS_HA_AGENT_NOOP_SEED_ADVERTISED for a real `tailscale set` (see
     * class javadoc) since this IT harness has no real Tailscale daemon.
     */
    @Test
    void staleVipAdvertisementIsWithdrawnAtStartup() {
        agent = startAgent(builder -> builder.withEnv("DNS_HA_AGENT_NOOP_SEED_ADVERTISED", "10.253.53.53/32"));

        agent.waitingFor(Wait.forLogMessage(".*\"action\":\"advertise\".*\"result\":\"success\".*\\n", 1)
            .withStartupTimeout(Duration.ofSeconds(10)));

        String logs = agent.getLogs();
        assertThat(logs).contains("found stale VIP advertisement at startup");
        // The withdraw-at-startup log line must appear BEFORE the eventual
        // re-advertise once probes pass -- i.e. it never inherited "already
        // advertising" as healthy.
        int withdrawIdx = logs.indexOf("found stale VIP advertisement at startup");
        int advertiseIdx = logs.indexOf("\"action\":\"advertise\"");
        assertThat(withdrawIdx).isGreaterThanOrEqualTo(0);
        assertThat(advertiseIdx).isGreaterThan(withdrawIdx);
    }

    /**
     * PHASES.md Phase 4 AC: "inject Thread.sleep(Long.MAX_VALUE) into probe
     * loop in a test build. Within 30s the WatchdogThread detects and the
     * process exits non-zero." Uses DNS_HA_AGENT_TEST_HANG_PROBE_LOOP (see
     * ProbeLoop javadoc) instead of a separate test build variant.
     */
    @Test
    void watchdogForceExitsOnHungProbeLoop() throws Exception {
        agent = startAgent(builder -> builder
            .withEnv("DNS_HA_AGENT_TEST_HANG_PROBE_LOOP", "true")
            // The agent still logs "dns_ha_agent_started" (unconditional,
            // happens before the probe-loop thread is dispatched), so the
            // default startAgent() waitingFor condition is unaffected by
            // the probe loop hanging forever right after that.
            .withStartupCheckStrategy(
                new org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy()));

        // maxProbeAge=30s + wakeInterval=10s worst case + container
        // teardown slack.
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(50).toMillis();
        Long exitCode = null;
        while (System.currentTimeMillis() < deadline) {
            InspectContainerResponse info = agent.getDockerClient().inspectContainerCmd(agent.getContainerId()).exec();
            if (Boolean.FALSE.equals(info.getState().getRunning())) {
                exitCode = info.getState().getExitCodeLong();
                break;
            }
            Thread.sleep(1000);
        }

        assertThat(exitCode).as("agent container should have self-exited within ~50s").isNotNull();
        assertThat(exitCode).isNotEqualTo(0L);
        assertThat(agent.getLogs()).contains("watchdog_probe_loop_hung");
    }

    /**
     * PHASES.md Phase 4 AC: "send SIGTERM. Agent withdraws within 5s and
     * exits 0."
     */
    @Test
    void sigtermWithdrawsWithinFiveSecondsAndExitsZero() throws Exception {
        agent = startAgent(builder -> builder);
        agent.waitingFor(Wait.forLogMessage(".*\"action\":\"advertise\".*\"result\":\"success\".*\\n", 1)
            .withStartupTimeout(Duration.ofSeconds(10)));

        long sentAt = System.currentTimeMillis();
        agent.getDockerClient().killContainerCmd(agent.getContainerId()).withSignal("TERM").exec();

        long deadline = sentAt + Duration.ofSeconds(10).toMillis();
        Long exitCode = null;
        while (System.currentTimeMillis() < deadline) {
            InspectContainerResponse info = agent.getDockerClient().inspectContainerCmd(agent.getContainerId()).exec();
            if (Boolean.FALSE.equals(info.getState().getRunning())) {
                exitCode = info.getState().getExitCodeLong();
                break;
            }
            Thread.sleep(200);
        }
        long tookMs = System.currentTimeMillis() - sentAt;

        assertThat(exitCode).as("agent container should have exited after SIGTERM").isNotNull();
        assertThat(exitCode).isEqualTo(0L);
        assertThat(tookMs).isLessThan(Duration.ofSeconds(10).toMillis()); // 5s budget + container-teardown slack
        assertThat(agent.getLogs()).contains("sigterm_withdraw_confirmed");
        assertThat(agent.getLogs()).contains("sigterm_graceful_shutdown_complete_exiting");
    }

    private GenericContainer<?> startAgent(UnaryOperator<GenericContainer<?>> customize) {
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
            configFile = Files.createTempFile("it-agent-lifecycle-config", ".yaml");
            Files.writeString(configFile, configYaml);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write IT agent config", e);
        }

        GenericContainer<?> container = new GenericContainer<>(
            new ImageFromDockerfile("dns-ha-agent-it/agent-lifecycle", false)
                .withDockerfile(Path.of("Dockerfile")))
            .withNetwork(network)
            .withEnv("DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE", "true")
            .withCopyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(configFile),
                "/etc/dns-ha-agent/config.yaml")
            .waitingFor(Wait.forLogMessage(".*dns_ha_agent_started.*\\n", 1)
                .withStartupTimeout(Duration.ofSeconds(30)));

        container = customize.apply(container);
        container.start();
        return container;
    }
}
