# Migration: 1.3.0 → 1.4.0

The production 1.3.0 source archive was recovered and verified against the live 1.3.0 JAR before this migration. The original production source is retained under `legacy/1.3.0/` for auditability.

1.4.0 is intentionally conservative. It does not redesign the GUI, rebalance pricing, replace Vault, move Blacksmith gameplay into PlexonCore, or require a new database/config migration.

## Runtime changes

- Core bridge registers STARTING then READY/DEGRADED/FAILED.
- Public API is registered independently of Core.
- Repair/enchant confirmations use a per-session reentrancy guard.
- Payment is charged before output, but cached inputs remain owned until cursor output succeeds.
- If output commit throws after a successful charge, Blacksmith attempts one Vault refund and keeps the cached inputs.
- Success events are emitted only after output commit.
- Player quit, inventory close, reload and plugin disable close sessions safely.

## Deployment

Stop the server, replace the 1.3.0 JAR with `PlexonBlacksmith-1.4.0.jar`, preserve `plugins/PlexonBlacksmith/`, and start the server.

Validate `/plexon modules`, `/plexon diagnostics`, `/blacksmith diagnostics`, then perform one controlled repair and enchant transaction.
