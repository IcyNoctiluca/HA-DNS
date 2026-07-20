package io.homelab.dnsha.tailscale;

/** Thrown by TailscaleClient implementations on any mutation/observation failure. */
public final class TailscaleException extends Exception {

    public TailscaleException(String message) {
        super(message);
    }

    public TailscaleException(String message, Throwable cause) {
        super(message, cause);
    }
}
