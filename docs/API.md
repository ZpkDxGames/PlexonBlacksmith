# PlexonBlacksmith Public API

Retrieve `dev.plexon.blacksmith.api.PlexonBlacksmithAPI` from Bukkit's `ServicesManager`.

Read-only methods:

```java
boolean canRepair(ItemStack item);
RepairQuote quoteRepair(Player player, ItemStack item);
EnchantQuote quoteEnchant(Player player, ItemStack item, ItemStack book);
Optional<BlacksmithSessionView> activeSession(UUID playerId);
```

All ItemStack values returned by API records are defensive clones. Mutable internal Inventory or Session objects are never exposed.

## Events

### `PlexonItemRepairedEvent`

Post-success, non-cancellable Bukkit event containing player, transaction ID, event ID, repaired item snapshot, old/new damage, repair amount, exact price paid and economy provider.

### `PlexonItemEnchantedEvent`

Post-success, non-cancellable Bukkit event containing player, transaction ID, event ID, final item snapshot, book snapshot, applied enchantments, exact price paid and economy provider.

Events are dispatched on the primary server thread only after the transaction is committed. No event fires for previews, validation failures, insufficient funds, failed payment, or pre-commit rollback.
