package io.homelab.dnsha;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppCliArgsTest {

    @Test
    void parsesOfflineConfigCheck() {
        App.CliArgs args = App.CliArgs.parse(new String[] {"--config", "/tmp/config.yaml", "--check-config"});

        assertThat(args.configPath()).isEqualTo(Path.of("/tmp/config.yaml"));
        assertThat(args.mode()).isEqualTo(App.CliMode.CHECK_CONFIG);
        assertThat(args.allowNoopTailscale()).isFalse();
    }

    @Test
    void parsesOfflineRoutePlan() {
        App.CliArgs args = App.CliArgs.parse(new String[] {"--plan-routes"});

        assertThat(args.mode()).isEqualTo(App.CliMode.PLAN_ROUTES);
    }

    @Test
    void rejectsMultipleOfflineCommands() {
        assertThatThrownBy(() -> App.CliArgs.parse(new String[] {"--check-config", "--plan-routes"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one");
    }
}
