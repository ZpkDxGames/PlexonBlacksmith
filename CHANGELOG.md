# Changelog

## 2.0.0-rc.1 — Phase 2 release candidate

### Added
- Three-page 54-slot premium workstation: Repair, Combine and Enchant.
- Safe same-identity combine operation that changes only primary-item durability.
- Configurable pricing and feature flags through `config.yml`.
- Confirmation state for donor/book consumption and expensive repairs.
- Stable Close control, explicit READY/WAITING/LOCKED/confirmation states and MiniMessage/Adventure presentation.
- `CombineQuote`, `canCombine`, `quoteCombine` and `PlexonItemsCombinedEvent` public contracts.
- Expanded diagnostics, source/runtime certification status and config validation reporting.
- Regression coverage for exact custom PDC preservation, combine identity rejection, one-charge/one-result behavior and API defensive copies.

### Preserved
- Plugin-owned cached-input inventory architecture.
- Vault/TheosisEconomy provider contract.
- Main-thread transaction guard, compensating refund path, Core 1/2 compatibility and standalone fallback.
- Existing repair/enchant quote and post-success event contracts.
- Close/quit/reload/disable unused-input return safety.

### Intentionally not implemented
- Generic material upgrades. Arbitrary custom-item ownership cannot be transformed safely without explicit adapters/recipes.

### Certification
- Candidate only. PlexonCraft runtime certification remains required before stable `2.0.0`.

## 1.4.1
- PlexonCore 2 lifecycle readiness compatibility patch.

## 1.4.0
- PlexonCore migration, public API/events and transaction hardening.
