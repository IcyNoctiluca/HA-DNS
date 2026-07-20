package io.homelab.dnsha.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Loads and validates /etc/dns-ha-agent/config.yaml. Config is read once at
 * startup — see AGENTS.md invariant #10 (no hot reload).
 *
 * On any failure (unreadable file, malformed YAML, missing required
 * fields, unsupported schema version) this throws ConfigException. App is
 * responsible for catching that and exiting with code 78 (EX_CONFIG) per
 * BLUEPRINT.md §5 "Schema versioning".
 */
public final class ConfigLoader {

    /** EX_CONFIG per sysexits.h — configuration error. */
    public static final int EXIT_CONFIG_ERROR = 78;

    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private ConfigLoader() {
    }

    public static Config loadFromFile(Path path) {
        String yaml;
        try {
            yaml = Files.readString(path);
        } catch (IOException e) {
            throw new ConfigException("could not read config file: " + path, e);
        }
        return loadFromString(yaml);
    }

    public static Config loadFromString(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new ConfigException("config file is empty");
        }
        Config config;
        try {
            config = buildMapper().readValue(yaml, Config.class);
        } catch (ConfigException e) {
            // Compact-constructor validation inside the records (e.g.
            // missing tailscale.managed_routes) surfaces as ConfigException
            // wrapped by Jackson; unwrap so callers see the real message.
            throw e;
        } catch (IOException e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof ConfigException ce) {
                    throw ce;
                }
                cause = cause.getCause();
            }
            throw new ConfigException("failed to parse config YAML: " + e.getMessage(), e);
        }
        validate(config);
        return config;
    }

    private static YAMLMapper buildMapper() {
        YAMLMapper mapper = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();
        mapper.registerModule(new JavaTimeModule());
        SimpleModule durationModule = new SimpleModule("HumanDuration");
        durationModule.addDeserializer(Duration.class, new HumanDurationDeserializer());
        mapper.registerModule(durationModule);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private static void validate(Config config) {
        if (config == null) {
            throw new ConfigException("config file is empty");
        }
        if (config.version() != SUPPORTED_SCHEMA_VERSION) {
            throw new ConfigException(
                "unsupported config schema version: " + config.version()
                    + " (this build supports version " + SUPPORTED_SCHEMA_VERSION + ")");
        }
        if (isBlank(config.node().name())) {
            throw new ConfigException("node.name is required");
        }
        if (isBlank(config.node().vip())) {
            throw new ConfigException("node.vip is required");
        }
        if (config.probe().resolveNames() == null || config.probe().resolveNames().isEmpty()) {
            throw new ConfigException("probe.resolve_names must have at least one entry");
        }
        if (isBlank(config.probe().blockName())) {
            throw new ConfigException("probe.block_name is required");
        }
        if (config.tailscale().managedRoutes() == null || config.tailscale().managedRoutes().isEmpty()) {
            throw new ConfigException("tailscale.managed_routes must have at least one entry");
        }
        String mode = config.tailscale().mode();
        if (!"cli".equals(mode) && !"noop".equals(mode)) {
            throw new ConfigException("tailscale.mode must be 'cli' or 'noop', got: " + mode);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
