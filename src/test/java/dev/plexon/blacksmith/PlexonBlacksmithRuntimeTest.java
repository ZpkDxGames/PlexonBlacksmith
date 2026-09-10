package dev.plexon.blacksmith;

import static org.junit.jupiter.api.Assertions.*;

import dev.plexon.blacksmith.api.PlexonBlacksmithAPI;
import dev.plexon.blacksmith.event.PlexonItemRepairedEvent;
import dev.plexon.blacksmith.event.PlexonItemsCombinedEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
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
    private static final int COMBINE_PRIMARY = 19;
    private static final int COMBINE_DONOR = 21;
    private static final int COMBINE_OUTPUT = 25;
    private static final int NEXT = 52;

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
        ItemStack damaged = customPickaxe(100, "plexon-tools:legendary");
        player.setItemOnCursor(damaged);
        call(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);

        assertCursorEmpty();
        ItemStack visible = player.getOpenInventory().getTopInventory().getItem(REPAIR_INPUT);
        assertNotNull(visible);
        assertEquals(100, ((Damageable) visible.getItemMeta()).getDamage());

        call(NEXT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        call(46, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        ItemStack restoredView = player.getOpenInventory().getTopInventory().getItem(REPAIR_INPUT);
        assertEquals(Material.DIAMOND_PICKAXE, restoredView.getType());

        player.closeInventory();
        assertEquals(1, count(Material.DIAMOND_PICKAXE));
    }

    @Test
    void repairQuotePreservesCustomPdcAndChangesOnlyDamage() {
        PlexonBlacksmithAPI api = server.getServicesManager().load(PlexonBlacksmithAPI.class);
        assertNotNull(api);
        ItemStack damaged = customPickaxe(100, "plexon-tools:legendary");
        var quote = api.quoteRepair(player, damaged);
        assertTrue(quote.repairable());
        assertEquals(0, ((Damageable) quote.resultSnapshot().getItemMeta()).getDamage());
        assertEquals("plexon-tools:legendary", identity(quote.resultSnapshot()));
        assertEquals(identity(damaged), identity(quote.resultSnapshot()));
        double expected = Math.max(25.0, Math.round((1200.0 * 100.0 / damaged.getType().getMaxDurability()) * 100.0) / 100.0);
        assertEquals(expected, quote.price(), 0.001);
    }

    @Test
    void doubleClickCollectionFromPlayerInventoryIsCancelled() {
        open();
        InventoryClickEvent event = call(54, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);
        assertTrue(event.isCancelled());
    }

    @Test
    void committedRepairChargesOnceProducesOnceAndFiresOneEvent() {
        FakeEconomy economy = economy();
        AtomicInteger repairedEvents = new AtomicInteger();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void repaired(PlexonItemRepairedEvent event) {
                assertFalse(event.getEventId().isBlank());
                repairedEvents.incrementAndGet();
            }
        }, plugin);

        open();
        player.setItemOnCursor(customPickaxe(100, "plexon-tools:legendary"));
        call(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        double before = economy.balance(player);
        call(REPAIR_OUTPUT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        ItemStack output = player.getItemOnCursor();
        assertNotNull(output);
        assertEquals(0, ((Damageable) output.getItemMeta()).getDamage());
        assertEquals("plexon-tools:legendary", identity(output));
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

    @Test
    void combineRejectsMismatchedCustomIdentity() {
        PlexonBlacksmithAPI api = server.getServicesManager().load(PlexonBlacksmithAPI.class);
        ItemStack primary = customPickaxe(900, "plexon-tools:legendary-a");
        ItemStack donor = customPickaxe(900, "plexon-tools:legendary-b");
        assertFalse(api.canCombine(primary, donor));
        assertFalse(api.quoteCombine(player, primary, donor).combinable());
    }

    @Test
    void combineRequiresConfirmationThenChargesAndConsumesExactlyOnce() {
        FakeEconomy economy = economy();
        AtomicInteger combinedEvents = new AtomicInteger();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void combined(PlexonItemsCombinedEvent event) {
                assertEquals("plexon-tools:legendary", identity(event.getResultSnapshot()));
                combinedEvents.incrementAndGet();
            }
        }, plugin);

        open();
        call(NEXT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        player.setItemOnCursor(customPickaxe(1200, "plexon-tools:legendary"));
        call(COMBINE_PRIMARY, ClickType.LEFT, InventoryAction.PLACE_ALL);
        player.setItemOnCursor(customPickaxe(1200, "plexon-tools:legendary"));
        call(COMBINE_DONOR, ClickType.LEFT, InventoryAction.PLACE_ALL);
        assertCursorEmpty();

        double before = economy.balance(player);
        call(COMBINE_OUTPUT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        assertEquals(0, economy.withdrawals, "First click must arm confirmation only");
        assertEquals(before, economy.balance(player), 0.001);
        assertCursorEmpty();

        call(COMBINE_OUTPUT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        ItemStack output = player.getItemOnCursor();
        assertNotNull(output);
        assertEquals(Material.DIAMOND_PICKAXE, output.getType());
        assertEquals("plexon-tools:legendary", identity(output));
        assertTrue(((Damageable) output.getItemMeta()).getDamage() < 1200);
        assertEquals(1, economy.withdrawals);
        assertEquals(1, combinedEvents.get());

        player.setItemOnCursor(null);
        call(COMBINE_OUTPUT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        assertEquals(1, economy.withdrawals);
        assertEquals(1, combinedEvents.get());
    }

    private FakeEconomy economy() {
        FakeEconomy economy = new FakeEconomy(10_000.0);
        server.getServicesManager().register(Economy.class, economy, plugin, ServicePriority.Normal);
        return economy;
    }

    private void open() {
        assertTrue(server.dispatchCommand(player, "blacksmith"));
        assertEquals(54, player.getOpenInventory().getTopInventory().getSize());
    }

    private InventoryClickEvent call(int rawSlot, ClickType click, InventoryAction action) {
        InventoryClickEvent event = new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, rawSlot, click, action);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private ItemStack customPickaxe(int damage, String identity) {
        ItemStack stack = new ItemStack(Material.DIAMOND_PICKAXE);
        Damageable meta = (Damageable) stack.getItemMeta();
        meta.setDamage(damage);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "test_identity"), PersistentDataType.STRING, identity);
        stack.setItemMeta(meta);
        return stack;
    }

    private String identity(ItemStack stack) {
        return stack.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(plugin, "test_identity"), PersistentDataType.STRING);
    }

    private void assertCursorEmpty() {
        ItemStack cursor = player.getItemOnCursor();
        assertTrue(cursor == null || cursor.getType().isAir() || cursor.getAmount() <= 0);
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
