package io.homelab.dnsha.state;

import io.homelab.dnsha.config.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Thread-safe holder of the agent's current health view, via
 * atomic-ref-swap of immutable HealthSnapshot instances (AGENTS.md
 * invariant #6 — build an immutable snapshot, swap the reference, never
 * synchronize read paths).
 *
 * Note on API shape vs BLUEPRINT.md §9 "Key class signatures": the
 * blueprint sketches recordProbeSuccess(Duration) / recordProbeFailure(String)
 * as two separate entry points. This implementation instead exposes a
 * single recordProbeResult(resolveOk, filterOk, duration, failureReason)
 * because ProbeLoop always has both the resolve and filter outcome
 * available from DnsProbe and deriving "success" in one place (here) keeps
 * that logic from being duplicated at each call site. This is a shape
 * change only — the FSM contract (thresholds, transitions) is unchanged.
 * Logged as a minor deviation in PROGRESS.md.
 */
public final class HealthState {

    private final Config config;
    private final AtomicReference<HealthSnapshot> ref;

    public HealthState(Config config) {
        this.config = config;
        this.ref = new AtomicReference<>(HealthSnapshot.initial(Instant.now()));
    }

    /**
     * Records the outcome of one local probe cycle and drives the FSM
     * forward via FsmReducer. resolveOk/filterOk are ANDed together to
     * derive overall probe success, per BLUEPRINT.md §3 DnsProbe semantics.
     */
    public void recordProbeResult(boolean resolveOk, boolean filterOk, Duration duration, String failureReason) {
        boolean success = resolveOk && filterOk;
        update(prev -> {
            ProbeResult prior = prev.lastProbeResult();
            int consecutiveSuccesses = success ? prior.consecutiveSuccesses() + 1 : 0;
            int consecutiveFailures = success ? 0 : prior.consecutiveFailures() + 1;
            ProbeResult result = new ProbeResult(
                success, resolveOk, filterOk, success ? null : failureReason,
                duration, Instant.now(), consecutiveSuccesses, consecutiveFailures);
            FsmState next = FsmReducer.reduce(prev.fsmState(), result, prev.peerObservations(), config);
            return prev.withProbeResult(result, next);
        });
    }

    /** Convenience wrapper matching BLUEPRINT.md's illustrative signature. */
    public void recordProbeSuccess(Duration duration) {
        recordProbeResult(true, true, duration, null);
    }

    /** Convenience wrapper matching BLUEPRINT.md's illustrative signature. */
    public void recordProbeFailure(String reason) {
        recordProbeResult(false, false, Duration.ZERO, reason);
    }

    /**
     * Records a peer observation (Phase 3 — PeerMonitor is not implemented
     * yet, so nothing calls this in Phase 1/2). Kept here now because
     * HealthState's peer-observation set is what Decider's partition check
     * reads, and defining the shape early avoids a Phase 3 HealthState
     * rewrite.
     */
    public void recordPeerObservation(PeerObservation observation) {
        update(prev -> {
            Set<PeerObservation> next = replacePeer(prev.peerObservations(), observation);
            FsmState nextState = FsmReducer.reduce(prev.fsmState(), prev.lastProbeResult(), next, config);
            return prev.withPeerObservations(next, nextState);
        });
    }

    public void recordPeerUnreachable(String peerName) {
        update(prev -> {
            PeerObservation existing = prev.peerObservations().stream()
                .filter(p -> p.peerName().equals(peerName))
                .findFirst()
                .orElse(null);
            Instant firstUnreachable = (existing != null && existing.reportsMeUnreachable())
                ? existing.firstReportedUnreachableTs()
                : Instant.now();
            PeerObservation updated = new PeerObservation(
                peerName,
                existing != null && existing.reachable(),
                existing != null ? existing.reportedState() : null,
                true,
                firstUnreachable);
            Set<PeerObservation> next = replacePeer(prev.peerObservations(), updated);
            FsmState nextState = FsmReducer.reduce(prev.fsmState(), prev.lastProbeResult(), next, config);
            return prev.withPeerObservations(next, nextState);
        });
    }

    /**
     * Records the outcome of one BackendProbe cycle for a single AGH
     * backend (Phase 2). Deliberately does NOT run FsmReducer — backend
     * probe results never drive FSM transitions or route decisions (see
     * BackendProbe's javadoc "IMPORTANT non-effect" and PHASES.md Phase 2
     * acceptance criteria "BackendProbe does not drive FSM").
     */
    public void recordBackendProbeResult(String name, String address, boolean resolveOk, boolean filterOk) {
        BackendProbeResult result = new BackendProbeResult(name, address, Instant.now(), resolveOk, filterOk);
        update(prev -> prev.withBackendResult(result));
    }

    public FsmState currentState() {
        return ref.get().fsmState();
    }

    public HealthSnapshot snapshot() {
        return ref.get();
    }

    private static Set<PeerObservation> replacePeer(Set<PeerObservation> existing, PeerObservation replacement) {
        Set<PeerObservation> next = new LinkedHashSet<>();
        for (PeerObservation p : existing) {
            if (!p.peerName().equals(replacement.peerName())) {
                next.add(p);
            }
        }
        next.add(replacement);
        return Set.copyOf(next);
    }

    private void update(UnaryOperator<HealthSnapshot> fn) {
        ref.updateAndGet(fn);
    }
}
