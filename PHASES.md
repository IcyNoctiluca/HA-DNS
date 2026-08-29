# HA DNS — producer work phases

The original version of this file was a Battlenet-specific deployment runbook.
It is retired from active use: deployment topology, credentials, firewall
policy, live commands, cutover and rollback belong to the consuming
infrastructure repository. The retired text remains available in Git history.

This producer repository now has two active work streams:

1. Portable HA DNS architecture and acceptance criteria in
   [`BLUEPRINT.md`](BLUEPRINT.md).
2. The `dns-ha-agent` implementation and release gates in
   [`dns-ha-agent/PHASES.md`](dns-ha-agent/PHASES.md).

## Producer release sequence

1. Change portable behavior or the agent implementation.
2. Update versioned schemas and compatibility notes when the contract changes.
3. Run unit and integration tests.
4. Build and publish the multi-architecture image with source and contract
   labels.
5. Smoke-test the published image by immutable digest.
6. Publish the source revision, digest and compatibility information for
   consumers.

The producer stops at publication. A consumer then opens its own change to pin
the release, render its environment-specific configuration, validate both
route states, and conduct canary/failure/rollback acceptance. Those changes do
not need a simultaneous producer branch.

## Historical status

Chronological design and build history remains in [`PROGRESS.md`](PROGRESS.md).
It is evidence of what this repository built or tested, not current deployment
state. Current host and rollout evidence must be read from the consuming
infrastructure repository.
