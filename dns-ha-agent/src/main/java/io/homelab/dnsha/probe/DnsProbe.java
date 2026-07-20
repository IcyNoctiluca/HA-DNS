package io.homelab.dnsha.probe;

import io.homelab.dnsha.config.BlockExpectation;
import io.homelab.dnsha.config.ProbeConfig;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dual-check probe against the local VIP (10.253.53.53 via dnsdist), per
 * BLUEPRINT.md §3.
 *
 * Resolve check: passes if AT LEAST ONE of probe.resolve_names returns a
 * valid answer.
 * Filter check: passes if the sentinel block name's rcode is in
 * allowed_rcodes AND, when NOERROR, every returned address is in
 * allowed_ips — fails if any returned routable address is not allowed
 * (a real public IP for the sentinel is always a failure: filter bypass).
 *
 * AGENTS.md invariant #5: this class's own DNS queries are exactly the
 * probe against config.probe().target() (the VIP) — that is the intended
 * subject under test, not a bootstrap-circularity violation. Nothing else
 * in this class resolves hostnames through the VIP.
 */
public final class DnsProbe {

    private final ProbeConfig config;
    private final Resolver resolver;
    private final int queryType;

    public DnsProbe(ProbeConfig config) {
        this(config, buildResolver(config));
    }

    /** Package-visible constructor for tests to inject a mocked Resolver. */
    DnsProbe(ProbeConfig config, Resolver resolver) {
        this.config = config;
        this.resolver = resolver;
        int type = Type.value(config.resolveType());
        if (type == -1) {
            throw new IllegalArgumentException("unknown probe.resolve_type: " + config.resolveType());
        }
        this.queryType = type;
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
        Instant start = Instant.now();
        ResolveCheckResult resolveResult = checkResolve();
        FilterCheckResult filterResult = checkFilter();
        Duration duration = Duration.between(start, Instant.now());

        String failureReason = null;
        if (!resolveResult.ok()) {
            failureReason = "resolve: " + resolveResult.reason();
        } else if (!filterResult.ok()) {
            failureReason = "filter: " + filterResult.reason();
        }

        return new ProbeOutcome(resolveResult.ok(), filterResult.ok(), failureReason, duration);
    }

    private ResolveCheckResult checkResolve() {
        List<String> failures = new ArrayList<>();
        for (String name : config.resolveNames()) {
            try {
                Message response = query(name, queryType);
                if (response.getRcode() == Rcode.NOERROR && !answersOf(response).isEmpty()) {
                    return ResolveCheckResult.ok();
                }
                failures.add(name + "=" + Rcode.string(response.getRcode()));
            } catch (IOException e) {
                failures.add(name + "=error:" + e.getMessage());
            }
        }
        return ResolveCheckResult.fail("all resolve_names failed: " + String.join(", ", failures));
    }

    private FilterCheckResult checkFilter() {
        try {
            Message response = query(config.blockName(), queryType);
            int rcode = response.getRcode();
            String rcodeName = Rcode.string(rcode);
            BlockExpectation expectation = config.blockExpectation();

            if (!expectation.allowedRcodes().contains(rcodeName)) {
                return FilterCheckResult.fail("unexpected rcode " + rcodeName + " for block_name");
            }

            if (rcode == Rcode.NOERROR) {
                List<String> addresses = answersOf(response);
                for (String address : addresses) {
                    if (!expectation.allowedIps().contains(address)) {
                        return FilterCheckResult.fail(
                            "block_name resolved to disallowed address " + address + " (filter bypass)");
                    }
                }
            }
            return FilterCheckResult.ok();
        } catch (IOException e) {
            return FilterCheckResult.fail("query error: " + e.getMessage());
        }
    }

    private Message query(String name, int type) throws IOException {
        Name qname;
        try {
            qname = Name.fromString(ensureAbsolute(name));
        } catch (TextParseException e) {
            throw new IOException("invalid name: " + name, e);
        }
        Record question = Record.newRecord(qname, type, DClass.IN);
        Message query = Message.newQuery(question);
        return resolver.send(query);
    }

    private static String ensureAbsolute(String name) {
        return name.endsWith(".") ? name : name + ".";
    }

    private static List<String> answersOf(Message response) {
        List<String> addresses = new ArrayList<>();
        for (Record r : response.getSection(Section.ANSWER)) {
            if (r instanceof ARecord a) {
                addresses.add(a.getAddress().getHostAddress());
            }
        }
        return addresses;
    }

    private record ResolveCheckResult(boolean ok, String reason) {
        static ResolveCheckResult ok() {
            return new ResolveCheckResult(true, null);
        }

        static ResolveCheckResult fail(String reason) {
            return new ResolveCheckResult(false, reason);
        }
    }

    private record FilterCheckResult(boolean ok, String reason) {
        static FilterCheckResult ok() {
            return new FilterCheckResult(true, null);
        }

        static FilterCheckResult fail(String reason) {
            return new FilterCheckResult(false, reason);
        }
    }
}
