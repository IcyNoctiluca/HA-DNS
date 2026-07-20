package io.homelab.dnsha.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the short human-friendly duration strings used throughout the YAML
 * schema (e.g. "2s", "60s", "500ms", "15m", "1h") — NOT java.time.Duration's
 * ISO-8601 "PT2S" format. See BLUEPRINT.md §5 for example config values.
 */
final class HumanDurationDeserializer extends JsonDeserializer<Duration> {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h)$");

    @Override
    public Duration deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        Matcher m = PATTERN.matcher(trimmed);
        if (!m.matches()) {
            throw new IOException(
                "invalid duration '" + text + "' — expected a number followed by ms/s/m/h, e.g. '2s'");
        }
        long value = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(value);
            case "s" -> Duration.ofSeconds(value);
            case "m" -> Duration.ofMinutes(value);
            case "h" -> Duration.ofHours(value);
            default -> throw new IOException("unreachable: unhandled duration unit in '" + text + "'");
        };
    }
}
