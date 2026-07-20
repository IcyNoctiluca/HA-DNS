package io.homelab.dnsha;

import io.homelab.dnsha.config.BackendConfig;
import io.homelab.dnsha.config.Config;
import io.homelab.dnsha.config.ConfigException;
import io.homelab.dnsha.config.ConfigLoader;
import io.homelab.dnsha.decide.Decision;
import io.homelab.dnsha.http.HealthServer;
import io.homelab.dnsha.metrics.Metrics;
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
 * backend. Still no peer monitor (Phase 3), no WatchdogThread / hardened
 * startup reconciliation / graceful SIGTERM handling (Phase 4 — this class
 * has a minimal best-effort shutdown hook only, noted below). The agent is
 * fully functional via logs + /health + /metrics per PHASES.md Phase 1/2
 * acceptance criteria.
 */
public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);
    private static final Path DEFAULT_CONFIG_PATH = Path.of("/etc/dns-ha-agent/config.yaml");

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
            log.error("uncaught_exception", kv("thread", thread.getName()), kv("error", throwable.toString()), throwable));

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
        LoggingBootstrap.apply(config.logging());

        log.info("dns_ha_agent_starting",
            kv("node", config.node().name()), kv("region", config.node().region()), kv("vip", config.node().vip()));

        TailscaleClient tailscaleClient = createTailscaleClient(config, cliArgs.allowNoopTailscale());
        Metrics metrics = new Metrics();
        Reconciler reconciler = new Reconciler(tailscaleClient, metrics, config);
        HealthState healthState = new HealthState(config);
        DnsProbe probe = new DnsProbe(config.probe());
        ProbeLoop probeLoop = new ProbeLoop(probe, healthState, config, reconciler, metrics);

        List<BackendProbeLoop> backendProbeLoops = startBackendProbeLoops(config, healthState, metrics);

        Instant startedAt = Instant.now();
        HealthServer healthServer = new HealthServer(config, healthState, reconciler, metrics, startedAt);
        healthServer.start();

        // Minimal best-effort shutdown path. This is NOT the hardened
        // graceful-SIGTERM handler from BLUEPRINT.md §8 (transition to
        // SHUTTING_DOWN, wait up to 5s for withdraw confirmation, drain
        // HTTP, exit 0) — that's Phase 4 scope (lifecycle/ShutdownHook).
        // Attempting a withdraw here is strictly better than doing nothing
        // on SIGTERM in the meantime, and does not weaken the fail-closed
        // startup-reconciliation story: a new instance always re-validates
        // via STARTING before it will ADVERTISE again regardless of
        // whether this hook ran.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutdown_hook_running", kv("node", config.node().name()));
            probeLoop.stop();
            backendProbeLoops.forEach(BackendProbeLoop::stop);
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

        Thread.ofVirtual().name("probe-loop").start(probeLoop);

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
        return new CliTailscaleClient(config.tailscale().cliPath(), config.tailscale().commandTimeout());
    }

    private static void awaitForever() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Thrown for startup failures that are not config-parse errors (e.g. the noop-mode gate). */
    static final class FatalStartupException extends Exception {
        private final int exitCode;

        FatalStartupException(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }

        int exitCode() {
            return exitCode;
        }
    }

    /** Minimal `--config PATH` / `--allow-noop-tailscale` parsing. */
    record CliArgs(Path configPath, boolean allowNoopTailscale) {
        static CliArgs parse(String[] args) {
            Path configPath = DEFAULT_CONFIG_PATH;
            boolean allowNoop = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--config" -> {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("--config requires a path argument");
                        }
                        configPath = Path.of(args[++i]);
                    }
                    case "--allow-noop-tailscale" -> allowNoop = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            return new CliArgs(configPath, allowNoop);
        }
    }
}
