# Migrating PlexonBlacksmith 1.4.1 -> 2.0.0-rc.1

1. Keep the 1.4.1 JAR available for rollback.
2. Stop the server before replacing the plugin JAR.
3. Replace `PlexonBlacksmith-1.4.1.jar` with `PlexonBlacksmith-2.0.0-rc.1.jar`.
4. Preserve `plugins/PlexonBlacksmith/`. The new `config.yml` is additive; no player database migration exists.
5. Preserve Vault and the server's authoritative economy provider (TheosisEconomy in the Plexon ecosystem).
6. Start in a maintenance/staging window and run `/blacksmith diagnostics` plus PlexonCore diagnostics.
7. Runtime-test Repair, Combine and Enchant using ordinary items and representative PlexonTools/custom items before considering stable promotion.

## Rollback

Stop the server and restore the 1.4.1 JAR. No player-data schema is changed by 2.0.0-rc.1. A generated 2.0 `config.yml` may remain because 1.4.1 does not depend on it.

## Important behavior change

Combine is new and deliberately strict: donor and primary must represent the same custom identity except for damage. Generic material upgrades are not part of this release candidate.
