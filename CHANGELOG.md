# Changelog

## 2.0.0 — Stable

### Added
- Premium Repair, Combine and Enchant workstation with Phase 3 Blacksmith Home, explicit service routing, result previews, quote/status projection and clear confirmation feedback.
- Public quote/event contracts for repair, combine and enchant workflows.
- Stable GitHub Build + exact-main Release verification with JAR, SHA-256, test summary and provenance evidence.

### Fixed
- `/blacksmith reload` now fails closed when `config.yml` is missing or malformed. The invalid candidate is rejected before active exact-ItemStack custody sessions are closed or runtime pricing/feature state can change.
- Regression coverage proves a rejected malformed reload preserves the same active session and cached damaged input.

### Preserved
- Phase 2 remains the sole authority for exact quotes, item custody, validation, Vault withdrawal/refund, transaction commit, events and close/quit/disable recovery.
- Repair and Combine preserve custom identity and mutate only permitted durability state; Enchant preserves target identity while applying compatible enchantment upgrades.
- Core 1/2 compatibility and standalone fallback.
- Generic material upgrades remain intentionally excluded without explicit custom-item adapters/recipes.

### Release boundary
- Accepted Phase 2 lineage: `475f1e8005fd6ed5f9118be7385473a69a574b05`.
- Accepted Phase 3 lineage: `f46290bc0192945c3f1f8db51bf6b6b58d69414c`.
- Historical RC2 source: `7bb0c307cf39e7c2f76834baa3d58f1e060607b1`.
- Stable rollback: `v1.4.1` / `2fca852d0c2e9efddd9d8d55865c7b5c0c46fcb5` / JAR SHA-256 `0c371dc03be7fec01463c2024b98e3ad804f06a7af5d45c8be848cf5733ae88e`.
- Live PlexonCraft runtime certification is a deployment follow-up and remains explicitly `NOT_EXECUTED` in GitHub release provenance until performed.

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

## 1.4.1
- PlexonCore 2 lifecycle readiness compatibility patch.

## 1.4.0
- PlexonCore migration, public API/events and transaction hardening.
