package io.homelab.dnsha;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.tailscale.CliTailscaleClient;
import io.homelab.dnsha.tailscale.NoopTailscaleClient;
import io.homelab.dnsha.tailscale.TailscaleClient;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASES.md Phase 7 acceptance: "{@code tailscale.mode} enforcement".
 *
 * Exercises {@link App#createTailscaleClient} directly — the single choke
 * point where {@code tailscale.mode=noop} is gated on
 * {@code DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE=true} or
 * {@code --allow-noop-tailscale}. The negative case relies on the fact that
 * the test JVM does not have {@code DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE} set
 * (the overwhelmingly common case, mirroring NoopTailscaleClientTest's own
 * assumption) — it would be a broken test environment, not a broken agent,
 * if that env var were present. The {@code mode=cli} "no reachable CLI/socket
 * exits non-zero" half of the acceptance criterion is covered at the
 * StartupReconciliation layer by StartupReconciliationTest
 * ({@code unobservableTailscaleStateAtStartupIsFatal}): a
 * {@code currentlyAdvertised()} failure in cli mode throws
 * {@link FatalStartupException} with a non-zero exit code, which App.main
 * maps to {@code System.exit}.
 */
class AppTailscaleModeTest {

    @Test
    void noopModeWithoutOptInIsFatalWithConfigErrorExitCode() {
        Config config = TestConfigs.minimalBuilder().mode("noop").build();

        assertThatThrownBy(() -> App.createTailscaleClient(config, false))
            .isInstanceOfSatisfying(FatalStartupException.class, e -> {
                assertThat(e.exitCode()).isEqualTo(78);
                assertThat(e.getMessage()).contains("DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE");
            });
    }

    @Test
    void noopModeWithFlagOptInReturnsNoopClient() throws Exception {
        Config config = TestConfigs.minimalBuilder().mode("noop").build();

        TailscaleClient client = App.createTailscaleClient(config, true);

        assertThat(client).isInstanceOf(NoopTailscaleClient.class);
    }

    @Test
    void cliModeReturnsCliClientWithoutNeedingOptIn() throws Exception {
        Config config = TestConfigs.minimalBuilder().mode("cli").build();

        TailscaleClient client = App.createTailscaleClient(config, false);

        assertThat(client).isInstanceOf(CliTailscaleClient.class);
    }
}
