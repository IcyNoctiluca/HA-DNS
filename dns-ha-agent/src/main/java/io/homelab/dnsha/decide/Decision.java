package io.homelab.dnsha.decide;

/** Output of Decider: what the Reconciler should converge Tailscale state toward. */
public enum Decision {
    ADVERTISE,
    WITHDRAW
}
