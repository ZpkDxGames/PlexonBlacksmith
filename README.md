# PlexonBlacksmith 2.0.0-rc.1

PlexonBlacksmith is the Plexon ecosystem's exact-item Blacksmith workstation for Paper 26.2 / Java 25.

## Phase 2 workstation

`/blacksmith` provides three plugin-owned pages:

- **Repair** — restores durability while preserving the complete item identity and metadata.
- **Combine** — combines remaining durability only when primary and donor are the same material and the same custom identity after damage normalization.
- **Enchant** — applies compatible higher enchantment levels from an enchanted book to an exact clone of the target.

Every page exposes real input/material slots, an exact result preview, deterministic cost, state-aware lore and stable Back / Guide / Next / Close controls.

## Transaction contract

Paid operations run synchronously and follow:

`validate -> determine exact cost -> reserve/charge -> perform -> commit -> refund/rollback on failure`

Inputs are cleared only after the result has been placed successfully. Expensive/destructive actions use confirmation. Duplicate-click, shift-click, drag and cross-inventory collection paths are bounded and protected.

## Custom item compatibility

Repair and Combine intentionally mutate only `Damageable.damage`. Enchant intentionally mutates only enchantment state. PDC, components, names, lore, attributes and other plugin metadata remain owned by the input item.

Generic material upgrades are intentionally excluded from 2.0.0-rc.1 because there is no safe generic rule for transforming arbitrary custom-item components/PDC. Future upgrades require an explicit adapter/recipe contract.

## Economy

PlexonBlacksmith consumes the active Vault economy service. In the Plexon ecosystem, TheosisEconomy remains the provider authority through Vault; Blacksmith does not register or duplicate an economy provider.

## Commands

- `/blacksmith`
- `/blacksmith diagnostics` — `plexon.blacksmith.admin`
- `/blacksmith reload` — closes sessions safely, reloads configuration and re-resolves Vault.

## Configuration and API

See:

- `docs/CONFIGURATION_2_0.md`
- `docs/API.md`
- `docs/MIGRATION_2_0.md`
- `docs/PHASE2_2_0_0_PREMIUM_REBUILD_SPEC.md`

## Certification boundary

`2.0.0-rc.1` is a release candidate. Source/CI completion does not certify live PlexonCraft runtime behavior. Stable `2.0.0` must not be published until the exact RC candidate is runtime-certified on PlexonCraft.
