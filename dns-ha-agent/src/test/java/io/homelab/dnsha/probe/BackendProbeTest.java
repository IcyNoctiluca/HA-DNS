package io.homelab.dnsha.probe;

import io.homelab.dnsha.config.BackendConfig;
import io.homelab.dnsha.config.BlockExpectation;
import io.homelab.dnsha.config.ProbeConfig;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mirrors DnsProbeTest's resolve/filter assertions (both now share
 * DualCheckEngine — see BackendProbe's javadoc), plus BackendProbe-specific
 * coverage: the "IMPORTANT non-effect" contract (its outcome type carries
 * no FSM/decision information — that's enforced structurally by
 * ProbeOutcome/BackendProbeLoop, not by this class) and address parsing.
 */
class BackendProbeTest {

    private static ProbeConfig probeConfig(String blockName) {
        return new ProbeConfig(
            "10.253.53.53",
            List.of("cloudflare.com."),
            "A",
            blockName,
            new BlockExpectation("must_not_return_real_ip",
                List.of("NXDOMAIN", "REFUSED", "NOERROR"), List.of("0.0.0.0")),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            2, 3);
    }

    private static Message okResponse(String qname, String address) throws Exception {
        Message response = new Message();
        response.getHeader().setRcode(Rcode.NOERROR);
        Name name = Name.fromString(qname.endsWith(".") ? qname : qname + ".");
        Record record = new ARecord(name, DClass.IN, 60, InetAddress.getByName(address));
        response.addRecord(record, Section.ANSWER);
        return response;
    }

    private static Message rcodeResponse(int rcode) {
        Message response = new Message();
        response.getHeader().setRcode(rcode);
        return response;
    }

    private static Resolver mockedResolver(Map<String, Message> responsesByName) throws IOException {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any())).thenAnswer(invocation -> {
            Message query = invocation.getArgument(0);
            String qname = query.getQuestion().getName().toString();
            Message response = responsesByName.get(qname);
            if (response == null) {
                throw new IOException("no canned response for " + qname);
            }
            return response;
        });
        return resolver;
    }

    @Test
    void reportsBackendNameAndAddress() {
        BackendConfig backend = new BackendConfig("agh-iom-01", "100.64.1.2:5353");
        BackendProbe probe = new BackendProbe(backend, probeConfig("sentinel.example."), mock(Resolver.class));

        assertThat(probe.name()).isEqualTo("agh-iom-01");
        assertThat(probe.address()).isEqualTo("100.64.1.2:5353");
    }

    @Test
    void probeSucceedsWhenResolveAndFilterBothOk() throws Exception {
        BackendConfig backend = new BackendConfig("agh-ams-01", "127.0.0.1:5353");
        Resolver resolver = mockedResolver(Map.of(
            "cloudflare.com.", okResponse("cloudflare.com.", "1.1.1.1"),
            "sentinel.example.", rcodeResponse(Rcode.NXDOMAIN)));

        ProbeOutcome outcome = new BackendProbe(backend, probeConfig("sentinel.example."), resolver).probe();

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.resolveOk()).isTrue();
        assertThat(outcome.filterOk()).isTrue();
    }

    @Test
    void probeFailsFilterWhenBlockNameLeaksRealIp() throws Exception {
        BackendConfig backend = new BackendConfig("agh-iom-01", "100.64.1.2:5353");
        Resolver resolver = mockedResolver(Map.of(
            "cloudflare.com.", okResponse("cloudflare.com.", "1.1.1.1"),
            "sentinel.example.", okResponse("sentinel.example.", "203.0.113.42")));

        ProbeOutcome outcome = new BackendProbe(backend, probeConfig("sentinel.example."), resolver).probe();

        assertThat(outcome.filterOk()).isFalse();
        assertThat(outcome.success()).isFalse();
    }

    @Test
    void constructorRejectsUnparseablePort() {
        BackendConfig backend = new BackendConfig("agh-bad", "127.0.0.1:not-a-port");

        assertThatThrownBy(() ->
            new BackendProbe(backend, probeConfig("sentinel.example."), Duration.ofSeconds(2)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bad port");
    }

    @Test
    void constructorAcceptsAddressWithoutExplicitPort() {
        // No colon at all -> defaults to port 53, host = the whole string.
        BackendConfig backend = new BackendConfig("agh-default-port", "127.0.0.1");

        BackendProbe probe = new BackendProbe(backend, probeConfig("sentinel.example."), Duration.ofSeconds(2));

        assertThat(probe.address()).isEqualTo("127.0.0.1");
    }
}
