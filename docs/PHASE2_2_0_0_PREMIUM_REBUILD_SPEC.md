# PlexonBlacksmith 2.0.0 — Phase 2 Premium Workstation Rebuild Specification

Status: EXECUTION SPECIFICATION
Baseline: `v1.4.1` / `2fca852d0c2e9efddd9d8d55865c7b5c0c46fcb5`
Implementation branch: `phase2/2.0.0-premium-workstation`
Candidate target: `v2.0.0-rc.1`
Stable target: `v2.0.0` only after PlexonCraft runtime certification

## 1. Repository-state decision

The verified production baseline is PlexonBlacksmith 1.4.1. It already owns its mutable inventory, uses cached exact `ItemStack` inputs, has synchronous transaction guards, Vault charging/refunds, a public quote API, repair/enchant success events, Core 1/2 lifecycle integration, shutdown/close/quit return safety, and MockBukkit regression coverage.

Phase 2 therefore extends those mature internals instead of replacing them. The 2.0 version is justified by a workstation-level product surface, configurable economy/pricing policy, a safe combine operation, confirmation state, new public contracts, richer diagnostics, and a documented migration/certification boundary.

## 2. Product contract

PlexonBlacksmith 2.0 is a plugin-owned, deterministic workstation. A player places real items into explicit input/material slots; the GUI displays the exact operation result and exact currency/material cost; the player confirms; only then can a transaction consume inputs or currency.

Presentation follows the Plexon Phase 2 language:

- Adventure/MiniMessage components
- restrained gold/gray workstation palette with semantic status colors
- gray labels and high-contrast values
- concise premium lore and explicit interaction hints
- stable Back / Guide / Next / Close controls
- READY / WAITING / LOCKED / ERROR / CONFIRM states

PlexonTools remains the visual/lore reference. Blacksmith must not rewrite item lore as part of an operation unless that operation specifically owns the lore property. Service-only preview lore is applied to a display clone and never to the committed item.

## 3. Supported operations

### Repair

Input: one damaged, breakable item.
Result: an exact clone with only damage intentionally changed to zero.
Cost: deterministic currency quote derived from missing durability and configurable material-tier pricing.

### Combine

Inputs: primary item + donor item.
Eligibility:

- same material;
- both damageable and not unbreakable;
- metadata/components/PDC must be equivalent after normalizing only the damage property;
- the donor must improve the primary item.

Result: exact clone of the primary with only damage intentionally changed. Donor metadata is never merged into the primary. This makes the operation safe for PlexonTools/custom items: incompatible custom identities are rejected rather than flattened.

Durability rule: combine the remaining durability of both items plus a configurable percentage of max durability, capped at full durability.
Cost: deterministic currency quote plus consumption of exactly one donor item.

### Enchant

Inputs: target item + enchanted book.
Result: exact clone of target with only compatible, non-conflicting, higher enchantment levels from the book applied. Existing 1.4 rules remain authoritative unless a real defect is found.
Cost: deterministic currency quote; the book is consumed only after successful payment and result commit.

### Material upgrades

Generic material transformation is intentionally not implemented in 2.0.0-rc.1. A generic upgrade could silently destroy custom components/PDC or cross plugin ownership boundaries. An upgrade operation may be added later only through an explicit adapter/recipe contract whose result mutation is defined property-by-property.

## 4. Transaction invariant

Every paid operation must execute on the primary server thread and follow:

`validate -> determine exact cost -> reserve/charge -> perform operation -> commit result -> refund/rollback if anything fails`

Rules:

- input snapshots and quote are recalculated at click time;
- duplicate transaction execution is prevented per session;
- expensive/destructive operations require confirmation according to configuration;
- a committed result is produced at most once;
- cached inputs are cleared only after the result has been placed successfully;
- if any pre-commit step fails after payment, payment is refunded and inputs remain cached;
- event/listener or GUI-refresh failure after commit must never duplicate or roll back an already committed item;
- economy provider errors are surfaced in diagnostics and never converted into item consumption.

TheosisEconomy compatibility is via the configured Vault economy service, matching the ecosystem economy contract. PlexonBlacksmith does not become an economy provider.

## 5. Exact-item safety

All real inputs are cloned on capture. All operation builders work from clones. Preview rendering must not mutate cached inputs or committed results.

For repair/combine, PDC, item components, custom model data, names, lore, enchantments, attributes, flags and plugin metadata are preserved because the primary item is cloned and only `Damageable.damage` is intentionally changed.

For enchant, the target is cloned and only Bukkit enchantment state is intentionally changed.

Combine identity matching compares normalized clones whose only normalization is damage=0 and amount=1. If normalized items are not similar, combine is rejected.

## 6. Workstation GUI

Size: 54 slots to allow three clear operation pages and stable navigation.

Each page must contain:

