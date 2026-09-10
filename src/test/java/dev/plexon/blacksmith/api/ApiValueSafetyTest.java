package dev.plexon.blacksmith.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.Test;

class ApiValueSafetyTest {
    @Test
    void enchantQuoteCopiesAndFreezesAppliedEnchantments() {
        Map<String, Integer> mutable = new LinkedHashMap<>();
        mutable.put("minecraft:unbreaking", 3);
        EnchantQuote quote = new EnchantQuote(true, null, null, null, mutable, 475.0, "TestEconomy");
        mutable.put("minecraft:efficiency", 5);
        assertEquals(Map.of("minecraft:unbreaking", 3), quote.appliedEnchantments());
        assertThrows(UnsupportedOperationException.class,
                () -> quote.appliedEnchantments().put("minecraft:mending", 1));
    }

    @Test
    void combineQuoteDefensivelyCopiesItems() {
        ItemStack source = new ItemStack(Material.DIAMOND_PICKAXE);
        Damageable sourceMeta = (Damageable) source.getItemMeta();
        sourceMeta.setDamage(400);
        source.setItemMeta(sourceMeta);
        CombineQuote quote = new CombineQuote(true, source, source, source, 400, 100, 300, 250.0, "TestEconomy");

        Damageable changed = (Damageable) source.getItemMeta();
        changed.setDamage(999);
        source.setItemMeta(changed);
        assertEquals(400, ((Damageable) quote.primarySnapshot().getItemMeta()).getDamage());

        ItemStack exposed = quote.resultSnapshot();
        Damageable exposedMeta = (Damageable) exposed.getItemMeta();
        exposedMeta.setDamage(777);
        exposed.setItemMeta(exposedMeta);
        assertEquals(400, ((Damageable) quote.resultSnapshot().getItemMeta()).getDamage());
    }

    @Test
    void publicViewsNormalizeMissingProviderWithoutExposingMutableSessionState() {
        RepairQuote repair = new RepairQuote(false, null, null, 0, 0, 0, 0.0, " ");
        assertEquals("unavailable", repair.currencyProvider());
        BlacksmithSessionView view = new BlacksmithSessionView(
                UUID.randomUUID(), UUID.randomUUID(), "REPAIR",
                null, null, null, null, null, null, 0.0, false, false);
        assertNull(view.repairInput());
        assertFalse(view.transactionActive());
        assertFalse(view.confirmationArmed());
    }
}
