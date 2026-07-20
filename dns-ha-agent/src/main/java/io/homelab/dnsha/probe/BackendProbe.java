package io.homelab.dnsha.probe;

import io.homelab.dnsha.config.BackendConfig;
import io.homelab.dnsha.config.ProbeConfig;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.net.UnknownHostException;
import java.time.Duration;

/**
 * Per-AGH-backend dual probe (Phase 2). Same resolve+filter semantics as
 * DnsProbe (shared via DualCheckEngine), but targeted directly at one
 * configured AGH backend (e.g. "agh-ams-01" at "127.0.0.1:5353") instead of
 * the VIP.
 *
 * IMPORTANT non-effect (PHASES.md Phase 2): results from this probe are
 * NEVER fed into FsmReducer/Decider/Reconciler. They are written to
 * HealthState.recordBackendProbeResult(...) purely for /health and
 * /metrics visibility — see BackendProbeLoop.
 *
 * Reuses probe.resolve_names / probe.resolve_type / probe.block_name /
 * probe.block_expectation from the top-level ProbeConfig: the sentinel
 * definitions are global properties of "what filtering must look like",
 * not per-backend config, so there is intentionally no separate
 * per-backend sentinel schema.
 */
public final class BackendProbe {

    private final BackendConfig backend;
    private final DualCheckEngine engine;

    public BackendProbe(BackendConfig backend, ProbeConfig probeConfig, Duration queryTimeout) {
        this(backend, probeConfig, buildResolver(backend, queryTimeout));
    }

    /** Package-visible constructor for tests to inject a mocked Resolver. */
    BackendProbe(BackendConfig backend, ProbeConfig probeConfig, Resolver resolver) {
        this.backend = backend;
        int type = Type.value(probeConfig.resolveType());
        if (type == -1) {
            throw new IllegalArgumentException("unknown probe.resolve_type: " + probeConfig.resolveType());
        }
        this.engine = new DualCheckEngine(
            resolver, type, probeConfig.resolveNames(), probeConfig.blockName(), probeConfig.blockExpectation());
    }

    /**
     * backend.address() is "host:port" (e.g. "127.0.0.1:5353" or
     * "100.64.x.y:5353") — AGH backends listen on a non-standard DNS port,
     * unlike probe.target which is the VIP on port 53 via dnsdist.
     */
    private static Resolver buildResolver(BackendConfig backend, Duration queryTimeout) {
        String address = backend.address();
        String host = address;
        int port = 53;
        int idx = address.lastIndexOf(':');
        if (idx > 0) {
            host = address.substring(0, idx);
            try {
                port = Integer.parseInt(address.substring(idx + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "invalid backend_probe.backends[].address (bad port): " + address, e);
            }
        }
        try {
            SimpleResolver r = new SimpleResolver(host);
            r.setPort(port);
            r.setTimeout(queryTimeout);
            return r;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(
                "invalid backend_probe.backends[].address (bad host): " + address, e);
        }
    }

    public ProbeOutcome probe() {
        return engine.probe();
    }

    public String name() {
        return backend.name();
    }

    public String address() {
        return backend.address();
    }
}
