package dev.plexon.blacksmith.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
    void publicViewsNormalizeMissingProviderWithoutExposingMutableSessionState() {
        RepairQuote repair = new RepairQuote(false, null, null, 0, 0, 0, 0.0, " ");
        assertEquals("unavailable", repair.currencyProvider());
        BlacksmithSessionView view = new BlacksmithSessionView(
                UUID.randomUUID(), UUID.randomUUID(), "REPAIR", null, null, null, null, 0.0, false);
        assertNull(view.repairInput());
        assertFalse(view.transactionActive());
    }
}
