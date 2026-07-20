package io.homelab.dnsha.http;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.decide.Decider;
import io.homelab.dnsha.decide.Decision;
import io.homelab.dnsha.state.BackendProbeResult;
import io.homelab.dnsha.state.HealthSnapshot;
import io.homelab.dnsha.state.HealthState;
import io.homelab.dnsha.state.PeerObservation;
import io.homelab.dnsha.state.ProbeResult;
import io.homelab.dnsha.tailscale.Reconciler;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * `GET /health` — peer-facing self-assessment endpoint. See BLUEPRINT.md §6
 * for the exact JSON schema this must match, and AGENTS.md "when in doubt"
 * (the /health JSON schema is a load-bearing contract — do not change field
 * names/shape without discussion).
 *
 * Deliberately does NOT call TailscaleClient.currentlyAdvertised() directly
 * on every request: `vip_advertised` is read from
 * Reconciler.lastObservedAdvertised(), a diagnostic-only in-memory cache
 * updated once per probe cycle. This mirrors the same principle AGENTS.md
 * states for /metrics ("never triggers probes on demand") — an HTTP GET
 * here must not itself shell out to `tailscale`.
 *
 * peers[] / unreachable_peers are always empty before Phase 3 (PeerMonitor
 * does not exist yet — see PeerView's javadoc).
 */
@Path("/health")
public final class HealthResource {

    private final HealthState healthState;
    private final Config config;
    private final Reconciler reconciler;
    private final Instant startedAt;

    public HealthResource(HealthState healthState, Config config, Reconciler reconciler, Instant startedAt) {
        this.healthState = healthState;
        this.config = config;
        this.reconciler = reconciler;
        this.startedAt = startedAt;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HealthResponse get() {
        HealthSnapshot snapshot = healthState.snapshot();
        Decision decision = Decider.decide(healthState, config);

        return new HealthResponse(
            1,
            config.node().name(),
            config.node().region(),
            snapshot.fsmState().name(),
            reconciler.lastObservedAdvertised().contains(config.node().vip()),
            decision == Decision.ADVERTISE,
            reconciler.isFailOpen(),
            Decider.isForceWithdrawActive(),
            toLocalProbeView(snapshot.lastProbeResult()),
            toBackendViews(snapshot),
            toPeerViews(snapshot),
            toUnreachablePeerNames(snapshot),
            Duration.between(startedAt, Instant.now()).getSeconds());
    }

    private static LocalProbeView toLocalProbeView(ProbeResult result) {
        return new LocalProbeView(
            result.timestamp() == null ? null : result.timestamp().toString(),
            result.success() ? "HEALTHY" : "UNHEALTHY",
            result.consecutiveSuccesses(),
            result.consecutiveFailures(),
            result.resolveOk(),
            result.filterOk(),
            result.duration() == null ? 0 : result.duration().toMillis());
    }

    private static List<BackendView> toBackendViews(HealthSnapshot snapshot) {
        List<BackendView> views = new ArrayList<>();
        for (BackendProbeResult r : snapshot.backendResults().values()) {
            views.add(new BackendView(r.name(), r.address(), r.lastProbeTs().toString(), r.resolveOk(), r.filterOk()));
        }
        views.sort(Comparator.comparing(BackendView::name));
        return views;
    }

    private static List<PeerView> toPeerViews(HealthSnapshot snapshot) {
        List<PeerView> views = new ArrayList<>();
        for (PeerObservation p : snapshot.peerObservations()) {
            views.add(new PeerView(
                p.peerName(),
                p.firstReportedUnreachableTs() == null ? null : p.firstReportedUnreachableTs().toString(),
                p.reachable(),
                p.reportedState() == null ? null : p.reportedState().name(),
                p.reportsMeUnreachable()));
        }
        views.sort(Comparator.comparing(PeerView::name));
        return views;
    }

    private static List<String> toUnreachablePeerNames(HealthSnapshot snapshot) {
        List<String> names = new ArrayList<>();
        for (PeerObservation p : snapshot.peerObservations()) {
            if (!p.reachable()) {
                names.add(p.peerName());
            }
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }
}
