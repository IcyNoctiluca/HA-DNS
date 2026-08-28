# HA-DNS producer boundary

HA-DNS publishes behavior and artifacts; it does not deploy a homelab.

## Published interface

Every releasable `dns-ha-agent` image carries:

- an immutable OCI digest and `org.opencontainers.image.revision` label;
- config, `/health`, metrics-contract and route-plan version labels;
- the machine-readable files under `/opt/dns-ha-agent/contract`;
- `--check-config`, which validates a consumer-rendered configuration without
  starting probes, opening listeners or accessing Tailscale;
- `--plan-routes`, which returns the complete ADVERTISE and WITHDRAW sets
  without accessing Tailscale.

The schemas are also versioned in [`dns-ha-agent/contract/`](dns-ha-agent/contract/).
The live reconciler and offline route plan use the same desired-route
calculation, preventing deployment admission from drifting away from runtime
behavior.

## Consumer responsibilities

A consumer must pin the image by digest and record the matching source
revision. Before mounting the Tailscale socket it must:

1. render its environment-specific configuration;
2. compare every currently advertised non-agent route with declared
   `preserved_routes`;
3. run the pinned image with `--check-config` and `--plan-routes` offline;
4. assert both route-plan states preserve every environment-owned route;
5. supply its own runtime, listener policy, firewall and recovery procedure.

The producer neither knows nor receives real hostnames, the production VIP,
peer addresses, tags, credentials or deployment keys.

## Change order

Backward-compatible producer contract first, published artifact second,
consumer pin and canary third. Remove an old producer contract only after no
declared consumer uses it. Each repository must remain independently buildable
and independently reversible throughout.
