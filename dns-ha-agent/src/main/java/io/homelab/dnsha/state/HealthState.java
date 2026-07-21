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
     * Records a raw peer observation, replacing whatever is currently held
     * for {@code observation.peerName()} wholesale. Exposed for tests and
     * for callers that have already computed a full PeerObservation
     * themselves; PeerMonitor (Phase 3) uses the narrower
     * {@link #recordPeerPollResult} instead, which handles the sticky
     * first-reported-unreachable/last-seen bookkeeping so callers don't
     * have to re-derive it on every poll.
     */
    public void recordPeerObservation(PeerObservation observation) {
        update(prev -> {
            Set<PeerObservation> next = replacePeer(prev.peerObservations(), observation);
            FsmState nextState = FsmReducer.reduce(prev.fsmState(), prev.lastProbeResult(), next, config);
            return prev.withPeerObservations(next, nextState);
        });
    }

    /**
     * Records the result of one PeerMonitor poll cycle for {@code peerName}
     * (Phase 3 — see io.homelab.dnsha.peer.PeerMonitor).
     *
     * @param reachable whether THIS agent could reach the peer's /health
     *     just now. Feeds this node's own {@code unreachable_peers} list
     *     (HealthResource) and {@code lastSeenTs} (advanced only when true).
     * @param reportedState the peer's own last-known FSM state. On a failed
     *     poll, PeerMonitor passes the value it last learned rather than
     *     null — see PeerObservation's javadoc on why a single missed poll
     *     must not erase what was previously known.
     * @param peerReportsMeUnreachable whether the peer's own
     *     /health.unreachable_peers list contained this node's name as of
     *     the last successful poll of that peer (also caller-preserved
     *     across failed polls). Drives the sticky
     *     firstReportedUnreachableTs timestamp: set on a false-&gt;true
     *     transition, cleared to null on true-&gt;false, held steady while
     *     staying true — this is the timestamp
     *     FsmReducer.isPartitionConditionMet measures against
     *     peer_unreachable_threshold.
     *
     * Every call re-runs FsmReducer with the updated peer-observation set
     * (same as recordPeerObservation/recordPeerUnreachable), so a single
     * peer poll result can itself drive a HEALTHY &lt;-&gt; PARTITIONED
     * transition.
     */
    public void recordPeerPollResult(
            String peerName, boolean reachable, FsmState reportedState, boolean peerReportsMeUnreachable) {
        update(prev -> {
            PeerObservation existing = findPeer(prev, peerName);
            Instant firstUnreachable;
            if (peerReportsMeUnreachable) {
                firstUnreachable = (existing != null && existing.reportsMeUnreachable())
                    ? existing.firstReportedUnreachableTs()
                    : Instant.now();
            } else {
                firstUnreachable = null;
            }
            Instant lastSeen = reachable ? Instant.now() : (existing != null ? existing.lastSeenTs() : null);
            PeerObservation updated = new PeerObservation(
                peerName, reachable, reportedState, peerReportsMeUnreachable, firstUnreachable, lastSeen);
            Set<PeerObservation> next = replacePeer(prev.peerObservations(), updated);
            FsmState nextState = FsmReducer.reduce(prev.fsmState(), prev.lastProbeResult(), next, config);
            return prev.withPeerObservations(next, nextState);
        });
    }

    /**
     * Marks {@code peerName} as reporting this node unreachable (i.e. this
     * node's own name was found in that peer's /health.unreachable_peers
     * list), without asserting anything about reachable/reportedState.
     * Narrower convenience wrapper kept for the existing unit-test matrix
     * and for callers that only have this one signal; PeerMonitor uses
     * {@link #recordPeerPollResult} for the full poll result instead.
     */
    public void recordPeerUnreachable(String peerName) {
        update(prev -> {
            PeerObservation existing = findPeer(prev, peerName);
            Instant firstUnreachable = (existing != null && existing.reportsMeUnreachable())
                ? existing.firstReportedUnreachableTs()
                : Instant.now();
            PeerObservation updated = new PeerObservation(
                peerName,
                existing != null && existing.reachable(),
                existing != null ? existing.reportedState() : null,
                true,
                firstUnreachable,
                existing != null ? existing.lastSeenTs() : null);
            Set<PeerObservation> next = replacePeer(prev.peerObservations(), updated);
            FsmState nextState = FsmReducer.reduce(prev.fsmState(), prev.lastProbeResult(), next, config);
            return prev.withPeerObservations(next, nextState);
        });
    }

    private static PeerObservation findPeer(HealthSnapshot snapshot, String peerName) {
        return snapshot.peerObservations().stream()
            .filter(p -> p.peerName().equals(peerName))
            .findFirst()
            .orElse(null);
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
