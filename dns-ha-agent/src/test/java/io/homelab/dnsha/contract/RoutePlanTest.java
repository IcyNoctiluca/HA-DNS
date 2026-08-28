package io.homelab.dnsha.contract;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.decide.Decision;
import io.homelab.dnsha.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePlanTest {

    @Test
    void exposesCompleteAdvertiseAndWithdrawSetsDeterministically() {
        Config config = TestConfigs.minimalBuilder()
            .managedRoutes(List.of("10.253.53.53/32"))
            .preservedRoutes(List.of("::/0", "0.0.0.0/0"))
            .build();

        RoutePlan plan = RoutePlan.from(config);

        assertThat(plan.schemaVersion()).isEqualTo(1);
        assertThat(plan.configSchemaVersion()).isEqualTo(1);
        assertThat(plan.node()).isEqualTo("test-node");
        assertThat(plan.managedRoutes()).containsExactly("10.253.53.53/32");
        assertThat(plan.preservedRoutes()).containsExactly("0.0.0.0/0", "::/0");
        assertThat(plan.advertiseRoutes()).containsExactly("0.0.0.0/0", "10.253.53.53/32", "::/0");
        assertThat(plan.withdrawRoutes()).containsExactly("0.0.0.0/0", "::/0");
    }

    @Test
    void liveDesiredSetUsesTheSamePlanner() {
        Config config = TestConfigs.minimalBuilder()
            .preservedRoutes(List.of("0.0.0.0/0", "::/0"))
            .build();

        assertThat(RoutePlan.desiredRoutes(config, Decision.ADVERTISE))
            .isEqualTo(Set.of("10.253.53.53/32", "0.0.0.0/0", "::/0"));
        assertThat(RoutePlan.desiredRoutes(config, Decision.WITHDRAW))
            .isEqualTo(Set.of("0.0.0.0/0", "::/0"));
    }
}
