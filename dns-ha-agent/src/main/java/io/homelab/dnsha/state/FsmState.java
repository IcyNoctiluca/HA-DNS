package io.homelab.dnsha.state;

/**
 * Agent FSM states. See BLUEPRINT.md §4.
 *
 * PARTITIONED and the Decider-side partition logic that produces it are
 * Phase 3 scope (peer monitoring is not wired in before then, so in
 * Phase 1/2 this value is never emitted by FsmReducer — see FsmReducer's
 * javadoc). SHUTTING_DOWN is set directly by the lifecycle/shutdown path
 * (Phase 4), not by FsmReducer's probe-driven transitions.
 */
public enum FsmState {
    STARTING,
    HEALTHY,
    UNHEALTHY,
    PARTITIONED,
    SHUTTING_DOWN
}
