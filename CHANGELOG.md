# Changelog

## 1.4.0 — 2026-09-07

### Added
- Optional PlexonCore 1.x bridge and `blacksmith` module registration.
- `PLEXON_BLACKSMITH` integration capability publication.
- Public `PlexonBlacksmithAPI` through Bukkit ServicesManager.
- `PlexonItemRepairedEvent` and `PlexonItemEnchantedEvent` with non-empty transaction/event IDs.
- Session IDs/nonces and per-session transaction guards.
- Vault refund path for post-charge/pre-commit failures.
- `/blacksmith diagnostics` and `/blacksmith reload` admin operations.
- Explicit double-click / collect-to-cursor GUI hardening.
- Java 25 / Paper 26.2 Maven build, CI verification, no-Core-shading check, and tag-driven release workflow.

### Preserved
- Production 1.3.0 direct item insertion UX.
- Repair and Enchant pages/layout.
- Exact repair/enchant pricing rules.
- ItemStack clone-based custom metadata preservation.
- Page-to-page cached inputs.
- Safe return/drop fallback when closing.
- Vault as the real economy provider.

### Hardened
- Success events fire only after payment and item output commit.
- Rapid confirmation clicks cannot execute overlapping transactions.
- Failed output commits preserve cached inputs and attempt exactly one refund.
- Disconnect/shutdown paths return cached inputs instead of leaving sessions resident.
