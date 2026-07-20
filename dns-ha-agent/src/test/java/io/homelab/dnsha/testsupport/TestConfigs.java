package io.homelab.dnsha.testsupport;

import io.homelab.dnsha.config.BlockExpectation;
import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.NodeConfig;
import io.homelab.dnsha.config.PeerConfig;
import io.homelab.dnsha.config.ProbeConfig;
import io.homelab.dnsha.config.SplitBrainConfig;
import io.homelab.dnsha.config.TailscaleConfig;

import java.time.Duration;
import java.util.List;

/** Shared minimal Config fixtures for unit tests across packages. */
public final class TestConfigs {

    private TestConfigs() {
    }

    public static Config minimal() {
        return minimalBuilder().build();
    }

    public static Builder minimalBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private int failThreshold = 2;
        private int successThreshold = 3;
        private List<PeerConfig> peers = List.of();
        private SplitBrainConfig splitBrain = SplitBrainConfig.disabled();
        private List<String> managedRoutes = List.of("10.253.53.53/32");
        private List<String> preservedRoutes = List.of();

        public Builder failThreshold(int v) {
            this.failThreshold = v;
            return this;
        }

        public Builder successThreshold(int v) {
            this.successThreshold = v;
            return this;
        }

        public Builder peers(List<PeerConfig> v) {
            this.peers = v;
            return this;
        }

        public Builder splitBrain(SplitBrainConfig v) {
            this.splitBrain = v;
            return this;
        }

        public Builder managedRoutes(List<String> v) {
            this.managedRoutes = v;
            return this;
        }

        public Builder preservedRoutes(List<String> v) {
            this.preservedRoutes = v;
            return this;
        }

        public Config build() {
            NodeConfig node = new NodeConfig("test-node", "eu", "10.253.53.53/32");
            ProbeConfig probe = new ProbeConfig(
                "10.253.53.53",
                List.of("cloudflare.com", "example.com"),
                "A",
                "dns-filter-sentinel.example.com",
                new BlockExpectation(null, null, null),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                failThreshold,
                successThreshold);
            TailscaleConfig tailscale = new TailscaleConfig(
                "cli", "/usr/bin/tailscale", managedRoutes, preservedRoutes, Duration.ofSeconds(10));

            return new Config(1, node, probe, null, peers, splitBrain, tailscale, null, null, null);
        }
    }
}
