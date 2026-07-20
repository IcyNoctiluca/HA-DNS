package io.homelab.dnsha.it;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.InternetProtocol;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
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
 * NOT VERIFIED IN THIS SANDBOX: there is no Docker daemon available in the
 * environment this class was written in, so none of the Testcontainers
 * wiring below — in particular the manual UDP ExposedPort/PortBinding
 * setup, since GenericContainer#addExposedPorts only handles TCP by
 * default — has been exercised against a real Docker daemon. Treat this
 * as a best-effort implementation written from Testcontainers/docker-java
 * API documentation, not as confirmed-working code. See PROGRESS.md.
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

        // addExposedPorts() only tracks TCP; UDP needs the raw docker-java
        // create-container command modified directly.
        addExposedPort(CONTROL_PORT);
        withCreateContainerCmdModifier(cmd -> {
            ExposedPort udpPort = new ExposedPort(DNS_PORT, InternetProtocol.UDP);
            Ports portBindings = cmd.getHostConfig().getPortBindings();
            if (portBindings == null) {
                portBindings = new Ports();
            }
            portBindings.bind(udpPort, PortBinding.parse(":" + DNS_PORT).getBinding());
            cmd.getHostConfig().withPortBindings(portBindings);
            cmd.withExposedPorts(concat(cmd.getExposedPorts(), udpPort));
        });

        waitingFor(Wait.forHttp("/").forPort(CONTROL_PORT).withStartupTimeout(Duration.ofSeconds(30)));
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
