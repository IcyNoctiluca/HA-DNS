package io.homelab.dnsha.config;

import java.util.List;

/**
 * How the filter (block-sentinel) probe interprets a DNS response.
 * See BLUEPRINT.md §5 for the "must_not_return_real_ip" semantics.
 */
public record BlockExpectation(
    String mode,
    List<String> allowedRcodes,
    List<String> allowedIps
) {
    public BlockExpectation {
        if (allowedRcodes == null) {
            allowedRcodes = List.of("NXDOMAIN", "REFUSED", "NOERROR");
        }
        if (allowedIps == null) {
            allowedIps = List.of("0.0.0.0");
        }
        if (mode == null || mode.isBlank()) {
            mode = "must_not_return_real_ip";
        }
    }
}
