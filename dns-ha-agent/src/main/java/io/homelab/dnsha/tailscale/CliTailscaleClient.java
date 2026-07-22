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
 * JSON SHAPE CONFIRMED AGAINST UPSTREAM SOURCE (2026-07-21, no compiler or
 * real `tailscale` binary was available in that sandbox either, so this was
 * verified by reading tailscale/tailscale's actual Go source on GitHub
 * rather than running it — see PROGRESS.md for the session that did this):
 * currentlyAdvertised() reads `tailscale debug prefs` and parses its
 * "AdvertiseRoutes" JSON field. `cmd/tailscale/cli/debug.go` confirms
 * `debug prefs` is a real subcommand (`Name: "prefs", ... Exec: runPrefs`)
 * whose non-pretty path does exactly `json.MarshalIndent(prefs, "", "\t")`
 * on the `*ipn.Prefs` struct returned by `localClient.GetPrefs(ctx)`.
 * `ipn/prefs.go`'s `Prefs.AdvertiseRoutes []netip.Prefix` field has no
 * custom json tag and no `omitempty`, so it always serializes under the
 * literal key `"AdvertiseRoutes"` — confirming this code's field name is
 * correct — and, being a nil slice when no routes are advertised (no
 * `omitempty`), serializes as JSON `null` rather than being omitted,
 * matching this method's existing `routesNode.isNull()` → `Set.of()`
 * handling. Individual `netip.Prefix` entries marshal as CIDR strings
 * (e.g. "10.253.53.53/32") via their own `MarshalText`, matching
 * `n.asText()` below. `debug prefs` is still explicitly
 * undocumented/subject-to-change per Tailscale's own CLI help text, and
 * this is still not the same as observing real output from a real
 * `tailscaled` — that end-to-end confirmation still needs Phase 7's real
 * infra — but the specific "does this field/shape exist at all" risk
 * flagged by earlier sessions is now resolved with high confidence via
 * direct source inspection, not guessed past.
 *
 * SOCKET PATH (fixed 2026-07-22): every invocation now optionally prepends
 * `--socket=<path>` (see {@link #command(String...)}), sourced from
 * TailscaleConfig.socketPath / ansible's dns_ha_agent_tailscale_socket. Prior
 * to this, the class had no way to target a non-default socket path at all —
 * it relied entirely on the `tailscale` CLI's own hardcoded default
 * (`/var/run/tailscale/tailscaled.sock` on Linux) happening to match wherever
 * the compose template's bind-mount put the socket. That happened to be true
 * for every currently-committed example (defaults/main.yml and
 * host_vars/dns-ams-01.yml both use the default path), so this was never
 * exercised as a live bug, but the ansible role explicitly documents
 * dns_ha_agent_tailscale_socket as a per-node override ("Must match the
 * tailscaled install on the edge node") — a node with a non-standard install
 * location would have had every `tailscale set`/`debug prefs` call fail with
 * no config-level way to fix it. See TailscaleConfig's javadoc for the
 * upstream-confirmed reason `TS_SOCKET` (the env var docker-compose.yml.j2
 * also sets) doesn't help here either.
 */
public final class CliTailscaleClient implements TailscaleClient {

    private static final Logger log = LoggerFactory.getLogger(CliTailscaleClient.class);

    private final String cliPath;
    private final String socketPath;
    private final Duration commandTimeout;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * @deprecated in favour of {@link #CliTailscaleClient(String, String, Duration)}.
     * Retained so existing call sites/tests that don't care about a
     * non-default socket path don't need to change. Omitting socketPath
     * means every invocation relies on the `tailscale` binary's own
     * compiled-in default socket lookup — see TailscaleConfig's javadoc for
     * why this is NOT the same as setting the container's TS_SOCKET env var.
     */
    @Deprecated
    public CliTailscaleClient(String cliPath, Duration commandTimeout) {
        this(cliPath, null, commandTimeout);
    }

    public CliTailscaleClient(String cliPath, String socketPath, Duration commandTimeout) {
        this.cliPath = Objects.requireNonNull(cliPath, "cliPath");
        this.socketPath = (socketPath == null || socketPath.isBlank()) ? null : socketPath;
        this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
    }

    /** Builds `[cliPath, (--socket=path,) ...args]` — `--socket` is a root-level
     * persistent flag in the tailscale CLI, so it must precede the subcommand. */
    private List<String> command(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add(cliPath);
        if (socketPath != null) {
            command.add("--socket=" + socketPath);
        }
        command.addAll(List.of(args));
        return List.copyOf(command);
    }

    @Override
    public void advertiseRoutes(Set<String> routes) throws TailscaleException {
        String joined = routes.stream().sorted().collect(Collectors.joining(","));
        List<String> command = command("set", "--advertise-routes=" + joined);
        log.info("tailscale_cli_invoke", kv("command", command));
        runCommand(command);
    }

    @Override
    public Set<String> currentlyAdvertised() throws TailscaleException {
        List<String> command = command("debug", "prefs");
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
     *
     * Waits for exit (bounded by commandTimeout) BEFORE reading
     * stdout/stderr. Originally this read the streams first via
     * readAllBytes() and only called waitFor() afterwards — but
     * readAllBytes() blocks until the stream hits EOF, which for a child
     * process only happens on exit. That meant a hung/slow child was
     * effectively waited on with no timeout at all: readAllBytes() would
     * block for however long the process actually took, and by the time
     * waitFor(commandTimeout) finally ran the process had already exited,
     * so it always returned true — the timeout branch was unreachable in
     * practice. Caught by the first real test run of this class
     * (commandTimeoutThrowsTailscaleException, 2026-07-21 — see
     * PROGRESS.md); in production this would have meant a hung `tailscale
     * set`/`tailscale debug prefs` blocked this thread indefinitely
     * instead of failing fast. Reading stdout/stderr only after the
     * process has already exited (or been forcibly killed) is safe here
     * because output is small (well under any pipe-buffer deadlock
     * threshold) — same assumption as before, just applied in the
     * correct order.
     */
    private String runCommand(List<String> command) throws TailscaleException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new TailscaleException("failed to start process: " + command, e);
        }

        boolean finished;
        try {
            finished = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TailscaleException("interrupted running command: " + command, e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new TailscaleException("command timed out after " + commandTimeout + ": " + command);
        }

        String stdout;
        String stderr;
        try {
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TailscaleException("I/O error reading command output: " + command, e);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new TailscaleException(
                "command " + command + " exited " + exitCode + ": " + stderr.strip());
        }
        return stdout;
    }
}
