package io.homelab.dnsha;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.homelab.dnsha.config.LoggingConfig;
import org.slf4j.LoggerFactory;

/**
 * Applies config.logging().level() to the root logger at startup. Format
 * (JSON vs PLAIN) is NOT switched at runtime in Phase 1 — logback.xml
 * always emits JSON (the production value per AGENTS.md invariant #9).
 * `logging.format: PLAIN` is accepted by ConfigLoader for schema
 * completeness but currently has no effect; wiring an actual runtime
 * encoder swap is deferred until it's needed for local dev ergonomics.
 */
final class LoggingBootstrap {

    private LoggingBootstrap() {
    }

    static void apply(LoggingConfig logging) {
        Level level = parseLevel(logging.level());
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).setLevel(level);
    }

    private static Level parseLevel(String configuredLevel) {
        Level level = Level.toLevel(configuredLevel, null);
        return level != null ? level : Level.INFO;
    }
}
