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
        // Real bug found 2026-07-22: ProbeConfig.resolveType is a free-form
        // string, and DnsProbe/BackendProbe both accept ANY value dnsjava's
        // Type.value() recognises (A, AAAA, MX, TXT, ...) with no complaint
        // at construction time. But DualCheckEngine.answersOf() is hardcoded
        // to extract only `ARecord` instances from the response — for any
        // other resolve_type, a query that succeeds at the DNS level (NOERROR
        // + real answers) would still be treated as a resolve-check FAILURE
        // forever, because the (correctly-typed, e.g. AAAARecord) answers
        // never match the `instanceof ARecord` filter. Per the fail-closed
        // design this fails safe (never advertises), but it's an extremely
        // confusing landmine: the agent would sit in STARTING (never
        // reaching HEALTHY) or flap to UNHEALTHY with no indication in any
        // log line that the actual root cause is "resolve_type isn't A, and
        // the code doesn't support anything else" rather than a real DNS/
        // filtering outage. Reject it at config-load time instead, with a
        // message that says exactly why. See DualCheckEngine's javadoc.
        if (!"A".equalsIgnoreCase(config.probe().resolveType())) {
            throw new ConfigException(
                "probe.resolve_type must be 'A' (got: " + config.probe().resolveType()
                    + "); DualCheckEngine only extracts A-record answers from DNS responses, so any "
                    + "other resolve_type would cause the resolve/filter checks to silently and "
                    + "permanently fail even when the underlying DNS query itself succeeds -- add "
                    + "AAAA/other record-type support to DualCheckEngine.answersOf() before allowing this");
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
