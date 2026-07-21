package io.homelab.dnsha.lifecycle;

import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.decide.Decision;
import io.homelab.dnsha.http.HealthServer;
import io.homelab.dnsha.peer.PeerMonitor;
import io.homelab.dnsha.probe.BackendProbeLoop;
import io.homelab.dnsha.probe.ProbeLoop;
import io.homelab.dnsha.state.HealthState;
import io.homelab.dnsha.tailscale.Reconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * BLUEPRINT.md §8 "Graceful shutdown (SIGTERM)": transition to
 * SHUTTING_DOWN, withdraw synchronously (wait up to 5s for confirmation),
 * drain HTTP (2s deadline), stop loops, flush logs, exit 0.
 *
 * <p><b>Why sun.misc.Signal instead of a plain {@code Runtime} shutdown
 * hook</b> (open design question, flagged not guessed past — AGENTS.md
 * "when in doubt: FSM transitions ... stop and discuss" doesn't quite cover
 * this, but it's adjacent enough — process-exit-code correctness — to
 * document explicitly): a plain {@code Runtime.getRuntime().addShutdownHook(...)}
 * (the pre-Phase-4 approach still in App.java as a generic best-effort
 * backstop) DOES run on SIGTERM by default, but a shutdown hook thread
 * cannot itself call {@code System.exit(...)} — the JVM's exit sequence is
 * already in progress at that point and a nested {@code exit()} call from
 * within a running hook blocks/deadlocks (documented JVM behaviour). That
 * means a plain shutdown hook cannot GUARANTEE the process's own exit
 * status is exactly 0 on SIGTERM; the acceptance criterion (PHASES.md
 * Phase 4: "Agent withdraws within 5s and exits 0") requires a specific,
 * verifiable code, not "whatever the JVM's built-in signal handler
 * happens to use," which is default-JVM-implementation-defined behaviour
 * this session could not empirically verify (see PROGRESS.md — an attempt
 * to test SIGTERM/shutdown-hook exit-code semantics directly in this exact
 * sandbox was inconclusive: the sandboxed process/signal-delivery model
 * here does not reliably mirror a real Docker host's signal handling well
 * enough to trust the result either way). sun.misc.Signal.handle(...) lets
 * this class install its own SIGTERM handler, run the full sequence on a
 * dedicated thread, and call {@link Runtime#halt(int)} (which — unlike
 * exit() — is legal to call from any thread, including one that isn't
 * itself a registered shutdown hook, and does not re-trigger the hook
 * chain) with an explicit, unambiguous 0. sun.misc.Signal is technically
 * unsupported/JDK-internal but is a long-standing, widely-relied-upon
 * pattern for exactly this (Netty, Jetty, Tomcat, and many other server
 * frameworks use it the same way) and has shipped in every mainstream
 * OpenJDK-based distribution, including Temurin (BLUEPRINT.md §11's target)
 * for its entire history — low risk relative to the benefit of a
 * deterministic exit code. <b>Please verify {@code Signal.handle(new
 * Signal("TERM"), ...)} actually intercepts a real {@code docker stop} /
 * {@code systemctl stop} SIGTERM against a compiled build on a real host
 * before Phase 7's production gate</b> — this session has no working
 * compiler for this source (see PROGRESS.md) and could not build+run this
 * class to confirm empirically.
 *
 * <p>This class does NOT replace the existing minimal best-effort
 * {@code Runtime} shutdown hook in App.java — that one remains as a
 * generic backstop for every OTHER exit path (WatchdogThread's forced
 * {@code System.exit(70)} on a detected hang, the uncaught-exception
 * handler's exit, or any future exit call this class doesn't know about),
 * deliberately kept simple (single best-effort withdraw attempt, no 5s
 * confirmation wait) so a forced hang-exit isn't delayed by the full
 * graceful sequence. Only an actual SIGTERM goes through this hardened path.
 */
public final class GracefulShutdown {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);

    static final Duration DEFAULT_WITHDRAW_CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_WITHDRAW_POLL_INTERVAL = Duration.ofMillis(300);
    static final Duration DEFAULT_HTTP_DRAIN_TIMEOUT = Duration.ofSeconds(2);

    private final HealthState healthState;
    private final ProbeLoop probeLoop;
    private final List<BackendProbeLoop> backendProbeLoops;
    private final List<PeerMonitor> peerMonitors;
    private final WatchdogThread watchdog;
    private final HealthServer healthServer;
    private final Reconciler reconciler;
    private final Config config;
    private final Duration withdrawConfirmTimeout;
    private final Duration withdrawPollInterval;
    private final Duration httpDrainTimeout;
    private final IntConsumer exitHandler;

    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    public GracefulShutdown(HealthState healthState, ProbeLoop probeLoop, List<BackendProbeLoop> backendProbeLoops,
                             List<PeerMonitor> peerMonitors, WatchdogThread watchdog, HealthServer healthServer,
                             Reconciler reconciler, Config config) {
        this(healthState, probeLoop, backendProbeLoops, peerMonitors, watchdog, healthServer, reconciler, config,
            DEFAULT_WITHDRAW_CONFIRM_TIMEOUT, DEFAULT_WITHDRAW_POLL_INTERVAL, DEFAULT_HTTP_DRAIN_TIMEOUT,
            code -> Runtime.getRuntime().halt(code));
    }

    /** Package-visible full constructor for tests: injects every timing knob plus the exit action. */
    GracefulShutdown(HealthState healthState, ProbeLoop probeLoop, List<BackendProbeLoop> backendProbeLoops,
                      List<PeerMonitor> peerMonitors, WatchdogThread watchdog, HealthServer healthServer,
                      Reconciler reconciler, Config config, Duration withdrawConfirmTimeout,
                      Duration withdrawPollInterval, Duration httpDrainTimeout, IntConsumer exitHandler) {
        this.healthState = Objects.requireNonNull(healthState, "healthState");
        this.probeLoop = Objects.requireNonNull(probeLoop, "probeLoop");
        this.backendProbeLoops = Objects.requireNonNull(backendProbeLoops, "backendProbeLoops");
        this.peerMonitors = Objects.requireNonNull(peerMonitors, "peerMonitors");
        this.watchdog = Objects.requireNonNull(watchdog, "watchdog");
        this.healthServer = Objects.requireNonNull(healthServer, "healthServer");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.config = Objects.requireNonNull(config, "config");
        this.withdrawConfirmTimeout = Objects.requireNonNull(withdrawConfirmTimeout, "withdrawConfirmTimeout");
        this.withdrawPollInterval = Objects.requireNonNull(withdrawPollInterval, "withdrawPollInterval");
        this.httpDrainTimeout = Objects.requireNonNull(httpDrainTimeout, "httpDrainTimeout");
        this.exitHandler = Objects.requireNonNull(exitHandler, "exitHandler");
    }

    /** Installs the SIGTERM handler. Call once from App after every component above is wired and started. */
    public void install() {
        try {
            Signal.handle(new Signal("TERM"), signal -> onSigterm());
            log.info("graceful_shutdown_sigterm_handler_installed", kv("node", config.node().name()));
        } catch (IllegalArgumentException e) {
            // Unexpected on any mainstream OpenJDK-based Linux target (see
            // class javadoc), but if TERM isn't a valid signal name on this
            // JVM/platform, log loudly rather than silently having no
            // graceful path at all. The generic best-effort Runtime
            // shutdown hook in App.java still applies as a fallback.
            log.error("graceful_shutdown_sigterm_handler_install_failed",
                kv("node", config.node().name()), kv("error", e.toString()));
        }
    }

    /** Package-visible (not private) so tests can invoke it directly instead of dispatching a real OS signal. */
    void onSigterm() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            log.info("sigterm_received_shutdown_already_in_progress", kv("node", config.node().name()));
            return;
        }
        // JDK Signal-dispatch threads must hand off quickly rather than
        // block (standard sun.misc.Signal usage guidance) -- the actual
        // sequence runs on its own thread.
        Thread shutdownThread = new Thread(this::runGracefulShutdown, "sigterm-graceful-shutdown");
        shutdownThread.setDaemon(false);
        shutdownThread.start();
    }

    /** Package-visible so tests can drive the sequence directly without dispatching a real OS signal. */
    void runGracefulShutdown() {
        log.warn("sigterm_received_beginning_graceful_shutdown", kv("node", config.node().name()));

        // Step 1 (BLUEPRINT.md §8): transition FSM to SHUTTING_DOWN first --
        // Decider.decide() then returns WITHDRAW unconditionally regardless
        // of whatever the local probe/peer state happened to be.
        healthState.transitionToShuttingDown();

        watchdog.stop();
        probeLoop.stop();
        backendProbeLoops.forEach(BackendProbeLoop::stop);
        peerMonitors.forEach(PeerMonitor::stop);

        // Step 2: withdraw synchronously, wait up to 5s for confirmation.
        boolean confirmed = withdrawAndConfirm();
        if (confirmed) {
            log.info("sigterm_withdraw_confirmed", kv("node", config.node().name()));
        } else {
            log.error("sigterm_withdraw_not_confirmed_within_timeout",
                kv("node", config.node().name()), kv("timeout", withdrawConfirmTimeout.toString()));
        }

        // Step 3: stop accepting new HTTP requests; allow in-flight to
        // complete (2s deadline).
        try {
            healthServer.stopGracefully(httpDrainTimeout);
        } catch (RuntimeException e) {
            log.error("sigterm_health_server_drain_failed",
                kv("node", config.node().name()), kv("error", e.toString()));
        }

        // Step 5/6: flush logs (logback's console appender used here is
        // unbuffered/immediate-flush by default -- see LoggingBootstrap --
        // so no extra flush call is needed) and exit 0. Runtime.halt(0),
        // not System.exit(0) -- see class javadoc for why.
        log.info("sigterm_graceful_shutdown_complete_exiting", kv("node", config.node().name()));
        exitHandler.accept(0);
    }

    private boolean withdrawAndConfirm() {
        Instant deadline = Instant.now().plus(withdrawConfirmTimeout);
        while (true) {
            try {
                reconciler.reconcile(Decision.WITHDRAW);
            } catch (RuntimeException e) {
                log.error("sigterm_withdraw_attempt_failed",
                    kv("node", config.node().name()), kv("error", e.toString()));
            }
            if (!managedRoutesStillAdvertised()) {
                return true;
            }
            if (!Instant.now().isBefore(deadline)) {
                return false;
            }
            sleepQuietly(withdrawPollInterval);
        }
    }

    private boolean managedRoutesStillAdvertised() {
        Set<String> observed = reconciler.lastObservedAdvertised();
        return config.tailscale().managedRoutes().stream().anyMatch(observed::contains);
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
