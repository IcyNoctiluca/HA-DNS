# HA DNS — portable system design

> This document defines portable behavior and safety properties. Concrete
> hosts, sites, addresses, tags, firewall rules, supervisors, credentials and
> rollout commands belong to the consuming infrastructure repository. The
> repository interface is described in
> [`REPOSITORY_BOUNDARY.md`](REPOSITORY_BOUNDARY.md).

## Context and goals

Tailnet clients need one stable resolver address that remains available across
edge and filtering-backend failures. Availability must not silently bypass
filtering: if no filtering-capable path is healthy, resolution fails closed.

The design therefore aims to provide:

- one consumer-selected `/32` service address advertised by multiple edges;
- edge-local withdrawal when the complete serving path becomes unhealthy;
- health checks that prove both ordinary resolution and filtering;
- independent startup without a cluster control plane, scheduler, datastore,
  overlay network or cluster DNS;
- immutable, source-identifiable agent releases; and
- exact rollback through a previously admitted image digest.

LAN DNS and tailnet DNS are separate systems. A consumer may continue serving
LAN clients through an existing resolver while introducing this design for
tailnet clients. Coupling the two paths is an optional later deployment choice,
not part of the producer contract.

## Architecture

Each route-advertising edge contains four logical capabilities:

1. A DNS entry point bound to the consumer-owned service address.
2. One or more filtering resolvers, admitted only when they resolve normal
   names and enforce the controlled filtering sentinel.
3. A recursive or otherwise policy-approved upstream path.
4. `dns-ha-agent`, which probes the complete local path and reconciles only the
   routes explicitly assigned to it.

The consumer may implement those capabilities with any software or supervisor
that preserves the published behavior. The producer does not prescribe
dnsdist, AdGuard Home, Unbound, systemd, containers, Kubernetes, launchd, UFW,
nftables or a particular topology.

Clients send queries to the stable service address. The tailnet routes each
query to an advertising edge. The edge selects a healthy filtering backend and
returns the filtered answer. If the edge cannot prove that path, its local
agent withdraws the managed route while preserving every consumer-owned route.

## Safety invariants

1. Every edge in one deployment advertises the same consumer-selected `/32`.
2. An edge never accepts DNS from the service it is providing.
3. A responsive but unfiltered backend is unhealthy.
4. There is no unfiltered public-resolver fallback.
5. The agent is the sole local writer of its managed route advertisement.
6. Withdrawal preserves every route outside the agent's declared managed set.
7. Startup reconciles observed route state before declaring the edge healthy.
8. Management listeners use a declared non-wildcard bind, host-firewall
   defence in depth and tailnet source policy.
9. The DNS serving path remains available without a cluster control plane.
10. Consumers deploy immutable image digests and verify matching source and
    contract labels before granting route-mutation capability.

## Health and failover behavior

The local probe covers the complete client-serving path. It must prove:

- at least one ordinary name resolves with the expected record type;
- a controlled public sentinel is blocked in an allowed fail-closed form; and
- the query completes within the consumer's declared thresholds.

Optional direct backend probes expose latent filtering drift before the active
backend changes. A backend that resolves the sentinel's real public answer is
never eligible merely because it responds quickly.

When the local path crosses the configured failure threshold, the agent's
desired state becomes `WITHDRAW`. When it crosses the recovery threshold, the
desired state becomes `ADVERTISE`. Hysteresis prevents one transient query from
flapping the route. The exact state machine, health schema, metrics and route
planner are specified in
[`dns-ha-agent/BLUEPRINT.md`](dns-ha-agent/BLUEPRINT.md).

Tailscale's node-level routing provides the first failover layer. The agent is
the second: it removes an unhealthy but still-connected edge. An independent
consumer-owned controller may provide a third layer by fencing centrally
approved routes when an edge remains advertised despite wanting withdrawal.
That controller consumes the agent's published health/metrics plus an external
DNS probe; its credentials and remediation procedure never enter this
producer repository.

## Failure model

| Failure | Required behavior |
|---|---|
| One filtering backend fails | The edge uses another filtering-capable backend, if one is available. |
| All local filtering paths fail | The agent withdraws the edge's managed service route. |
| DNS entry point fails | The complete-path probe fails and the route is withdrawn. |
| Agent restarts | Startup reconciliation withdraws stale managed state before health can re-advertise it. |
| Agent hangs | Its watchdog exits non-zero so the consumer's supervisor can restart it. |
| Route mutation fails | Health and metrics expose fail-open state for external fencing and alerting. |
| Peer reachability is asymmetric | Peer evidence may trigger best-effort self-withdrawal; it is not treated as full quorum. |
| Every edge is unhealthy | DNS fails closed rather than falling back to an unfiltered resolver. |

## Consumer acceptance contract

Before directing clients at the service address, a consumer independently
proves all of the following with its real topology:

- every selected image digest carries the expected source revision and
  contract versions;
- the exact rendered configuration passes `--check-config` with networking
  disabled;
- `--plan-routes` preserves all environment-owned routes in both ADVERTISE and
  WITHDRAW states;
- every backend passes resolution and controlled-sentinel filtering checks;
- all listeners match the consumer's bind, firewall and source-policy rules;
- the agent can access the supported Tailscale CLI/LocalAPI boundary;
- graceful shutdown and forced withdrawal remove only managed routes;
- failure injection, recovery, restart and rollback behave as declared; and
- at least two independent edges are healthy before the shared address becomes
  a tailnet nameserver.

Deployment evidence belongs only in the consumer repository. Producer CI proves
the schemas, state machine, image build and portable conformance suite; it does
not claim that a particular network is live.

## Alternatives and extensions

The same portable contract can sit above different filtering resolvers, DNS
proxies or recursive backends. A consumer may also evaluate a single integrated
DNS server, response-policy zones or a managed filtering service. Any
alternative must still satisfy fail-closed filtering, route preservation,
control-plane independence and observable withdrawal semantics.

Potential future producer work includes stronger peer protocols, signed release
metadata and an automated consumer pin-update proposal. Concrete regional
placement, LAN integration and external fencing implementations remain consumer
decisions.
