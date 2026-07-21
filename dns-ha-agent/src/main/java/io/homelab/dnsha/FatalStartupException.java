package io.homelab.dnsha;

/**
 * Thrown for startup failures that are not config-parse errors (which use
 * {@link io.homelab.dnsha.config.ConfigException} and exit 78 instead — see
 * {@link io.homelab.dnsha.config.ConfigLoader#EXIT_CONFIG_ERROR}).
 *
 * Examples: the {@code tailscale.mode=noop} production-safety gate
 * (App.createTailscaleClient), and — as of Phase 4 —
 * {@link io.homelab.dnsha.lifecycle.StartupReconciliation} failing to
 * observe current Tailscale state at startup (BLUEPRINT.md §8 error table:
 * "Tailscale CLI/socket unavailable at startup in production cli mode ->
 * Log fatal error and exit non-zero").
 *
 * Moved to a top-level public class in Phase 4 (was a package-private
 * nested class inside App) so io.homelab.dnsha.lifecycle classes can throw
 * it without a package-visibility workaround.
 */
public final class FatalStartupException extends Exception {

    private final int exitCode;

    public FatalStartupException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
