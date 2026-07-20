package io.homelab.dnsha.probe;

import io.homelab.dnsha.config.ProbeConfig;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.net.UnknownHostException;

/**
 * Dual-check probe against the local VIP (10.253.53.53 via dnsdist), per
 * BLUEPRINT.md §3. This is the ONLY probe whose result drives the FSM
 * (AGENTS.md invariant #5: this class's own DNS queries are exactly the
 * probe against config.probe().target() (the VIP) — that is the intended
 * subject under test, not a bootstrap-circularity violation).
 *
 * The actual resolve+filter logic lives in DualCheckEngine (Phase 2 —
 * extracted so BackendProbe can share it verbatim instead of risking a
 * hand-copied second implementation drifting from this one). This class's
 * public API (constructor, probe()) and the package-visible
 * DnsProbe(config, resolver) test-injection constructor are unchanged from
 * Phase 1, so DnsProbeTest requires no changes.
 */
public final class DnsProbe {

    private final DualCheckEngine engine;

    public DnsProbe(ProbeConfig config) {
        this(config, buildResolver(config));
    }

    /** Package-visible constructor for tests to inject a mocked Resolver. */
    DnsProbe(ProbeConfig config, Resolver resolver) {
        int type = Type.value(config.resolveType());
        if (type == -1) {
            throw new IllegalArgumentException("unknown probe.resolve_type: " + config.resolveType());
        }
        this.engine = new DualCheckEngine(
            resolver, type, config.resolveNames(), config.blockName(), config.blockExpectation());
    }

    private static Resolver buildResolver(ProbeConfig config) {
        try {
            SimpleResolver r = new SimpleResolver(config.target());
            r.setTimeout(config.queryTimeout());
            return r;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid probe.target: " + config.target(), e);
        }
    }

    public ProbeOutcome probe() {
        return engine.probe();
    }
}
