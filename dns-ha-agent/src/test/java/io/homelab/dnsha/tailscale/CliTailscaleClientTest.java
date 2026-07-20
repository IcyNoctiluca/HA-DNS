package io.homelab.dnsha.tailscale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises CliTailscaleClient against a fake shell script standing in for
 * the `tailscale` binary — AGENTS.md explicitly forbids subprocessing the
 * REAL tailscale CLI in tests. See AGENTS.md "Build / test / push
 * discipline".
 */
class CliTailscaleClientTest {

    @TempDir
    Path tempDir;

    private Path writeFakeCli(String script) throws IOException {
        Path path = tempDir.resolve("fake-tailscale.sh");
        Files.writeString(path, script);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        return path;
    }

    @Test
    void advertiseRoutesInvokesCorrectArgs() throws Exception {
        Path argsCapture = tempDir.resolve("captured-args.txt");
        Path cli = writeFakeCli("""
            #!/bin/sh
            echo "$@" > "%s"
            exit 0
            """.formatted(argsCapture));

        CliTailscaleClient client = new CliTailscaleClient(cli.toString(), Duration.ofSeconds(5));
        client.advertiseRoutes(Set.of("10.253.53.53/32"));

        String captured = Files.readString(argsCapture).trim();
        assertThat(captured).isEqualTo("set --advertise-routes=10.253.53.53/32");
    }

    @Test
    void advertiseRoutesSortsMultipleRoutesForDeterministicArgs() throws Exception {
        Path argsCapture = tempDir.resolve("captured-args.txt");
        Path cli = writeFakeCli("""
            #!/bin/sh
            echo "$@" > "%s"
            exit 0
            """.formatted(argsCapture));

        CliTailscaleClient client = new CliTailscaleClient(cli.toString(), Duration.ofSeconds(5));
        client.advertiseRoutes(Set.of("10.253.53.53/32", "10.10.0.0/24"));

        String captured = Files.readString(argsCapture).trim();
        assertThat(captured).isEqualTo("set --advertise-routes=10.10.0.0/24,10.253.53.53/32");
    }

    @Test
    void nonZeroExitThrowsTailscaleException() throws Exception {
        Path cli = writeFakeCli("""
            #!/bin/sh
            echo "boom: permission denied" >&2
            exit 1
            """);

        CliTailscaleClient client = new CliTailscaleClient(cli.toString(), Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.advertiseRoutes(Set.of("10.253.53.53/32")))
            .isInstanceOf(TailscaleException.class)
            .hasMessageContaining("exited 1")
            .hasMessageContaining("permission denied");
    }

    @Test
    void commandTimeoutThrowsTailscaleException() throws Exception {
        Path cli = writeFakeCli("""
            #!/bin/sh
            sleep 5
            exit 0
            """);

        CliTailscaleClient client = new CliTailscaleClient(cli.toString(), Duration.ofMillis(200));

        assertThatThrownBy(() -> client.advertiseRoutes(Set.of("10.253.53.53/32")))
            .isInstanceOf(TailscaleException.class)
            .hasMessageContaining("timed out");
    }

    @Test
    void currentlyAdvertisedParsesAdvertiseRoutesFromDebugPrefsJson() throws Exception {
        Path cli = writeFakeCli("""
            #!/bin/sh
            echo '{"AdvertiseRoutes":["10.253.53.53/32","10.10.0.0/24"],"Other":"ignored"}'
            exit 0
            """);

        CliTailscaleClient client = new CliTailscaleClient(cli.toString(), Duration.ofSeconds(5));
        Set<String> routes = client.currentlyAdvertised();

        assertThat(routes).containsExactlyInAnyOrder("10.253.53.53/32", "10.10.0.0/24");
    }

    @Test
    void currentlyAdvertisedReturnsEmptySetWhenFieldAbsent() throws Exception {
        Path cli = writeFakeCli("""
            #!/bin/sh
            echo '{"Other":"ignored"}'
            exit 0
            """);

        CliTailscaleClient client = new CliTailscaleClient(cli.toString(), Duration.ofSeconds(5));
        assertThat(client.currentlyAdvertised()).isEmpty();
    }

    @Test
    void currentlyAdvertisedThrowsOnMalformedJson() throws Exception {
        Path cli = writeFakeCli("""
            #!/bin/sh
            echo 'not json'
            exit 0
            """);

        CliTailscaleClient client = new CliTailscaleClient(cli.toString(), Duration.ofSeconds(5));
        assertThatThrownBy(client::currentlyAdvertised).isInstanceOf(TailscaleException.class);
    }

    @Test
    void missingExecutableThrowsTailscaleException() {
        CliTailscaleClient client = new CliTailscaleClient(
            tempDir.resolve("does-not-exist").toString(), Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.advertiseRoutes(Set.of("10.253.53.53/32")))
            .isInstanceOf(TailscaleException.class);
    }
}
