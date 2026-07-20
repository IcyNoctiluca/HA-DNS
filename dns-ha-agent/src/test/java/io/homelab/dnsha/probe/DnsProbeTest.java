package io.homelab.dnsha.probe;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DnsProbeTest {

    private static ProbeConfig configWith(List<String> resolveNames, String blockName) {
        return new ProbeConfig(
            "10.253.53.53",
            resolveNames,
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

    /** Routes resolver.send(query) to a canned response keyed by queried name. */
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
    void resolvePassesIfAtLeastOneNameResolves() throws Exception {
        ProbeConfig config = configWith(List.of("dead.example.", "cloudflare.com."), "sentinel.example.");
        Resolver resolver = mockedResolver(Map.of(
            "dead.example.", rcodeResponse(Rcode.NXDOMAIN),
            "cloudflare.com.", okResponse("cloudflare.com.", "1.1.1.1"),
            "sentinel.example.", rcodeResponse(Rcode.NXDOMAIN)));

        ProbeOutcome outcome = new DnsProbe(config, resolver).probe();

        assertThat(outcome.resolveOk()).isTrue();
    }

    @Test
    void resolveFailsOnlyIfAllNamesFail() throws Exception {
        ProbeConfig config = configWith(List.of("dead1.example.", "dead2.example."), "sentinel.example.");
        Resolver resolver = mockedResolver(Map.of(
            "dead1.example.", rcodeResponse(Rcode.NXDOMAIN),
            "dead2.example.", rcodeResponse(Rcode.SERVFAIL),
            "sentinel.example.", rcodeResponse(Rcode.NXDOMAIN)));

        ProbeOutcome outcome = new DnsProbe(config, resolver).probe();

        assertThat(outcome.resolveOk()).isFalse();
    }

    @Test
    void filterPassesOnNxdomain() throws Exception {
        ProbeConfig config = configWith(List.of("cloudflare.com."), "sentinel.example.");
        Resolver resolver = mockedResolver(Map.of(
            "cloudflare.com.", okResponse("cloudflare.com.", "1.1.1.1"),
            "sentinel.example.", rcodeResponse(Rcode.NXDOMAIN)));

        ProbeOutcome outcome = new DnsProbe(config, resolver).probe();

        assertThat(outcome.filterOk()).isTrue();
    }

    @Test
    void filterPassesOnNoErrorWithAllowedSentinelIp() throws Exception {
        ProbeConfig config = configWith(List.of("cloudflare.com."), "sentinel.example.");
        Resolver resolver = mockedResolver(Map.of(
            "cloudflare.com.", okResponse("cloudflare.com.", "1.1.1.1"),
            "sentinel.example.", okResponse("sentinel.example.", "0.0.0.0")));

        ProbeOutcome outcome = new DnsProbe(config, resolver).probe();

        assertThat(outcome.filterOk()).isTrue();
    }

    @Test
    void filterFailsWhenRealPublicIpReturnedForBlockName() throws Exception {
        ProbeConfig config = configWith(List.of("cloudflare.com."), "sentinel.example.");
        Resolver resolver = mockedResolver(Map.of(
            "cloudflare.com.", okResponse("cloudflare.com.", "1.1.1.1"),
            "sentinel.example.", okResponse("sentinel.example.", "203.0.113.42"))); // real-looking public IP

        ProbeOutcome outcome = new DnsProbe(config, resolver).probe();

        assertThat(outcome.filterOk()).isFalse();
        assertThat(outcome.failureReason()).contains("disallowed address");
    }

    @Test
    void filterFailsOnDisallowedRcode() throws Exception {
        ProbeConfig config = configWith(List.of("cloudflare.com."), "sentinel.example.");
        Resolver resolver = mockedResolver(Map.of(
            "cloudflare.com.", okResponse("cloudflare.com.", "1.1.1.1"),
            "sentinel.example.", rcodeResponse(Rcode.SERVFAIL)));

        ProbeOutcome outcome = new DnsProbe(config, resolver).probe();

        assertThat(outcome.filterOk()).isFalse();
    }

    @Test
    void queryTimeoutCountsAsFailure() throws Exception {
        ProbeConfig config = configWith(List.of("cloudflare.com."), "sentinel.example.");
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any())).thenThrow(new IOException("query timed out"));

        ProbeOutcome outcome = new DnsProbe(config, resolver).probe();

        assertThat(outcome.resolveOk()).isFalse();
        assertThat(outcome.filterOk()).isFalse();
        assertThat(outcome.success()).isFalse();
    }

    @Test
    void overallSuccessRequiresBothResolveAndFilterOk() throws Exception {
        ProbeConfig config = configWith(List.of("cloudflare.com."), "sentinel.example.");
        Resolver resolver = mockedResolver(Map.of(
            "cloudflare.com.", okResponse("cloudflare.com.", "1.1.1.1"),
            "sentinel.example.", okResponse("sentinel.example.", "0.0.0.0")));

        ProbeOutcome outcome = new DnsProbe(config, resolver).probe();

        assertThat(outcome.success()).isTrue();
    }
}
