package io.homelab.dnsha.config;

/** Observability: log format/level. See BLUEPRINT.md §5, §8 Logging. */
public record LoggingConfig(
    String format,
    String level
) {
    public LoggingConfig {
        if (format == null || format.isBlank()) {
            format = "JSON";
        }
        if (level == null || level.isBlank()) {
            level = "INFO";
        }
    }
}
