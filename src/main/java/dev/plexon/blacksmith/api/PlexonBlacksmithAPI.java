package dev.plexon.blacksmith.api;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Public read-only PlexonBlacksmith API. Retrieve through Bukkit's ServicesManager. */
public interface PlexonBlacksmithAPI {
    boolean canRepair(ItemStack item);

    RepairQuote quoteRepair(Player player, ItemStack item);

    boolean canCombine(ItemStack primary, ItemStack donor);

    CombineQuote quoteCombine(Player player, ItemStack primary, ItemStack donor);

    EnchantQuote quoteEnchant(Player player, ItemStack item, ItemStack book);

    Optional<BlacksmithSessionView> activeSession(UUID playerId);
}
