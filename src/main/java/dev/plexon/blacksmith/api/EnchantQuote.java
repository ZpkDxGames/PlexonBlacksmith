package dev.plexon.blacksmith.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

public record EnchantQuote(
        boolean compatible,
        ItemStack itemSnapshot,
        ItemStack bookSnapshot,
        ItemStack resultSnapshot,
        Map<String, Integer> appliedEnchantments,
        double price,
        String currencyProvider) {

    public EnchantQuote {
        itemSnapshot = copy(itemSnapshot);
        bookSnapshot = copy(bookSnapshot);
        resultSnapshot = copy(resultSnapshot);
        appliedEnchantments = Collections.unmodifiableMap(new LinkedHashMap<>(
                appliedEnchantments == null ? Map.of() : appliedEnchantments));
        currencyProvider = currencyProvider == null || currencyProvider.isBlank() ? "unavailable" : currencyProvider;
    }

    @Override public ItemStack itemSnapshot() { return copy(itemSnapshot); }
    @Override public ItemStack bookSnapshot() { return copy(bookSnapshot); }
    @Override public ItemStack resultSnapshot() { return copy(resultSnapshot); }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.clone();
    }
}
