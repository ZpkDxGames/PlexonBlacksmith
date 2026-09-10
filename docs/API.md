# PlexonBlacksmith Public API — 2.0

Retrieve `dev.plexon.blacksmith.api.PlexonBlacksmithAPI` from Bukkit's `ServicesManager`.

Read-only quote/session methods:

```java
boolean canRepair(ItemStack item);
RepairQuote quoteRepair(Player player, ItemStack item);
boolean canCombine(ItemStack primary, ItemStack donor);
CombineQuote quoteCombine(Player player, ItemStack primary, ItemStack donor);
EnchantQuote quoteEnchant(Player player, ItemStack item, ItemStack book);
Optional<BlacksmithSessionView> activeSession(UUID playerId);
```

Quote and session values defensively clone every exposed `ItemStack`. `EnchantQuote` additionally freezes the applied-enchantment map.

Post-commit Bukkit events:

- `PlexonItemRepairedEvent`
- `PlexonItemsCombinedEvent`
- `PlexonItemEnchantedEvent`

Events are informational and fire only after a transaction has committed. Each provides a transaction UUID and stable operation-specific event ID. Listener failure cannot undo or duplicate an already committed item.

## Combine ownership rule

`quoteCombine` is intentionally strict. Primary and donor must be the same material, damageable and `ItemStack#isSimilar` after amount is normalized to one and only damage is normalized to zero. The result is a clone of the primary whose damage is reduced. Donor metadata is never merged.
