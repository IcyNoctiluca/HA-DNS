package io.homelab.dnsha.config;

/**
 * Thrown for any config load/parse/validation failure. Callers (App.main)
 * catch this and exit with code 78 (EX_CONFIG per sysexits.h), per
 * BLUEPRINT.md §5 "Schema versioning" and AGENTS.md invariant on config
 * handling.
 */
public final class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
