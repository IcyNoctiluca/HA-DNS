package io.homelab.dnsha.it;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.InternetProtocol;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Testcontainers wrapper around src/test/resources/fake-agh — a tiny
 * stdlib-only Python UDP DNS responder with an HTTP control API, per
 * BLUEPRINT.md §10 "FakeAghContainer".
 *
 * <p><b>Real bug found and fixed (2026-07-21, via static reading of the
 * Testcontainers/docker-java APIs and this class's own call sites, not a
 * live run — still no Docker in this sandbox):</b> this class used to
 * publish {@code DNS_PORT} (53/udp) to a <em>fixed</em> host port (53) via
 * a raw {@code withCreateContainerCmdModifier} + {@code PortBindings}
 * hack, on the theory that {@code GenericContainer#addExposedPorts} only
 * tracks TCP. That theory is correct (confirmed against the Testcontainers
 * 1.21.0 javadoc: {@code addExposedPort(Integer)} has no protocol
 * parameter; the only overload that accepts {@code InternetProtocol} is
 * the {@code protected} {@code addFixedExposedPort}, whose own javadoc
 * warns it is fixed-port and discouraged "as clashes or instability are
 * more likely"), but fixing it with a fixed host-port bind was the wrong
 * remedy for the actual problem. Grepping every {@code it/} test class
 * confirms {@code DNS_PORT} is <b>never</b> reached via
 * {@code getMappedPort()} — the agent under test always resolves against
 * the network alias (e.g. {@code target: fake-agh}) over the shared
 * Testcontainers {@link Network}, which is plain container-to-container
 * Docker networking and does not require the port to be exposed/published
 * to the host at all (that machinery — {@code EXPOSE}, {@code -p},
 * {@code ExposedPort}/{@code PortBinding} — only governs host-to-container
 * reachability; the fake-agh image's own {@code Dockerfile} already
 * declares {@code EXPOSE 53/udp} for documentation purposes). Binding a
 * *fixed* host port 53/udp was therefore both unnecessary and actively
 * broken: {@code EndToEndIT.backendProbeFailureDoesNotAffectFsmOrRouteMutation}
 * starts a second {@code FakeAghContainer} (aliased
 * {@code fake-agh-backup}) while the primary {@code fakeAgh} from
 * {@code @BeforeEach} is still running — both old instances would have
 * tried to bind the same fixed host port 53/udp, which Docker refuses
 * ("port is already allocated"), so that test would have failed to even
 * start its second container on the first real Docker run, unrelated to
 * anything the test is actually trying to exercise. Fixed by dropping the
 * fixed host-port publish entirely; {@code CONTROL_PORT} keeps using the
 * ordinary (non-fixed, auto-assigned) {@code addExposedPort} +
 * {@code getMappedPort()} pattern, which is host-reachable and collision-free
 * by construction since Testcontainers picks a free ephemeral host port per
 * container.
 */
public final class FakeAghContainer extends GenericContainer<FakeAghContainer> {

    public static final int DNS_PORT = 53;
    public static final int CONTROL_PORT = 8080;

    public FakeAghContainer(Network network, String networkAlias, String blockName) {
        super(new ImageFromDockerfile("dns-ha-agent-it/fake-agh", false)
            .withFileFromPath(".", Path.of("src/test/resources/fake-agh")));
        withNetwork(network);
        withNetworkAliases(networkAlias);
        withEnv("BLOCK_NAME", blockName);
        withEnv("RESOLVE_IP", "93.184.216.34");
        withEnv("BYPASS_IP", "203.0.113.99");

        // CONTROL_PORT is reached from the test JVM (the host) via
        // getMappedPort(), so it needs a real (auto-assigned) host
        // port mapping.
        addExposedPort(CONTROL_PORT);
        // DNS_PORT (53/udp) is reached only by other containers (the
        // agent under test) over the shared Testcontainers Network via
        // networkAlias — plain Docker container-to-container networking,
        // which needs no host port exposure/publishing at all. We still
        // register it as an exposed *container* port (no host binding)
        // purely so `docker inspect`/`getExposedPorts()` accurately
        // reflects what the container listens on; this is metadata only
        // and never claims a host port, so multiple instances (see
        // EndToEndIT's primary + backup FakeAgh) never collide.
        withCreateContainerCmdModifier(cmd -> {
            ExposedPort udpPort = new ExposedPort(DNS_PORT, InternetProtocol.UDP);
            cmd.withExposedPorts(concat(cmd.getExposedPorts(), udpPort));
        });

        // The test JVM talks to a remote DinD daemon, so its Docker host-port
        // forwarding can become reachable a little after the container itself
        // has started. Waiting through that mapping makes every IT fail with a
        // 30-second ContainerFetchException even though fake-agh is healthy.
        // Its own startup log is the direct readiness signal. setFilterBypassed
        // still exercises the mapped control port below where that access is
        // actually required.
        waitingFor(Wait.forLogMessage(
            ".*fake_agh control_server listening on :8080/tcp.*\\n", 1)
            .withStartupTimeout(Duration.ofSeconds(30)));
    }

    private static ExposedPort[] concat(ExposedPort[] existing, ExposedPort extra) {
        ExposedPort[] result = new ExposedPort[existing.length + 1];
        System.arraycopy(existing, 0, result, 0, existing.length);
        result[existing.length] = extra;
        return result;
    }

    /** Flips the sentinel/block-name response between NXDOMAIN and a real leaked IP. */
    public void setFilterBypassed(boolean bypassed) throws IOException {
        String mode = bypassed ? "bypassed" : "blocked";
        URI uri = URI.create("http://" + getHost() + ":" + getMappedPort(CONTROL_PORT) + "/control/filter?mode=" + mode);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code != 200) {
            throw new IOException("fake-agh control API returned " + code);
        }
    }
}
