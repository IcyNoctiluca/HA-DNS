package io.homelab.dnsha.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    private static final String VALID_YAML = """
        version: 1
        node:
          name: dns-ams-01
          region: eu
          vip: 10.253.53.53/32
        probe:
          target: 10.253.53.53
          resolve_names: [cloudflare.com, example.com]
          resolve_type: A
          block_name: dns-filter-sentinel.example.com
          block_expectation:
            mode: must_not_return_real_ip
            allowed_rcodes: [NXDOMAIN, REFUSED, NOERROR]
            allowed_ips: ["0.0.0.0"]
          interval: 2s
          query_timeout: 2s
          fail_threshold: 2
          success_threshold: 3
        peers: []
        split_brain:
          enabled: false
        tailscale:
          mode: cli
          cli_path: /usr/bin/tailscale
          managed_routes: [10.253.53.53/32]
          preserved_routes: []
          command_timeout: 10s
        http:
          bind_address: 127.0.0.1
          port: 8091
        logging:
          format: JSON
          level: INFO
        metrics:
          enabled: true
          path: /metrics
        """;

    @Test
    void validYamlParses() {
        Config config = ConfigLoader.loadFromString(VALID_YAML);

        assertThat(config.version()).isEqualTo(1);
        assertThat(config.node().name()).isEqualTo("dns-ams-01");
        assertThat(config.probe().resolveNames()).containsExactly("cloudflare.com", "example.com");
        assertThat(config.probe().interval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(config.probe().failThreshold()).isEqualTo(2);
        assertThat(config.probe().successThreshold()).isEqualTo(3);
        assertThat(config.tailscale().managedRoutes()).containsExactly("10.253.53.53/32");
        assertThat(config.tailscale().commandTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.http().bindAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void defaultsApplyForOptionalSections() {
        String yaml = """
            version: 1
            node:
              name: dns-ams-01
              region: eu
              vip: 10.253.53.53/32
            probe:
              target: 10.253.53.53
              resolve_names: [cloudflare.com]
              block_name: dns-filter-sentinel.example.com
              interval: 2s
              query_timeout: 2s
              fail_threshold: 2
              success_threshold: 3
            tailscale:
              managed_routes: [10.253.53.53/32]
            """;

        Config config = ConfigLoader.loadFromString(yaml);

        assertThat(config.peers()).isEmpty();
        assertThat(config.splitBrain().enabled()).isFalse();
        assertThat(config.backendProbe().enabled()).isFalse();
        assertThat(config.tailscale().mode()).isEqualTo("cli");
        assertThat(config.tailscale().preservedRoutes()).isEmpty();
        assertThat(config.http().port()).isEqualTo(8091);
        assertThat(config.logging().format()).isEqualTo("JSON");
        assertThat(config.metrics().path()).isEqualTo("/metrics");
    }

    @Test
    void invalidYamlRejected() {
        String malformed = "version: 1\nnode: [this is not a map";

        assertThatThrownBy(() -> ConfigLoader.loadFromString(malformed))
            .isInstanceOf(ConfigException.class);
    }

    @Test
    void unknownSchemaVersionExitsWithConfigError() {
        String yaml = """
            version: 2
            node: { name: x, region: eu, vip: 10.253.53.53/32 }
            probe:
              target: 10.253.53.53
              resolve_names: [cloudflare.com]
              block_name: sentinel.example.com
              interval: 2s
              query_timeout: 2s
              fail_threshold: 2
              success_threshold: 3
            tailscale:
              managed_routes: [10.253.53.53/32]
            """;

        assertThatThrownBy(() -> ConfigLoader.loadFromString(yaml))
            .isInstanceOf(ConfigException.class)
            .hasMessageContaining("unsupported config schema version");
    }

    @Test
    void missingManagedRoutesRejected() {
        String yaml = """
            version: 1
            node: { name: x, region: eu, vip: 10.253.53.53/32 }
            probe:
              target: 10.253.53.53
              resolve_names: [cloudflare.com]
              block_name: sentinel.example.com
              interval: 2s
              query_timeout: 2s
              fail_threshold: 2
              success_threshold: 3
            tailscale:
              managed_routes: []
            """;

        assertThatThrownBy(() -> ConfigLoader.loadFromString(yaml))
            .isInstanceOf(ConfigException.class)
            .hasMessageContaining("managed_routes");
    }

    @Test
    void missingResolveNamesRejected() {
        String yaml = """
            version: 1
            node: { name: x, region: eu, vip: 10.253.53.53/32 }
            probe:
              target: 10.253.53.53
              resolve_names: []
              block_name: sentinel.example.com
              interval: 2s
              query_timeout: 2s
              fail_threshold: 2
              success_threshold: 3
            tailscale:
              managed_routes: [10.253.53.53/32]
            """;

        assertThatThrownBy(() -> ConfigLoader.loadFromString(yaml))
            .isInstanceOf(ConfigException.class)
            .hasMessageContaining("resolve_names");
    }

    @Test
    void emptyConfigRejected() {
        assertThatThrownBy(() -> ConfigLoader.loadFromString(""))
            .isInstanceOf(ConfigException.class);
    }

    @Test
    void invalidTailscaleModeRejected() {
        String yaml = """
            version: 1
            node: { name: x, region: eu, vip: 10.253.53.53/32 }
            probe:
              target: 10.253.53.53
              resolve_names: [cloudflare.com]
              block_name: sentinel.example.com
              interval: 2s
              query_timeout: 2s
              fail_threshold: 2
              success_threshold: 3
            tailscale:
              mode: yolo
              managed_routes: [10.253.53.53/32]
            """;

        assertThatThrownBy(() -> ConfigLoader.loadFromString(yaml))
            .isInstanceOf(ConfigException.class)
            .hasMessageContaining("tailscale.mode");
    }

    @Test
    void malformedDurationRejected() {
        String yaml = """
            version: 1
            node: { name: x, region: eu, vip: 10.253.53.53/32 }
            probe:
              target: 10.253.53.53
              resolve_names: [cloudflare.com]
              block_name: sentinel.example.com
              interval: not-a-duration
              query_timeout: 2s
              fail_threshold: 2
              success_threshold: 3
            tailscale:
              managed_routes: [10.253.53.53/32]
            """;

        assertThatThrownBy(() -> ConfigLoader.loadFromString(yaml))
            .isInstanceOf(ConfigException.class);
    }
}
