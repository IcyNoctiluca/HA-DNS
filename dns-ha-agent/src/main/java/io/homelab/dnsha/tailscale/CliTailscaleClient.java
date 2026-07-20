package io.homelab.dnsha.tailscale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Production TailscaleClient: subprocesses the `tailscale` CLI. See
 * AGENTS.md "Things that look like good ideas but break the contract" —
 * v1 deliberately uses subprocess rather than the LocalAPI unix socket
 * directly.
 *
 * OPEN QUESTION (flagged, not guessed past): currentlyAdvertised() reads
 * `tailscale debug prefs` and parses its "AdvertiseRoutes" JSON field.
 * `debug prefs` is a real, widely-relied-upon Tailscale CLI subcommand
 * that dumps ipn.Prefs as JSON, but it is explicitly undocumented/subject
 * to change, and this sandbox has no real `tailscale` binary or tailscaled
 * socket to verify actual output against. If this assumption is wrong,
 * dns_ha_agent_fail_open detection (Reconciler) could be inaccurate — the
 * single most dangerous failure class per BLUEPRINT.md §6. This needs
 * verification against a real tailscaled before Phase 7's production
 * acceptance gate; noted in PROGRESS.md.
 */
public final class CliTailscaleClient implements TailscaleClient {

    private static final Logger log = LoggerFactory.getLogger(CliTailscaleClient.class);

    private final String cliPath;
    private final Duration commandTimeout;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public CliTailscaleClient(String cliPath, Duration commandTimeout) {
        this.cliPath = Objects.requireNonNull(cliPath, "cliPath");
        this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
    }

    @Override
    public void advertiseRoutes(Set<String> routes) throws TailscaleException {
        String joined = routes.stream().sorted().collect(Collectors.joining(","));
        List<String> command = List.of(cliPath, "set", "--advertise-routes=" + joined);
        log.info("tailscale_cli_invoke", kv("command", command));
        runCommand(command);
    }

    @Override
    public Set<String> currentlyAdvertised() throws TailscaleException {
        List<String> command = List.of(cliPath, "debug", "prefs");
        String stdout = runCommand(command);
        try {
            JsonNode root = jsonMapper.readTree(stdout);
            JsonNode routesNode = root.path("AdvertiseRoutes");
            if (routesNode.isMissingNode() || routesNode.isNull()) {
                return Set.of();
            }
            Set<String> result = new LinkedHashSet<>();
            routesNode.forEach(n -> result.add(n.asText()));
            return Set.copyOf(result);
        } catch (IOException e) {
            throw new TailscaleException("failed to parse `tailscale debug prefs` output: " + e.getMessage(), e);
        }
    }

    /**
     * Runs the command to completion, returning stdout. Throws
     * TailscaleException on a non-zero exit, I/O failure, or timeout.
     * Reads stdout/stderr fully before waiting on exit — safe here because
     * `tailscale set` / `tailscale debug prefs` output is small (well
     * under any pipe-buffer deadlock threshold).
     */
    private String runCommand(List<String> command) throws TailscaleException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new TailscaleException("failed to start process: " + command, e);
        }

        String stdout;
        String stderr;
        boolean finished;
        try {
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            finished = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            throw new TailscaleException("I/O error running command: " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TailscaleException("interrupted running command: " + command, e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new TailscaleException("command timed out after " + commandTimeout + ": " + command);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new TailscaleException(
                "command " + command + " exited " + exitCode + ": " + stderr.strip());
        }
        return stdout;
    }
}
