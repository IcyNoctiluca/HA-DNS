package io.homelab.dnsha.decide;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.SplitBrainConfig;
import io.homelab.dnsha.state.FsmState;
import io.homelab.dnsha.state.HealthSnapshot;
import io.homelab.dnsha.state.HealthState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * Pure decision function: FSM state + config -> ADVERTISE or WITHDRAW.
 * See BLUEPRINT.md §7.
 *
 * The partition branch below mirrors the blueprint's Decider pseudocode
 * exactly (peer-unreachable counting against config.split_brain). It is
 * unreachable in practice before Phase 3 lands PeerMonitor, because
 * config.peers() is empty and HealthState never receives peer
 * observations until then — see FsmReducer's javadoc for the fuller
 * phase-boundary note. Implementing it now (rather than leaving a TODO)
 * means the Phase 1 unit-test matrix can already exercise "every FSM
 * state -> correct decision" including PARTITIONED/SHUTTING_DOWN, per
 * PHASES.md Phase 1 acceptance criteria, using synthetic HealthState
 * fixtures (no PeerMonitor required to construct the test inputs).
 */
public final class Decider {

    private static final Path DEFAULT_FORCE_WITHDRAW_PATH = Path.of("/var/lib/dns-ha-agent/force-withdraw");

    private Decider() {
    }

    public static Decision decide(HealthState state, Config config) {
        return decide(state, config, Decider::isForceWithdrawActive);
    }

    /**
     * Exposed so HealthResource (Phase 2 /health) can report
     * `force_withdraw_active` using the exact same check Decider itself
     * uses to make the WITHDRAW decision, rather than duplicating the path
     * constant and risking the two drifting apart.
     */
    public static boolean isForceWithdrawActive() {
        return Files.exists(DEFAULT_FORCE_WITHDRAW_PATH);
    }

    /**
     * Package-visible overload for tests: injects the force-withdraw file
     * check so tests never touch the real filesystem path.
     */
    static Decision decide(HealthState state, Config config, BooleanSupplier forceWithdrawActive) {
        HealthSnapshot snapshot = state.snapshot();
        FsmState fsm = snapshot.fsmState();

        // SHUTTING_DOWN always wins.
        if (fsm == FsmState.SHUTTING_DOWN) {
            return Decision.WITHDRAW;
        }

        // Operator force-withdraw file overrides everything else but SHUTTING_DOWN.
        if (forceWithdrawActive.getAsBoolean()) {
            return Decision.WITHDRAW;
        }

        // PARTITIONED overrides healthy local probes.
        if (fsm == FsmState.PARTITIONED) {
            return Decision.WITHDRAW;
        }

        // UNHEALTHY / STARTING local probes.
        if (fsm == FsmState.UNHEALTHY || fsm == FsmState.STARTING) {
            return Decision.WITHDRAW;
        }

        // HEALTHY: check partition condition (Phase 3; inert until PeerMonitor exists).
        if (isPartitionConditionMet(snapshot, config)) {
            return Decision.WITHDRAW;
        }

        return Decision.ADVERTISE;
    }

    private static boolean isPartitionConditionMet(HealthSnapshot snapshot, Config config) {
        SplitBrainConfig splitBrain = config.splitBrain();
        if (splitBrain == null || !splitBrain.enabled() || config.peers() == null || config.peers().isEmpty()) {
            return false;
        }
        Instant now = Instant.now();
        long unreachableCount = snapshot.peerObservations().stream()
            .filter(p -> p.reportsMeUnreachable() && p.firstReportedUnreachableTs() != null)
            .filter(p -> !Duration.between(p.firstReportedUnreachableTs(), now)
                .minus(splitBrain.peerUnreachableThreshold()).isNegative())
            .count();
        return unreachableCount >= splitBrain.minPeerReportsToSelfWithdraw();
    }
}
