# Migrating PlexonBlacksmith 1.4.1 -> 2.0.0

1. Keep the immutable `v1.4.1` JAR available for rollback.
2. Stop the server before replacing the plugin JAR.
3. Replace `PlexonBlacksmith-1.4.1.jar` with `PlexonBlacksmith-2.0.0.jar`.
4. Preserve `plugins/PlexonBlacksmith/`. Version 2.0 does not introduce a player-data database migration.
5. Preserve Vault and the server's authoritative economy provider (TheosisEconomy in the Plexon ecosystem).
6. Start in a maintenance/staging window and run `/blacksmith diagnostics` plus PlexonCore diagnostics.
7. Runtime-test Repair, Combine and Enchant using ordinary items and representative PlexonTools/custom items before production cutover is considered complete.

## Configuration reload safety

Stable 2.0 validates the candidate `config.yml` before crossing the reload boundary. If the file is missing or malformed, `/blacksmith reload` is rejected and the current runtime plus active exact-ItemStack custody sessions remain unchanged.

A valid candidate then follows the established safe reload path: active sessions close, unused cached inputs are returned, configuration is reloaded and Vault is re-resolved.

## Rollback

Stop the server and restore `PlexonBlacksmith-1.4.1.jar`.

Authoritative rollback identity:

- tag: `v1.4.1`
- source: `2fca852d0c2e9efddd9d8d55865c7b5c0c46fcb5`
- JAR SHA-256: `0c371dc03be7fec01463c2024b98e3ad804f06a7af5d45c8be848cf5733ae88e`

No player-data schema is changed by 2.0. A generated 2.0 `config.yml` may remain because 1.4.1 does not depend on the new combine configuration.

## GitHub release vs live deployment

Stable GitHub publication proves reproducible source/build/release integrity only. It does not claim that PlexonCraft has already been upgraded or runtime-certified. Release provenance records `runtime_certification=NOT_EXECUTED` until live deployment validation is performed.

## Important behavior changes

Combine is deliberately strict: donor and primary must represent the same custom identity except for damage. Generic material upgrades are not part of 2.0 because arbitrary custom-item ownership cannot be transformed safely without explicit adapters/recipes.
