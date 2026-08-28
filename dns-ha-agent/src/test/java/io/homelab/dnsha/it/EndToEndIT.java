package io.homelab.dnsha.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.regex.Pattern;

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
 * route mutations are observed via the agent's JSON stdout logs.
 *
 * Phase 2 additions (also NOT VERIFIED IN THIS SANDBOX — same no-Docker
 * caveat as above): healthEndpointMatchesSchema and
 * metricsEndpointExposesPrometheusFormat curl the agent's own /health and
 * /metrics over the exposed HEALTH_PORT; backendProbeFailureDoesNotAffectFsm
 * stands up a second FakeAgh as a backup backend and asserts BackendProbe
 * failures never drive a withdraw (PHASES.md Phase 2 acceptance: "BackendProbe
 * does not drive FSM").
 *
 * <p><b>Real bug found and fixed (2026-07-21, via static reading of the
 * Testcontainers API, not a live run — still no Docker in that sandbox):</b>
 * every test method used to call {@code agent.waitingFor(Wait.forLogMessage
 * (...))} AFTER {@code agent = startAgent()} had already returned an
 * already-started container. Per Testcontainers' own contract, a wait
 * strategy set via {@code waitingFor(...)} is only consulted during that
 * container's own {@code start()} call — setting it afterwards is a no-op
 * that does not retroactively block anything. So every one of these five
 * tests was racing the agent's probe/advertise cycle instead of actually
 * waiting for it: {@code buildAndStartAgentContainer}'s own wait strategy
 * (blocks for {@code dns_ha_agent_started}, logged at process boot, before
 * the first probe cycle even runs) was the only real synchronization point,
 * and every assertion after it depended on undefined extra delay to
 * coincidentally happen to land after the real advertise/HEALTHY line
 * appeared. {@code PartitionIT} (written after this class) already
 * independently discovered the same Testcontainers behaviour and used a
 * polling {@code waitForLogContains} helper instead — this class now uses
 * the equivalent ({@link #waitForLogMatches}, regex-based to preserve the
 * original two-field-on-one-line match) at every one of those five call
 * sites. This was a real correctness bug in the test suite itself (not
 * production code), silently relying on timing luck rather than an actual
 * synchronization guarantee — worth being aware of if any related IT test
 * ever flaked before this fix; it wasn't the config/threshold values being
 * wrong, it was never actually waiting.
 */
@Testcontainers
class EndToEndIT {

    private static final String BLOCK_NAME = "dns-filter-sentinel.example.com";
    private static final String FAKE_AGH_ALIAS = "fake-agh";
    private static final String FAKE_AGH_BACKUP_ALIAS = "fake-agh-backup";
    private static final int HEALTH_PORT = 8091;
    private static final Pattern ADVERTISE_SUCCESS_PATTERN =
        Pattern.compile("\"action\":\"advertise\".*\"result\":\"success\"");

    private final ObjectMapper jsonMapper = new ObjectMapper();

    private Network network;
    private FakeAghContainer fakeAgh;
    private FakeAghContainer fakeAghBackup;
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
        if (fakeAghBackup != null) {
            fakeAghBackup.stop();
        }
        fakeAgh.stop();
        network.close();
    }

    @Test
    void healthyProbeAdvertises() throws InterruptedException {
        agent = startAgent();

        // Phase 1 AC: transitions STARTING -> HEALTHY and calls advertise within 10s.
        waitForLogMatches(agent, ADVERTISE_SUCCESS_PATTERN, Duration.ofSeconds(10));

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
        waitForLogMatches(agent, ADVERTISE_SUCCESS_PATTERN, Duration.ofSeconds(10));

        fakeAgh.setFilterBypassed(true);

        // fail_threshold=2 * probe.interval=2s + a little slack.
        Thread.sleep(Duration.ofSeconds(8).toMillis());

        String logs = agent.getLogs();
        assertThat(logs).contains("\"action\":\"withdraw\"");
        assertThat(logs).contains("\"to\":\"UNHEALTHY\"");
    }

    /**
     * Phase 2 AC: "curl http://<tailscale_ip>:8091/health returns 200 +
     * valid JSON matching schema". No real Tailscale IP exists inside a
     * Testcontainers network, so this curls from INSIDE the agent's own
     * container via docker exec (wget is installed in the runtime image
     * specifically for this purpose — see Dockerfile) against
     * http.bind_address (127.0.0.1 in this IT config, same value the
     * container itself is bound to) — this does not require exposing the
     * port to the host or changing bind_address to 0.0.0.0, so it exercises
     * the real production bind-address code path unmodified.
     */
    @Test
    void healthEndpointMatchesSchema() throws Exception {
        agent = startAgent();
        waitForLogMatches(agent, ADVERTISE_SUCCESS_PATTERN, Duration.ofSeconds(10));

        JsonNode json = jsonMapper.readTree(curlInContainer(agent, "/health"));

        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("node").asText()).isEqualTo("it-agent");
        assertThat(json.get("region").asText()).isEqualTo("ci");
        assertThat(json.get("state").asText()).isEqualTo("HEALTHY");
        assertThat(json.get("vip_advertised").asBoolean()).isTrue();
        assertThat(json.get("desired_vip_advertised").asBoolean()).isTrue();
        assertThat(json.get("fail_open").asBoolean()).isFalse();
        assertThat(json.get("force_withdraw_active").asBoolean()).isFalse();
        assertThat(json.get("local_probe").get("resolve_check_ok").asBoolean()).isTrue();
        assertThat(json.get("local_probe").get("filter_check_ok").asBoolean()).isTrue();
        assertThat(json.get("backends").isArray()).isTrue();
        assertThat(json.get("peers").isArray()).isTrue();
        assertThat(json.get("peers")).isEmpty(); // this IT's config has peers: [] -- see PartitionIT for peer-populated cases
        assertThat(json.get("unreachable_peers")).isEmpty();
        assertThat(json.get("uptime_seconds").asLong()).isGreaterThanOrEqualTo(0);
    }

    /** Phase 2 AC: "curl http://<tailscale_ip>:8091/metrics returns 200 + valid Prometheus format." */
    @Test
    void metricsEndpointExposesPrometheusFormat() throws Exception {
        agent = startAgent();
        waitForLogMatches(agent, ADVERTISE_SUCCESS_PATTERN, Duration.ofSeconds(10));

        String body = curlInContainer(agent, "/metrics");

        assertThat(body).contains("dns_ha_agent_probe_total");
        assertThat(body).contains("dns_ha_agent_state{state=");
        assertThat(body).contains("dns_ha_agent_vip_advertised");
        assertThat(body).contains("dns_ha_agent_fail_open ");
        assertThat(body).contains("dns_ha_agent_route_mutations_total");
    }

    /**
     * PHASES.md Phase 2 AC: "BackendProbe does not drive FSM" — break
     * filtering on a backup backend only; the active-path FSM (driven
     * exclusively by DnsProbe against probe.target, i.e. the primary
     * fake-agh) must stay HEALTHY and the VIP must remain advertised, while
     * /health and the backend_probe_result logs still surface the backup's
     * broken filter.
     */
    @Test
    void backendProbeFailureDoesNotAffectFsmOrRouteMutation() throws Exception {
        fakeAghBackup = new FakeAghContainer(network, FAKE_AGH_BACKUP_ALIAS, BLOCK_NAME);
        fakeAghBackup.start();

        agent = startAgentWithBackupBackend();
        waitForLogMatches(agent, ADVERTISE_SUCCESS_PATTERN, Duration.ofSeconds(10));

        fakeAghBackup.setFilterBypassed(true); // ONLY the backup backend's filter breaks

        // backend_probe.interval=3s in startAgentWithBackupBackend() + slack.
        Thread.sleep(Duration.ofSeconds(6).toMillis());

        String logs = agent.getLogs();
        assertThat(logs).contains("\"backend\":\"" + FAKE_AGH_BACKUP_ALIAS + "\"");
        assertThat(logs).doesNotContain("\"to\":\"UNHEALTHY\"");
        assertThat(logs).doesNotContain("\"action\":\"withdraw\"");

        JsonNode json = jsonMapper.readTree(curlInContainer(agent, "/health"));
        assertThat(json.get("state").asText()).isEqualTo("HEALTHY");
        assertThat(json.get("vip_advertised").asBoolean()).isTrue();

        boolean backupFlaggedBroken = false;
        for (JsonNode backend : json.get("backends")) {
            if (backend.get("name").asText().equals(FAKE_AGH_BACKUP_ALIAS)) {
                backupFlaggedBroken = !backend.get("filter_ok").asBoolean();
            }
        }
        assertThat(backupFlaggedBroken).isTrue();
    }

    /**
     * Polls {@link GenericContainer#getLogs()} until a line matching
     * {@code pattern} appears or {@code timeout} elapses. Unlike calling
     * {@code container.waitingFor(...)} on an already-started container
     * (which is a documented no-op — Testcontainers wait strategies are
     * only consulted during that container's own {@code start()} — see this
     * class's javadoc for the real bug this replaced), this actually blocks
     * the calling thread. Same technique {@code PartitionIT} independently
     * arrived at for the same reason.
     */
    private static void waitForLogMatches(GenericContainer<?> container, Pattern pattern, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (pattern.matcher(container.getLogs()).find()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(
            "timed out after " + timeout + " waiting for log to match: " + pattern.pattern()
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

        return buildAndStartAgentContainer(configYaml);
    }

    private GenericContainer<?> startAgentWithBackupBackend() {
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
            backend_probe:
              enabled: true
              interval: 3s
              query_timeout: 2s
              backends:
                - name: %s
                  address: "%s:53"
                - name: %s
                  address: "%s:53"
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
            """.formatted(
                FAKE_AGH_ALIAS, BLOCK_NAME,
                FAKE_AGH_ALIAS, FAKE_AGH_ALIAS,
                FAKE_AGH_BACKUP_ALIAS, FAKE_AGH_BACKUP_ALIAS);

        return buildAndStartAgentContainer(configYaml);
    }

    private GenericContainer<?> buildAndStartAgentContainer(String configYaml) {
        Path configFile;
        try {
            configFile = Files.createTempFile("it-agent-config", ".yaml");
            Files.writeString(configFile, configYaml);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write IT agent config", e);
        }

        GenericContainer<?> container = new GenericContainer<>(
            new ImageFromDockerfile("dns-ha-agent-it/agent", false)
                .withDockerfile(Path.of("Dockerfile").toAbsolutePath()))
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
