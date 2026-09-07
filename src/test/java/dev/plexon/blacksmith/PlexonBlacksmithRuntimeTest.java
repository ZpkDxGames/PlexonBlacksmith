package dev.plexon.blacksmith;

import static org.junit.jupiter.api.Assertions.*;

import dev.plexon.blacksmith.api.PlexonBlacksmithAPI;
import dev.plexon.blacksmith.event.PlexonItemRepairedEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlexonBlacksmithRuntimeTest {
    private static final int REPAIR_INPUT = 20;
    private static final int REPAIR_OUTPUT = 24;
    private static final int PREVIOUS = 37;
    private static final int NEXT = 43;

    ServerMock server;
    PlexonBlacksmithBridge plugin;
    PlayerMock player;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PlexonBlacksmithBridge.class);
        player = server.addPlayer("Tonim");
        assertTrue(plugin.isEnabled());
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void directInputStaysVisibleAcrossPagesAndReturnsExactlyOnceOnClose() {
        open();
        ItemStack damaged = damagedDiamondPickaxe(100);
        player.setItemOnCursor(damaged);
        call(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);

        assertNull(player.getItemOnCursor());
        ItemStack visible = player.getOpenInventory().getTopInventory().getItem(REPAIR_INPUT);
        assertNotNull(visible);
        assertEquals(Material.DIAMOND_PICKAXE, visible.getType());
        assertEquals(100, ((Damageable) visible.getItemMeta()).getDamage());

        call(NEXT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        call(PREVIOUS, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        ItemStack restoredView = player.getOpenInventory().getTopInventory().getItem(REPAIR_INPUT);
        assertEquals(Material.DIAMOND_PICKAXE, restoredView.getType());

        player.closeInventory();
        assertEquals(1, count(Material.DIAMOND_PICKAXE));
    }

    @Test
    void publicApiQuotesTheCachedProductionRepairRules() {
        PlexonBlacksmithAPI api = server.getServicesManager().load(PlexonBlacksmithAPI.class);
        assertNotNull(api);
        ItemStack damaged = damagedDiamondPickaxe(100);
        var quote = api.quoteRepair(player, damaged);
        assertTrue(quote.repairable());
        assertEquals(100, quote.previousDamage());
        assertEquals(0, quote.newDamage());
        assertEquals(100, quote.repairAmount());
        assertEquals(0, ((Damageable) quote.resultSnapshot().getItemMeta()).getDamage());
        double expected = Math.max(25.0, Math.round((1200.0 * 100.0 / damaged.getType().getMaxDurability()) * 100.0) / 100.0);
        assertEquals(expected, quote.price(), 0.001);
    }

    @Test
    void doubleClickCollectionFromPlayerInventoryIsCancelled() {
        open();
        InventoryClickEvent event = call(45, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);
        assertTrue(event.isCancelled());
    }

    @Test
    void committedRepairChargesOnceProducesOnceAndFiresOneEvent() {
        FakeEconomy economy = new FakeEconomy(10_000.0);
        server.getServicesManager().register(Economy.class, economy, plugin, ServicePriority.Normal);
        AtomicInteger repairedEvents = new AtomicInteger();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void repaired(PlexonItemRepairedEvent event) {
                assertFalse(event.getEventId().isBlank());
                assertNotNull(event.getTransactionId());
                repairedEvents.incrementAndGet();
            }
        }, plugin);

        open();
        player.setItemOnCursor(damagedDiamondPickaxe(100));
        call(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        double before = economy.balance(player);
        call(REPAIR_OUTPUT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        ItemStack output = player.getItemOnCursor();
        assertNotNull(output);
        assertEquals(Material.DIAMOND_PICKAXE, output.getType());
        assertEquals(0, ((Damageable) output.getItemMeta()).getDamage());
        assertEquals(1, repairedEvents.get());
        assertEquals(1, economy.withdrawals);
        assertTrue(economy.balance(player) < before);

        double after = economy.balance(player);
        player.setItemOnCursor(null);
        call(REPAIR_OUTPUT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        assertEquals(after, economy.balance(player), 0.001);
        assertEquals(1, economy.withdrawals);
        assertEquals(1, repairedEvents.get());
    }

    private void open() {
        assertTrue(server.dispatchCommand(player, "blacksmith"));
        assertEquals(45, player.getOpenInventory().getTopInventory().getSize());
    }

    private InventoryClickEvent call(int rawSlot, ClickType click, InventoryAction action) {
        InventoryClickEvent event = new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, rawSlot, click, action);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private ItemStack damagedDiamondPickaxe(int damage) {
        ItemStack stack = new ItemStack(Material.DIAMOND_PICKAXE);
        Damageable meta = (Damageable) stack.getItemMeta();
        meta.setDamage(damage);
        stack.setItemMeta(meta);
        return stack;
    }

    private int count(Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) total += stack.getAmount();
        }
        return total;
    }

    private static final class FakeEconomy implements Economy {
        private final Map<UUID, Double> balances = new HashMap<>();
        private final double initial;
        int withdrawals;

        private FakeEconomy(double initial) { this.initial = initial; }
        double balance(OfflinePlayer player) { return balances.getOrDefault(player.getUniqueId(), initial); }
        @Override public double getBalance(OfflinePlayer player) { return balance(player); }
        @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            double current = balance(player);
            if (current + 1.0e-9 < amount) return new EconomyResponse(false);
            balances.put(player.getUniqueId(), current - amount);
            withdrawals++;
            return new EconomyResponse(true);
        }
        @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            balances.put(player.getUniqueId(), balance(player) + amount);
            return new EconomyResponse(true);
        }
        @Override public String getName() { return "FakeVault"; }
    }
}
