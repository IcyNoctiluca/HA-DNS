# HA DNS

Producer repository for `dns-ha-agent`, a fail-closed Tailscale route-health
agent for highly available filtered DNS. The agent is the only executable
service implemented here; resolver deployment and homelab topology belong to
infrastructure consumers such as Battlenet.

## System contract

Tailnet clients use one consumer-selected `/32` resolver address advertised by
multiple edges. Each edge provides a DNS entry point, one or more filtering
backends, an approved upstream path, and `dns-ha-agent`:

```text
tailnet client
    -> shared /32 routed to a healthy edge
    -> DNS entry point
    -> filtering backend
    -> recursive or policy-approved upstream
```

The agent probes the complete local serving path. A probe must prove both
ordinary resolution and blocking of a controlled public sentinel. When the path
is unhealthy, the agent withdraws only its managed route and preserves all
consumer-owned routes. Tailscale handles node loss; the agent handles a broken
service on a still-connected node; an optional consumer-owned external fencer
may remediate a route that remains approved after failed local withdrawal.

The portable safety invariants are:

1. Every edge in one deployment advertises the same consumer-selected `/32`.
2. An edge does not accept DNS from the service it provides.
3. A responsive but unfiltered backend is unhealthy.
4. No failure path falls back to an unfiltered public resolver.
5. The agent is the sole local writer of its managed route advertisement.
6. Withdrawal preserves every route outside the declared managed set.
7. Startup reconciles observed route state before declaring the edge healthy.
8. The serving path starts and runs without a cluster control plane, scheduler,
   datastore, overlay network, or cluster DNS.
9. Management listeners require a declared bind, host-firewall defence in
   depth, and tailnet source policy.
10. Consumers deploy immutable digests and verify source and contract labels
    before granting route-mutation capability.

Detailed state-machine, configuration, health, metrics, test, and container
semantics are maintained beside the implementation in
[`dns-ha-agent/BLUEPRINT.md`](dns-ha-agent/BLUEPRINT.md).

## Published interface

Every releasable image provides:

- an immutable OCI digest and source-revision label;
- version labels for the config, health, metrics, and route-plan contracts;
- machine-readable schemas under `/opt/dns-ha-agent/contract` and in
  [`dns-ha-agent/contract/`](dns-ha-agent/contract/);
- `--check-config` for side-effect-free validation of consumer-rendered YAML;
  and
- `--plan-routes` for side-effect-free ADVERTISE and WITHDRAW route sets.

The live reconciler and offline planner use the same desired-route calculation.
A mutable image tag is only a browse pointer; consumers pin the digest.

## Producer and consumer boundary

This repository owns agent behavior, source, tests, schemas, image builds, and
producer release evidence. A consumer owns:

- hosts, sites, placement, power domains, and the real service address;
- peer addresses, Tailscale tags, route approval, and access policy;
- resolver configuration, supervisor, firewall, credentials, and monitoring;
- rendered agent configuration and preserved-route declarations; and
- canary, failure-injection, recovery, rollback, and live rollout evidence.

A consumer admits a release by recording its source revision and immutable
digest, validating its rendered config offline, checking both route-plan states
preserve environment-owned routes, and only then mounting the Tailscale socket.
No inventory, vault password, host key, or deployment credential crosses into
producer CI.

Changes cross the boundary in this order: backwards-compatible producer change,
green immutable release, then an independent consumer pin and canary. Removing
an old contract waits until no declared consumer uses it. The repositories do
not require coordinated unmerged branches, shared deployment roles, submodules,
or relative checkouts.

## Current release

Source revision `6d19438bf99bbff38692b41bdc0a866e3c124951` produced immutable
image digest
`sha256:61bdf3387050f2cacf1bd4f57ef91c527d5eaa5020a0eb26bb963f794dbf94f1`
in green Woodpecker pipeline 26. Deployment status is intentionally not tracked
here; Battlenet is authoritative for its own live state.

GitHub (`IcyNoctiluca/HA-DNS`) is canonical. Every push is mirrored to Forgejo
at `git.battlenet.internal`, where Woodpecker at `ci.battlenet.internal` runs
producer CI.

## Repository layout

```text
.
├── README.md                 portable system and repository boundary
├── AGENTS.md                 contributor and repository operating rules
├── .woodpecker.yml           producer CI
└── dns-ha-agent/
    ├── README.md             build and usage entry point
    ├── AGENTS.md             implementation invariants
    ├── BLUEPRINT.md          detailed service specification
    ├── PHASES.md             implementation and acceptance gates
    ├── PROGRESS.md           producer build/test history
    ├── contract/             versioned machine-readable schemas
    └── src/                  Java implementation and tests
```

Read [`AGENTS.md`](AGENTS.md) before changing the repository and
[`dns-ha-agent/README.md`](dns-ha-agent/README.md) before building or testing
the service. Retired root deployment documents remain available in Git history;
they are not current desired state.
