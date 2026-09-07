package dev.plexon.blacksmith.api;

import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public record BlacksmithSessionView(
        UUID playerId,
        UUID sessionId,
        String page,
        ItemStack repairInput,
        ItemStack enchantTarget,
        ItemStack enchantBook,
        ItemStack previewResult,
        double previewPrice,
        boolean transactionActive) {

    public BlacksmithSessionView {
        repairInput = copy(repairInput);
        enchantTarget = copy(enchantTarget);
        enchantBook = copy(enchantBook);
        previewResult = copy(previewResult);
    }

    @Override public ItemStack repairInput() { return copy(repairInput); }
    @Override public ItemStack enchantTarget() { return copy(enchantTarget); }
    @Override public ItemStack enchantBook() { return copy(enchantBook); }
    @Override public ItemStack previewResult() { return copy(previewResult); }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.clone();
    }
}
