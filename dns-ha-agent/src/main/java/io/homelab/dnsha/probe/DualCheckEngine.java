package io.homelab.dnsha.probe;

import io.homelab.dnsha.config.BlockExpectation;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The dual resolve+filter check shared by DnsProbe (the active-path probe
 * against the local VIP, which drives the FSM) and BackendProbe (the
 * per-AGH-backend probe added in Phase 2, which only feeds /health and
 * /metrics — see PHASES.md Phase 2 "Important non-effect"). Extracted from
 * the original Phase 1 DnsProbe implementation so both probers share one
 * code path rather than risking behavioural drift between two hand-written
 * copies of the same resolve/filter semantics.
 *
 * Package-private: DnsProbe and BackendProbe are the only public entry
 * points; this is an implementation detail of both.
 *
 * A-RECORD ONLY (found/fixed at the config layer 2026-07-22): {@link #answersOf}
 * below only extracts {@code ARecord} instances from a response's ANSWER
 * section. probe.resolve_type is accepted by DnsProbe/BackendProbe as any
 * string dnsjava's {@code Type.value()} recognises, but this engine has never
 * actually supported anything other than "A" -- a real AAAA/MX/TXT response
 * would parse fine at the DNS level yet {@link #answersOf} would always
 * return an empty list for it, making {@link #checkResolve()} report failure
 * even on a fully successful query. ConfigLoader now rejects any
 * probe.resolve_type other than "A" at config-load time specifically because
 * of this gap (see its validate() method) -- if AAAA support is ever added,
 * extend this method to also collect {@code AAAARecord} addresses (and
 * update ConfigLoader's validation + BLUEPRINT.md §5 accordingly) rather than
 * just relaxing the config-level check.
 */
final class DualCheckEngine {

    private final Resolver resolver;
    private final int queryType;
    private final List<String> resolveNames;
    private final String blockName;
    private final BlockExpectation blockExpectation;

    DualCheckEngine(Resolver resolver, int queryType, List<String> resolveNames,
                     String blockName, BlockExpectation blockExpectation) {
        this.resolver = resolver;
        this.queryType = queryType;
        this.resolveNames = resolveNames;
        this.blockName = blockName;
        this.blockExpectation = blockExpectation;
    }

    ProbeOutcome probe() {
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
        for (String name : resolveNames) {
            try {
                Message response = query(name, queryType);
                if (response.getRcode() == Rcode.NOERROR && !answersOf(response).isEmpty()) {
                    return ResolveCheckResult.success();
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
            Message response = query(blockName, queryType);
            int rcode = response.getRcode();
            String rcodeName = Rcode.string(rcode);

            if (!blockExpectation.allowedRcodes().contains(rcodeName)) {
                return FilterCheckResult.fail("unexpected rcode " + rcodeName + " for block_name");
            }

            if (rcode == Rcode.NOERROR) {
                List<String> addresses = answersOf(response);
                for (String address : addresses) {
                    if (!blockExpectation.allowedIps().contains(address)) {
                        return FilterCheckResult.fail(
                            "block_name resolved to disallowed address " + address + " (filter bypass)");
                    }
                }
            }
            return FilterCheckResult.success();
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
        // Named success()/fail(), not ok()/fail() — a static factory named
        // ok() collides with the record's own auto-generated public instance
        // accessor `boolean ok()` for the `ok` component (same name, same
        // zero-arg signature, one static one not). javac rejects that as an
        // invalid accessor override, not an overload. Discovered by the
        // first real compile of this file (2026-07-21) — see PROGRESS.md.
        static ResolveCheckResult success() {
            return new ResolveCheckResult(true, null);
        }

        static ResolveCheckResult fail(String reason) {
            return new ResolveCheckResult(false, reason);
        }
    }

    private record FilterCheckResult(boolean ok, String reason) {
        // Same ok()-vs-accessor collision as ResolveCheckResult above.
        static FilterCheckResult success() {
            return new FilterCheckResult(true, null);
        }

        static FilterCheckResult fail(String reason) {
            return new FilterCheckResult(false, reason);
        }
    }
}
