package io.homelab.dnsha.http;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.MetricsConfig;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.state.HealthState;
import io.homelab.dnsha.tailscale.NoopTailscaleClient;
import io.homelab.dnsha.tailscale.Reconciler;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link HealthServer#buildResourceConfig} directly — the Jersey
 * {@link ResourceConfig} it returns can be inspected without starting a real
 * Grizzly server / binding a socket, consistent with HealthResourceTest's and
 * MetricsResourceTest's "no embedded HTTP server needed for a unit test"
 * approach (the real end-to-end HTTP wiring is EndToEndIT's job, which needs
 * a real Docker daemon this sandbox doesn't have).
 *
 * Covers the real bug fixed 2026-07-23: {@code MetricsResource} used to be
 * registered unconditionally regardless of {@code config.metrics().enabled()}
 * — see HealthServer's javadoc for full detail.
 */
class HealthServerTest {

    private static final Config BASE = TestConfigs.minimal();

    @Test
    void metricsResourceIsRegisteredWhenMetricsEnabled() {
        Config config = withMetrics(true);
        ResourceConfig resourceConfig = buildFor(config);

        assertThat(resourceConfig.getSingletons()).anyMatch(o -> o instanceof MetricsResource);
        assertThat(resourceConfig.getSingletons()).anyMatch(o -> o instanceof HealthResource);
    }

    @Test
    void metricsResourceIsNotRegisteredWhenMetricsDisabled() {
        Config config = withMetrics(false);
        ResourceConfig resourceConfig = buildFor(config);

        assertThat(resourceConfig.getSingletons()).noneMatch(o -> o instanceof MetricsResource);
        // /health must still be served regardless of the metrics flag.
        assertThat(resourceConfig.getSingletons()).anyMatch(o -> o instanceof HealthResource);
    }

    private static Config withMetrics(boolean enabled) {
        return new Config(
            BASE.version(), BASE.node(), BASE.probe(), BASE.backendProbe(), BASE.peers(), BASE.splitBrain(),
            BASE.tailscale(), BASE.http(), BASE.logging(), new MetricsConfig(enabled, "/metrics"));
    }

    private static ResourceConfig buildFor(Config config) {
        HealthState state = new HealthState(config);
        Reconciler reconciler = new Reconciler(new NoopTailscaleClient(), new Metrics(), config);
        return HealthServer.buildResourceConfig(config, state, reconciler, new Metrics(), Instant.now());
    }
}
