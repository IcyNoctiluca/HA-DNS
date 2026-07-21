package io.homelab.dnsha.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.PeerConfig;
import io.homelab.dnsha.config.SplitBrainConfig;
import io.homelab.dnsha.http.HealthResponse;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.state.FsmState;
import io.homelab.dnsha.state.HealthState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Polls one peer's /health endpoint on its own virtual thread (PHASES.md
 * Phase 3: "one virtual thread per peer"), on
 * {@code split_brain.peer_poll_interval} cadence. Writes what it learns
 * into {@link HealthState#recordPeerPollResult}, which re-runs FsmReducer —
 * this class never calls Decider or Reconciler directly (same
 * never-drive-side-effects-directly pattern as BackendProbeLoop, though
 * here the peer-observation write path DOES feed the FSM, unlike backend
 * probes — that is the whole point of peer monitoring).
 *
 * Two distinct signals are extracted from each successful poll (see
 * BLUEPRINT.md §6 and PeerObservation's javadoc):
 *  - reachable: this poll itself succeeded.
 *  - reportsMeUnreachable: the peer's own /health.unreachable_peers array
 *    contains this node's name (config.node().name()) — the
 *    asymmetric-inbound signal.
 *
 * Uses java.net.http.HttpClient (JDK-provided; no new dependency) and
 * Jackson's ObjectMapper (already a transitive dependency via
 * jersey-media-json-jackson / jackson-dataformat-yaml) to parse the peer's
 * JSON body directly into the existing HealthResponse record — the same
 * type HealthResource serializes on the peer's side — so this stays in
 * lock-step with the actual wire schema instead of hand-parsing a subset
 * of fields.
 */
public final class PeerMonitor implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PeerMonitor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PeerConfig peer;
    private final Config config;
    private final HealthState state;
    private final Metrics metrics;
    private final HttpClient httpClient;

    private volatile boolean running = true;

    // Carried forward across failed polls: a single missed poll is not new
    // information about what the peer last reported (see PeerObservation's
    // javadoc and HealthState.recordPeerPollResult's parameter docs) — it
    // only changes what "reachable" reports for THIS poll.
    private volatile FsmState lastKnownReportedState;
    private volatile boolean lastKnownReportsMeUnreachable;

    public PeerMonitor(PeerConfig peer, Config config, HealthState state, Metrics metrics) {
        this(peer, config, state, metrics, HttpClient.newBuilder()
            .connectTimeout(effectiveSplitBrain(config).peerPollTimeout())
            .build());
    }

    /** Test-visible constructor: inject an HttpClient (e.g. pointed at a loopback fake /health server). */
    PeerMonitor(PeerConfig peer, Config config, HealthState state, Metrics metrics, HttpClient httpClient) {
        this.peer = peer;
        this.config = config;
        this.state = state;
        this.metrics = metrics;
        this.httpClient = httpClient;
    }

    private static SplitBrainConfig effectiveSplitBrain(Config config) {
        // Config's compact constructor already defaults a null splitBrain
        // to SplitBrainConfig.disabled() (which itself fills in sane
        // Duration defaults) — see Config.java — so this is never null in
        // practice. Re-checked here defensively since this runs at
        // construction time, before App's usual "peers empty -> don't even
        // construct a PeerMonitor" guard would have caught a null.
        return config.splitBrain() != null ? config.splitBrain() : SplitBrainConfig.disabled();
    }

    @Override
    public void run() {
        SplitBrainConfig splitBrain = effectiveSplitBrain(config);
        log.info("peer_monitor_started",
            kv("node", config.node().name()), kv("peer", peer.name()), kv("peer_health_url", peer.healthUrl()),
            kv("interval", splitBrain.peerPollInterval().toString()));
        while (running) {
            try {
                runOneCycle(splitBrain);
                Thread.sleep(splitBrain.peerPollInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("peer_monitor_interrupted", kv("node", config.node().name()), kv("peer", peer.name()));
                return;
            } catch (RuntimeException e) {
                log.error("peer_monitor_cycle_error",
                    kv("node", config.node().name()), kv("peer", peer.name()), kv("error", e.toString()), e);
            }
        }
    }

    private void runOneCycle(SplitBrainConfig splitBrain) throws InterruptedException {
        // A peer poll result can itself flip the FSM (HEALTHY <-> PARTITIONED
        // — see HealthState.recordPeerPollResult), independently of and on
        // a different cadence from ProbeLoop's own probe cycle. ProbeLoop's
        // before/after "state_transition" logging only observes transitions
        // that happen inside its own recordProbeResult call, so a
        // peer-driven transition would otherwise go completely unlogged and
        // the dns_ha_agent_state gauge would go stale until the next probe
        // cycle happens to refresh it (AGENTS.md invariant #9: state
        // transitions are INFO-logged). This method tracks and logs its own
        // before/after the same way, mirroring ProbeLoop.runOneCycle.
        FsmState before = state.currentState();

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(peer.healthUrl()))
                .timeout(splitBrain.peerPollTimeout())
                .GET()
                .build();
        } catch (RuntimeException e) {
            // Malformed health_url — a config problem, not a transient
            // network failure, but still surfaces as "peer unreachable"
            // rather than crashing this virtual thread.
            recordUnreachable("invalid_peer_health_url: " + e);
            logStateTransitionIfChanged(before, "peer_poll_unreachable");
            return;
        }

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                recordUnreachable("http_status_" + response.statusCode());
                logStateTransitionIfChanged(before, "peer_poll_unreachable");
                return;
            }
            HealthResponse body = JSON.readValue(response.body(), HealthResponse.class);
            recordReachable(body);
            logStateTransitionIfChanged(before, "peer_poll_result");
        } catch (IOException e) {
            recordUnreachable(e.toString());
            logStateTransitionIfChanged(before, "peer_poll_unreachable");
        }
    }

    private void logStateTransitionIfChanged(FsmState before, String reason) {
        FsmState after = state.currentState();
        for (FsmState candidate : new FsmState[] {
            FsmState.STARTING, FsmState.HEALTHY, FsmState.UNHEALTHY, FsmState.PARTITIONED
        }) {
            metrics.state.labels(candidate.name()).set(candidate == after ? 1 : 0);
        }
        if (after == before) {
            return;
        }
        boolean regressed = after == FsmState.UNHEALTHY || after == FsmState.PARTITIONED
            || after == FsmState.SHUTTING_DOWN;
        if (regressed) {
            log.warn("state_transition",
                kv("node", config.node().name()), kv("from", before), kv("to", after), kv("reason", reason));
        } else {
            log.info("state_transition",
                kv("node", config.node().name()), kv("from", before), kv("to", after), kv("reason", reason));
        }
    }

    private void recordReachable(HealthResponse body) {
        FsmState reportedState = parseFsmState(body.state());
        boolean reportsMeUnreachable = body.unreachablePeers() != null
            && body.unreachablePeers().contains(config.node().name());

        lastKnownReportedState = reportedState;
        lastKnownReportsMeUnreachable = reportsMeUnreachable;

        state.recordPeerPollResult(peer.name(), true, reportedState, reportsMeUnreachable);
        metrics.peerReachable.labels(peer.name()).set(1);
        log.debug("peer_poll_result",
            kv("node", config.node().name()), kv("peer", peer.name()), kv("result", "reachable"),
            kv("peer_reported_state", reportedState), kv("reports_me_unreachable", reportsMeUnreachable));
    }

    private void recordUnreachable(String reason) {
        state.recordPeerPollResult(peer.name(), false, lastKnownReportedState, lastKnownReportsMeUnreachable);
        metrics.peerReachable.labels(peer.name()).set(0);
        log.warn("peer_poll_result",
            kv("node", config.node().name()), kv("peer", peer.name()), kv("result", "unreachable"),
            kv("reason", reason));
    }

    private static FsmState parseFsmState(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return FsmState.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void stop() {
        running = false;
    }

    /**
     * Test-visible single-cycle hook: runs exactly one poll (the same code
     * {@link #run()}'s loop calls) without starting the sleep/loop
     * machinery, so PeerMonitorTest can assert on HealthState after one
     * deterministic poll rather than racing a background virtual thread.
     */
    void pollOnceForTest() {
        try {
            runOneCycle(effectiveSplitBrain(config));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during test poll", e);
        }
    }
}
