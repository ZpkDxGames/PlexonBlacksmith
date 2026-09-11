package dev.plexon.blacksmith.api;

import org.bukkit.inventory.ItemStack;

/** Immutable, defensive snapshot of a combine quote. */
public record CombineQuote(
        boolean combinable,
        ItemStack primarySnapshot,
        ItemStack donorSnapshot,
        ItemStack resultSnapshot,
        int previousDamage,
        int newDamage,
        int repairAmount,
        double price,
        String currencyProvider) {

    public CombineQuote {
        primarySnapshot = copy(primarySnapshot);
        donorSnapshot = copy(donorSnapshot);
        resultSnapshot = copy(resultSnapshot);
        currencyProvider = currencyProvider == null || currencyProvider.isBlank() ? "unavailable" : currencyProvider;
    }

    @Override public ItemStack primarySnapshot() { return copy(primarySnapshot); }
    @Override public ItemStack donorSnapshot() { return copy(donorSnapshot); }
    @Override public ItemStack resultSnapshot() { return copy(resultSnapshot); }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.clone();
    }
}