- title/status header;
- named input/material slots;
- process icon;
- exact result preview;
- quote/status panel;
- confirmation-aware output interaction;
- stable Back, Guide, Next and Close controls.

Input behavior:

- direct cursor placement;
- shift-click routing only to eligible empty input slots;
- one-slot drag support only when the drag targets exactly one eligible input slot;
- top-inventory decoration/output slots are always protected;
- double-click collection and cross-inventory collection are blocked;
- occupied inputs are never silently overwritten.

Close/quit/reload/disable returns each unused cached input exactly once. Page switching never returns or duplicates cached inputs.

## 7. Confirmation model

Default policy:

- Repair requires confirmation only at/above configured `confirmation.expensive-threshold`.
- Combine always requires confirmation by default because it consumes a donor item.
- Enchant always requires confirmation by default because it consumes an enchanted book.

The first output click arms a confirmation containing operation type + current input fingerprints + exact price + expiry. The second matching click within the configured window executes. Any input/page change invalidates confirmation.

## 8. Configuration

Add `config.yml` with documented defaults for:

- confirmation timeout/threshold/policies;
- repair tier prices/minimum price;
- combine base price/bonus percentage;
- enchant base/rate tiers;
- diagnostics warnings;
- optional feature toggles for combine/enchant.

Reload must close active sessions first, reload config, re-resolve Vault and rebuild immutable settings. Invalid numeric values are clamped/fallback-safe and surfaced by diagnostics.

## 9. Admin and diagnostics

Preserve `/blacksmith diagnostics` and `/blacksmith reload`.

Diagnostics must report:

- plugin/version/build target;
- Paper/Java/Core state;
- Vault provider and availability;
- feature enablement;
- active sessions/transactions/confirmations;
- configuration source/validation status;
- public API registration and event contracts;
- runtime certification status as SOURCE-CERTIFIED / RUNTIME-PENDING for the RC.

## 10. Public API/events

Preserve source-compatible 1.4 API methods and existing repair/enchant events.

Add:

- `CombineQuote`;
- `quoteCombine(Player, ItemStack primary, ItemStack donor)`;
- `canCombine(ItemStack primary, ItemStack donor)`;
- `PlexonItemsCombinedEvent` after successful commit.

All API value objects must defensive-clone mutable `ItemStack` values.

## 11. Persistence audit

PlexonBlacksmith has no player-data database by design. Workstation sessions are transient and owner-bound. The only persistent state is configuration/documentation.

No session state may survive a restart. Shutdown safety must return online-player inputs before plugin disable completes; quit handling remains the ownerless-session prevention path.

## 12. Performance

No asynchronous Bukkit inventory/item access is allowed.

Analysis/building a quote is bounded by the small number of inputs and enchantments on one enchanted book. No unbounded loops, scheduler polling, database access or network access belongs in the interaction path.

GUI rendering is a bounded 54-slot operation. Decorative item templates may be cloned/reused, but correctness takes precedence over micro-optimization.

## 13. Tests

Automated coverage must include at minimum:

- exact repair metadata preservation;
- repair quote pricing contract;
- paid repair charges exactly once;
- combine rejects mismatched custom metadata;
- combine preserves primary metadata and consumes exactly one donor only after commit;
- expensive/destructive confirmation cannot charge on first click;
- enchant target metadata preservation;
- double-click / shift-click / drag protection;
- close returns cached inputs exactly once;
- public API defensive cloning;
- Core standalone/API-range contract;
- build metadata/distribution assertions.

## 14. CI and distribution

CI must run on `main`, Phase 2 branches and PRs, using Java 25 / Paper 26.2 and the pinned PlexonCore 2.0.4 artifact checksum.

Required pipeline:

1. provision pinned PlexonCore;
2. `mvn -B clean verify`;
3. verify `plugin.yml` version;
4. verify PlexonCore is not shaded;
5. verify required config/docs are inside/source-visible as appropriate;
6. `git diff --check`;
7. generate `target/SHA256SUMS.txt`;
8. upload candidate JAR + checksum artifact.

For the RC boundary, a one-shot branch workflow may create `v2.0.0-rc.1` as a GitHub prerelease, targeting the exact candidate commit and uploading:

- `PlexonBlacksmith-2.0.0-rc.1.jar`
- `SHA256SUMS.txt`

The one-shot publisher must refuse to overwrite an existing tag/release.

## 15. Runtime certification boundary

Source/product/CI completion is not PlexonCraft runtime certification.

Until a real PlexonCraft runtime is available, do not claim live command behavior, Vault/Theosis charging, Core READY state, restart safety, multi-player concurrency, or Spark performance as observed.

RC checkpoint classification after successful CI + prerelease publication:

`RC RELEASED / RUNTIME PENDING`

Stable `v2.0.0` is forbidden until the RC JAR is actually runtime-certified on PlexonCraft.
