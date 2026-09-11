# PlexonBlacksmith 2.0.0

PlexonBlacksmith is the Plexon ecosystem's exact-item Blacksmith workstation for Paper 26.2 / Java 25.

## Workstation

`/blacksmith` provides three plugin-owned services:

- **Repair** — restores durability while preserving the complete item identity and metadata.
- **Combine** — combines remaining durability only when primary and donor are the same material and the same custom identity after damage normalization.
- **Enchant** — applies compatible higher enchantment levels from an enchanted book to an exact clone of the target.

The Phase 3 product layer adds Blacksmith Home, explicit service routing, safe result previews, quote/status projection and confirmation feedback without creating a second transaction engine. Phase 2 remains the sole authority for item custody, exact quotes, Vault withdrawal/refund, commit behavior, events and close/quit/disable recovery.

## Transaction contract

Paid operations run synchronously and follow:

`validate -> determine exact cost -> charge -> deliver result -> commit -> compensating refund on pre-commit failure`

Cached inputs are cleared only after the result has been placed successfully. Expensive/destructive actions use confirmation. Duplicate-click, shift-click, drag and cross-inventory collection paths are bounded and protected.

## Item safety

Repair and Combine intentionally mutate only `Damageable.damage`. Enchant intentionally mutates only enchantment state. PDC, components, names, lore, attributes and other plugin metadata remain owned by the input item.

Generic material upgrades remain intentionally excluded because there is no safe generic rule for transforming arbitrary custom-item components/PDC without an explicit adapter/recipe contract.

Unused cached inputs are returned on close, quit, valid reload and plugin shutdown.

## Economy

PlexonBlacksmith consumes the active Vault economy service. In the Plexon ecosystem, TheosisEconomy remains the provider authority through Vault; Blacksmith does not register or duplicate an economy provider.

## Commands

- `/blacksmith`
- `/blacksmith diagnostics` — `plexon.blacksmith.admin`
- `/blacksmith reload` — validates the candidate `config.yml` first. Missing or malformed YAML is rejected without closing active sessions or changing runtime settings. A valid candidate then closes sessions safely, returns unused inputs, reloads configuration and re-resolves Vault.

## Configuration and API

See:

- `docs/CONFIGURATION_2_0.md`
- `docs/API.md`
- `docs/MIGRATION_2_0.md`
- `docs/PHASE3_PLAYER_UX.md`

## Release boundary

Stable `2.0.0` is published only from the exact final `main` commit after independent Build verification. The stable Release workflow rebuilds that same source, publishes the JAR plus checksum/test/provenance evidence, then downloads the public assets and verifies them before succeeding.

GitHub source/release closure does not claim live PlexonCraft certification. `runtime_certification=NOT_EXECUTED` is explicit release provenance until deployment validation is performed.

Rollback baseline: immutable `v1.4.1` at `2fca852d0c2e9efddd9d8d55865c7b5c0c46fcb5`, JAR SHA-256 `0c371dc03be7fec01463c2024b98e3ad804f06a7af5d45c8be848cf5733ae88e`.
