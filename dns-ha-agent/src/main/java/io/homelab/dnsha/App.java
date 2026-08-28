package io.homelab.dnsha;

import io.homelab.dnsha.config.BackendConfig;
import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.ConfigException;
import io.homelab.dnsha.config.ConfigLoader;
import io.homelab.dnsha.config.PeerConfig;
import io.homelab.dnsha.contract.OfflineContract;
import io.homelab.dnsha.decide.Decision;
import io.homelab.dnsha.http.HealthServer;
import io.homelab.dnsha.lifecycle.GracefulShutdown;
import io.homelab.dnsha.lifecycle.StartupReconciliation;
import io.homelab.dnsha.lifecycle.WatchdogThread;
import io.homelab.dnsha.metrics.Metrics;
import io.homelab.dnsha.peer.PeerMonitor;
import io.homelab.dnsha.probe.BackendProbe;
import io.homelab.dnsha.probe.BackendProbeLoop;
import io.homelab.dnsha.probe.DnsProbe;
import io.homelab.dnsha.probe.ProbeLoop;
import io.homelab.dnsha.state.HealthState;
import io.homelab.dnsha.tailscale.CliTailscaleClient;
import io.homelab.dnsha.tailscale.NoopTailscaleClient;
import io.homelab.dnsha.tailscale.Reconciler;
import io.homelab.dnsha.tailscale.TailscaleClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point. Loads config, wires components, runs the probe loop.
 * See BLUEPRINT.md §8 Operational Behaviour, §9 Implementation.
 *
 * As of Phase 2: wires up HealthServer (/health, /metrics) and, when
 * backend_probe.enabled, one BackendProbeLoop virtual thread per configured
 * backend. As of Phase 3: wires up one PeerMonitor virtual thread per
 * configured peer (config.peers() empty -> no threads started at all,
 * "single-node region" per BLUEPRINT.md §4 note 1 / PHASES.md Phase 3
 * "empty peer list disables peer monitoring entirely"). As of Phase 4:
 * performs hardened startup reconciliation (StartupReconciliation, BEFORE
 * the probe/backend/peer loops start, per BLUEPRINT.md §8 step ordering),
 * runs a WatchdogThread that force-exits on a hung probe loop or a failing
 * HTTP self-check, and installs a GracefulShutdown SIGTERM handler for the
 * hardened withdraw-with-confirmation + HTTP-drain sequence. The
 * uncaught-exception handler now also force-exits (BLUEPRINT.md §8: "routes
 * ... to logging + exit"), and the pre-Phase-4 minimal best-effort shutdown
 * hook remains as a generic backstop for exit paths other than SIGTERM (see
 * GracefulShutdown's javadoc for why the two are deliberately separate).
 */
public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);
    private static final Path DEFAULT_CONFIG_PATH = Path.of("/etc/dns-ha-agent/config.yaml");

    /** BLUEPRINT.md §8: uncaught virtual-thread exceptions are logged + exit non-zero. Same code as WatchdogThread's hang-detection exit. */
    private static final int EXIT_CODE_UNCAUGHT_EXCEPTION = 70; // EX_SOFTWARE

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            log.error("uncaught_exception", kv("thread", thread.getName()), kv("error", throwable.toString()), throwable);
            System.exit(EXIT_CODE_UNCAUGHT_EXCEPTION);
        });

        try {
            run(args);
        } catch (ConfigException e) {
            log.error("config_error", kv("error", e.getMessage()));
            System.exit(ConfigLoader.EXIT_CONFIG_ERROR);
        } catch (FatalStartupException e) {
            log.error("fatal_startup_error", kv("error", e.getMessage()));
            System.exit(e.exitCode());
        }
    }

    private static void run(String[] args) throws FatalStartupException {
        CliArgs cliArgs = CliArgs.parse(args);
        Config config = ConfigLoader.loadFromFile(cliArgs.configPath());

        if (cliArgs.mode() != CliMode.RUN) {
            OfflineContract.write(config, cliArgs.mode());
            return;
        }

        LoggingBootstrap.apply(config.logging());

        log.info("dns_ha_agent_starting",
            kv("node", config.node().name()), kv("region", config.node().region()), kv("vip", config.node().vip()));

        TailscaleClient tailscaleClient = createTailscaleClient(config, cliArgs.allowNoopTailscale());
        Metrics metrics = new Metrics();
        Reconciler reconciler = new Reconciler(tailscaleClient, metrics, config);
        HealthState healthState = new HealthState(config);
        DnsProbe probe = new DnsProbe(config.probe());
        ProbeLoop probeLoop = new ProbeLoop(probe, healthState, config, reconciler, metrics);

        // BLUEPRINT.md §8 Startup step 3: HTTP server starts before
        // reconciliation (step 4) and before any probe/peer loop (steps
        // 5-6), so /health is reachable (reporting STARTING) throughout.
        Instant startedAt = Instant.now();
        HealthServer healthServer = new HealthServer(config, healthState, reconciler, metrics, startedAt);
        healthServer.start();

        // Step 4: reconcile actual route state with desired state BEFORE
        // any loop starts — a stale advertisement from a dead predecessor
        // must never be inherited as "we're healthy" (Phase 4).
        StartupReconciliation.reconcile(config, tailscaleClient, reconciler);

        // Steps 5-6: probe loop + peer monitor loops. (Backend probe loops
        // are a Phase 2 addition with no equivalent numbered BLUEPRINT.md
        // §8 step; started alongside peer monitors, after reconciliation.)
        // Probe loop thread starts here (not at the very end of this
        // method as in Phase 1-3) so WatchdogThread, started right below,
        // observes an already-running probe loop from the start rather
        // than one whose thread hasn't been dispatched yet.
        Thread.ofVirtual().name("probe-loop").start(probeLoop);
        List<BackendProbeLoop> backendProbeLoops = startBackendProbeLoops(config, healthState, metrics);
        List<PeerMonitor> peerMonitors = startPeerMonitors(config, healthState, metrics);

        WatchdogThread watchdog = new WatchdogThread(probeLoop, config);
        Thread.ofVirtual().name("watchdog").start(watchdog);

        GracefulShutdown gracefulShutdown = new GracefulShutdown(
            healthState, probeLoop, backendProbeLoops, peerMonitors, watchdog, healthServer, reconciler, config);
        gracefulShutdown.install();

        // Minimal best-effort shutdown path. This is NOT the hardened
        // graceful-SIGTERM handler (GracefulShutdown, installed above) —
        // it's a generic backstop for every OTHER exit path this class
        // doesn't explicitly know about (WatchdogThread's forced
        // System.exit(70) on a detected hang, the uncaught-exception
        // handler's exit(70), a future exit call added elsewhere). It
        // deliberately stays simple (single best-effort withdraw attempt,
        // no 5s confirmation wait, no HTTP drain) so a forced hang-exit
        // isn't delayed by the full graceful sequence — see
        // GracefulShutdown's javadoc for the full reasoning on why the two
        // paths are kept separate. Attempting a withdraw here is strictly
        // better than doing nothing, and does not weaken the fail-closed
        // startup-reconciliation story: a new instance always re-validates
        // via STARTING before it will ADVERTISE again regardless of
        // whether this hook ran.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutdown_hook_running", kv("node", config.node().name()));
            healthState.transitionToShuttingDown();
            watchdog.stop();
            probeLoop.stop();
            backendProbeLoops.forEach(BackendProbeLoop::stop);
            peerMonitors.forEach(PeerMonitor::stop);
            try {
                healthServer.stop();
            } catch (RuntimeException e) {
                log.error("shutdown_hook_health_server_stop_failed", kv("error", e.toString()));
            }
            try {
                reconciler.reconcile(Decision.WITHDRAW);
            } catch (RuntimeException e) {
                log.error("shutdown_hook_withdraw_failed", kv("error", e.toString()));
            }
        }, "shutdown-hook"));

        log.info("dns_ha_agent_started", kv("node", config.node().name()));

        awaitForever();
    }

    /**
     * One virtual thread per configured backend (PHASES.md Phase 2: "One
     * virtual thread per backend"). No-op (returns an empty list) if
     * backend_probe.enabled is false or no backends are configured — this
     * is the expected default (config/example.yaml ships with
     * backend_probe.enabled: false until an operator wires in real AGH
     * backend addresses).
     */
    private static List<BackendProbeLoop> startBackendProbeLoops(Config config, HealthState healthState, Metrics metrics) {
        List<BackendProbeLoop> loops = new ArrayList<>();
        if (!config.backendProbe().enabled() || config.backendProbe().backends().isEmpty()) {
            log.info("backend_probe_disabled", kv("node", config.node().name()));
            return loops;
        }
        for (BackendConfig backend : config.backendProbe().backends()) {
            BackendProbe backendProbe = new BackendProbe(backend, config.probe(), config.backendProbe().queryTimeout());
            BackendProbeLoop loop = new BackendProbeLoop(
                backendProbe, healthState, config.backendProbe(), metrics, config.node().name());
            Thread.ofVirtual().name("backend-probe-" + backend.name()).start(loop);
            loops.add(loop);
        }
        return loops;
    }

    /**
     * One virtual thread per configured peer (PHASES.md Phase 3: "one
     * virtual thread per peer"). Returns an empty list (no threads started
     * at all) when config.peers() is empty — PARTITIONED is then
     * structurally unreachable, matching BLUEPRINT.md §4 note 1
     * "single-node region (zero peers configured): PARTITIONED state is
     * unreachable" and the Phase 3 acceptance criterion "empty peer list
     * disables peer monitoring entirely".
     */
    private static List<PeerMonitor> startPeerMonitors(Config config, HealthState healthState, Metrics metrics) {
        List<PeerMonitor> monitors = new ArrayList<>();
        if (config.peers() == null || config.peers().isEmpty()) {
            log.info("peer_monitor_disabled_no_peers_configured", kv("node", config.node().name()));
            return monitors;
        }
        for (PeerConfig peer : config.peers()) {
            PeerMonitor monitor = new PeerMonitor(peer, config, healthState, metrics);
            Thread.ofVirtual().name("peer-monitor-" + peer.name()).start(monitor);
            monitors.add(monitor);
        }
        return monitors;
    }

    private static TailscaleClient createTailscaleClient(Config config, boolean allowNoopFlag)
        throws FatalStartupException {
        String mode = config.tailscale().mode();
        boolean allowNoopEnv = "true".equalsIgnoreCase(System.getenv("DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE"));

        if ("noop".equals(mode)) {
            if (!allowNoopFlag && !allowNoopEnv) {
                throw new FatalStartupException(
                    "tailscale.mode=noop requires DNS_HA_AGENT_ALLOW_NOOP_TAILSCALE=true or "
                        + "--allow-noop-tailscale; refusing to start with a no-op Tailscale client "
                        + "in what looks like a production configuration",
                    78);
            }
            log.warn("tailscale_noop_mode_active",
                kv("node", config.node().name()), kv("note", "NOT managing real Tailscale routes"));
            return new NoopTailscaleClient();
        }

        // mode == "cli" (ConfigLoader.validate already rejected anything else)
        return new CliTailscaleClient(
            config.tailscale().cliPath(), config.tailscale().socketPath(), config.tailscale().commandTimeout());
    }

    private static void awaitForever() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public enum CliMode {
        RUN,
        CHECK_CONFIG,
        PLAN_ROUTES
    }

    /** Minimal runtime and offline-contract argument parsing. */
    record CliArgs(Path configPath, boolean allowNoopTailscale, CliMode mode) {
        static CliArgs parse(String[] args) {
            Path configPath = DEFAULT_CONFIG_PATH;
            boolean allowNoop = false;
            CliMode mode = CliMode.RUN;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--config" -> {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("--config requires a path argument");
                        }
                        configPath = Path.of(args[++i]);
                    }
                    case "--allow-noop-tailscale" -> allowNoop = true;
                    case "--check-config" -> mode = selectOfflineMode(mode, CliMode.CHECK_CONFIG);
                    case "--plan-routes" -> mode = selectOfflineMode(mode, CliMode.PLAN_ROUTES);
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (mode != CliMode.RUN && allowNoop) {
                throw new IllegalArgumentException(
                    "--allow-noop-tailscale is a runtime option and cannot be combined with offline commands");
            }
            return new CliArgs(configPath, allowNoop, mode);
        }

        private static CliMode selectOfflineMode(CliMode current, CliMode requested) {
            if (current != CliMode.RUN) {
                throw new IllegalArgumentException("choose exactly one of --check-config or --plan-routes");
            }
            return requested;
        }
    }
}
