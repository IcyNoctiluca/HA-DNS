package io.homelab.dnsha.decide;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.state.FsmReducer;
import io.homelab.dnsha.state.FsmState;
import io.homelab.dnsha.state.HealthSnapshot;
import io.homelab.dnsha.state.HealthState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Pure decision function: FSM state + config -> ADVERTISE or WITHDRAW.
 * See BLUEPRINT.md §7.
 *
 * The partition branch below is a fast-path check only: as of Phase 3,
 * FsmReducer itself transitions a node into PARTITIONED the moment the
 * peer-unreachable condition holds (see FsmReducer.isPartitionConditionMet,
 * called on every HealthState mutation), so by the time decide() runs,
 * snapshot.fsmState() is normally already PARTITIONED and the {@code fsm ==
 * PARTITIONED} check below catches it. isPartitionConditionMet(snapshot,
 * config) is kept as a second, independent evaluation of the exact same
 * predicate (delegating to FsmReducer's shared implementation, not a
 * duplicate copy) as defence-in-depth: even if some future caller invoked
 * decide() against a HealthState snapshot that had not yet run through
 * FsmReducer for the latest peer observations, the WITHDRAW decision would
 * still be correct. Decider itself remains pure — this method never
 * mutates the FsmState stored in HealthState; only FsmReducer does that.
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

        // HEALTHY: defence-in-depth re-check of the partition condition (see class javadoc).
        if (isPartitionConditionMet(snapshot, config)) {
            return Decision.WITHDRAW;
        }

        return Decision.ADVERTISE;
    }

    private static boolean isPartitionConditionMet(HealthSnapshot snapshot, Config config) {
        return FsmReducer.isPartitionConditionMet(snapshot.peerObservations(), config);
    }
}
