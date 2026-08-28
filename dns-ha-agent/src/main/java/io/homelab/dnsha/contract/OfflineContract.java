package io.homelab.dnsha.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.homelab.dnsha.App.CliMode;
import io.homelab.dnsha.FatalStartupException;
import io.homelab.dnsha.config.Config;

import java.io.IOException;

/** Emits producer-owned, side-effect-free deployment contract results. */
public final class OfflineContract {

    private static final int EXIT_IO_ERROR = 74; // EX_IOERR
    private static final ObjectMapper JSON = new ObjectMapper();

    private OfflineContract() {
    }

    public static void write(Config config, CliMode mode) throws FatalStartupException {
        Object result = switch (mode) {
            case CHECK_CONFIG -> new ConfigCheckResult(true, config.version(), config.node().name());
            case PLAN_ROUTES -> RoutePlan.from(config);
            case RUN -> throw new IllegalArgumentException("RUN is not an offline contract command");
        };

        try {
            JSON.writeValue(System.out, result);
        } catch (IOException e) {
            throw new FatalStartupException("could not write offline contract result: " + e.getMessage(), EXIT_IO_ERROR);
        }
    }
}
