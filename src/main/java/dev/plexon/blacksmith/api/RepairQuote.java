package dev.plexon.blacksmith.api;

import org.bukkit.inventory.ItemStack;

public record RepairQuote(
        boolean repairable,
        ItemStack inputSnapshot,
        ItemStack resultSnapshot,
        int previousDamage,
        int newDamage,
        int repairAmount,
        double price,
        String currencyProvider) {

    public RepairQuote {
        inputSnapshot = copy(inputSnapshot);
        resultSnapshot = copy(resultSnapshot);
        currencyProvider = currencyProvider == null || currencyProvider.isBlank() ? "unavailable" : currencyProvider;
    }

    @Override public ItemStack inputSnapshot() { return copy(inputSnapshot); }
    @Override public ItemStack resultSnapshot() { return copy(resultSnapshot); }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.clone();
    }
}
