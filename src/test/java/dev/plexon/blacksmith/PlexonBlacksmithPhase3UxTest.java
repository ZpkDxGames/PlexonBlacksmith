package dev.plexon.blacksmith;

import static org.junit.jupiter.api.Assertions.*;

import dev.plexon.blacksmith.api.PlexonBlacksmithAPI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlexonBlacksmithPhase3UxTest {
    private static final int HOME_REPAIR = 20;
    private static final int HOME_COMBINE = 22;
    private static final int HOME_ENCHANT = 24;
    private static final int HOME_GUIDE = 31;
    private static final int REPAIR_INPUT = 20;
    private static final int REPAIR_OUTPUT = 24;
    private static final int COMBINE_PRIMARY = 19;
    private static final int COMBINE_DONOR = 21;
    private static final int COMBINE_OUTPUT = 25;
    private static final int ENCHANT_TARGET = 19;
    private static final int ENCHANT_BOOK = 21;
    private static final int ENCHANT_OUTPUT = 25;
    private static final int BACK_HOME = 48;
    private static final int PRIMARY = 49;
    private static final int GUIDE = 50;
    private static final int STATUS = 51;

    ServerMock server;
    PlexonBlacksmithPhase3 plugin;
    PlayerMock player;
    PlexonBlacksmithAPI api;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PlexonBlacksmithPhase3.class);
        player = server.addPlayer("UxPlayer");
        api = server.getServicesManager().load(PlexonBlacksmithAPI.class);
        assertNotNull(api);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void blacksmithEntryOpensNonTransactionalHomeAndRoutesModes() {
        openHome();
        assertTrue(api.activeSession(player.getUniqueId()).isEmpty());
        assertEquals("REPAIR", name(HOME_REPAIR));
        assertEquals("COMBINE", name(HOME_COMBINE));
        assertEquals("ENCHANT", name(HOME_ENCHANT));
        assertEquals("GUIDE", name(HOME_GUIDE));

        click(HOME_COMBINE);
        assertEquals("COMBINE", api.activeSession(player.getUniqueId()).orElseThrow().page());
        assertEquals(54, player.getOpenInventory().getTopInventory().getSize());
    }

    @Test
    void repairPreviewIsNonTakeableAndPrimaryChargesExactlyOnce() {
        FakeEconomy economy = economy(10_000.0);
        openMode(HOME_REPAIR);
        ItemStack damaged = customPickaxe(100, "plexon-tools:legendary");
        player.setItemOnCursor(damaged);
        click(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        assertCursorEmpty();
        assertTrue(name(STATUS).contains("READY TO REPAIR"));
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(REPAIR_OUTPUT));

        click(REPAIR_OUTPUT);
        assertEquals(0, economy.withdrawals);
        assertCursorEmpty();
        assertNotNull(api.activeSession(player.getUniqueId()).orElseThrow().repairInput());

        click(PRIMARY);
        assertEquals(1, economy.withdrawals);
        ItemStack result = player.getItemOnCursor();
        assertNotNull(result);
        assertEquals(0, ((Damageable) result.getItemMeta()).getDamage());
        assertEquals("plexon-tools:legendary", identity(result));

        player.setItemOnCursor(null);
        click(PRIMARY);
        assertEquals(1, economy.withdrawals, "Duplicate primary clicks must not create a second charge");
    }

    @Test
    void repairRejectsFullAndUnsupportedItemsWithPlayerStates() {
        openMode(HOME_REPAIR);
        ItemStack full = customPickaxe(0, "plexon-tools:legendary");
        player.setItemOnCursor(full);
        click(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        assertTrue(name(STATUS).contains("ALREADY FULLY REPAIRED"));
        assertNull(api.activeSession(player.getUniqueId()).orElseThrow().repairInput());
        assertEquals(full, player.getItemOnCursor());

        ItemStack unsupported = new ItemStack(Material.STONE);
        player.setItemOnCursor(unsupported);
        click(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        assertTrue(name(STATUS).contains("NOT REPAIRABLE"));
        assertNull(api.activeSession(player.getUniqueId()).orElseThrow().repairInput());
    }

    @Test
    void combineExplainsMismatchAndDoesNotCharge() {
        FakeEconomy economy = economy(10_000.0);
        openMode(HOME_COMBINE);
        player.setItemOnCursor(customPickaxe(900, "plexon-tools:a"));
        click(COMBINE_PRIMARY, ClickType.LEFT, InventoryAction.PLACE_ALL);
        player.setItemOnCursor(customPickaxe(900, "plexon-tools:b"));
        click(COMBINE_DONOR, ClickType.LEFT, InventoryAction.PLACE_ALL);

        assertTrue(name(STATUS).contains("ITEMS CANNOT BE COMBINED"));
        click(PRIMARY);
        assertEquals(0, economy.withdrawals);
        var session = api.activeSession(player.getUniqueId()).orElseThrow();
        assertNotNull(session.combinePrimary());
        assertNotNull(session.combineDonor());
    }

    @Test
    void combineUsesExistingConfirmationAndCommitsExactlyOnce() {
        FakeEconomy economy = economy(10_000.0);
        openMode(HOME_COMBINE);
        player.setItemOnCursor(customPickaxe(900, "plexon-tools:legendary"));
        click(COMBINE_PRIMARY, ClickType.LEFT, InventoryAction.PLACE_ALL);
        player.setItemOnCursor(customPickaxe(900, "plexon-tools:legendary"));
        click(COMBINE_DONOR, ClickType.LEFT, InventoryAction.PLACE_ALL);
        assertTrue(name(STATUS).contains("READY TO COMBINE"));

        click(COMBINE_OUTPUT);
        assertEquals(0, economy.withdrawals, "The preview itself is never the commit action");
        click(PRIMARY);
        assertEquals(0, economy.withdrawals, "Phase 2 donor confirmation remains authoritative");
        assertTrue(api.activeSession(player.getUniqueId()).orElseThrow().confirmationArmed());
        click(PRIMARY);
        assertEquals(1, economy.withdrawals);
        assertNotNull(player.getItemOnCursor());

        player.setItemOnCursor(null);
        click(PRIMARY);
        assertEquals(1, economy.withdrawals);
    }

    @Test
    void enchantProjectsSupportedQuoteAndKeepsPreviewNonTransactional() {
        FakeEconomy economy = economy(10_000.0);
        openMode(HOME_ENCHANT);
        ItemStack target = customPickaxe(0, "plexon-tools:legendary");
        ItemStack book = enchantedBook();
        assertTrue(api.quoteEnchant(player, target, book).compatible());

        player.setItemOnCursor(target);
        click(ENCHANT_TARGET, ClickType.LEFT, InventoryAction.PLACE_ALL);
        player.setItemOnCursor(book);
        click(ENCHANT_BOOK, ClickType.LEFT, InventoryAction.PLACE_ALL);
        assertTrue(name(STATUS).contains("READY TO ENCHANT"));

        click(ENCHANT_OUTPUT);
        assertEquals(0, economy.withdrawals);
        click(PRIMARY);
        assertEquals(0, economy.withdrawals, "Existing enchant confirmation remains intact");
        click(PRIMARY);
        assertEquals(1, economy.withdrawals);
        ItemStack result = player.getItemOnCursor();
        assertNotNull(result);
        assertTrue(result.getItemMeta().hasEnchant(Enchantment.UNBREAKING));
        assertEquals("plexon-tools:legendary", identity(result));
    }

    @Test
    void insufficientBalanceDisablesPrimaryWithoutWithdrawing() {
        FakeEconomy economy = economy(0.0);
        openMode(HOME_REPAIR);
        player.setItemOnCursor(customPickaxe(500, "plexon-tools:legendary"));
        click(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        assertTrue(name(STATUS).contains("INSUFFICIENT FUNDS"));
        click(PRIMARY);
        assertEquals(0, economy.withdrawals);
        assertNotNull(api.activeSession(player.getUniqueId()).orElseThrow().repairInput());
    }

    @Test
    void guideNeverCreatesOrExecutesAWorkstationOperation() {
        FakeEconomy economy = economy(10_000.0);
        openHome();
        click(HOME_GUIDE);
        assertTrue(api.activeSession(player.getUniqueId()).isEmpty());
        assertEquals(0, economy.withdrawals);
        assertTrue(name(31).contains("COSTS"));

        click(BACK_HOME);
        click(HOME_REPAIR);
        player.setItemOnCursor(customPickaxe(100, "plexon-tools:legendary"));
        click(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        click(GUIDE);
        assertEquals(0, economy.withdrawals);
        assertNotNull(api.activeSession(player.getUniqueId()).orElseThrow().repairInput());
        click(BACK_HOME);
        assertTrue(name(STATUS).contains("READY TO REPAIR"));
    }

    @Test
    void backReturnsCachedInputBeforeOpeningHome() {
        openMode(HOME_REPAIR);
        ItemStack damaged = customPickaxe(100, "plexon-tools:return-me");
        player.setItemOnCursor(damaged);
        click(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        assertCursorEmpty();

        click(BACK_HOME);
        server.getScheduler().performOneTick();
        assertTrue(api.activeSession(player.getUniqueId()).isEmpty());
        assertEquals(1, countIdentity("plexon-tools:return-me"));
        assertEquals("REPAIR", name(HOME_REPAIR));
    }

    @Test
    void modeSwitchKeepsExactCachedInputAndNoCharge() {
        FakeEconomy economy = economy(10_000.0);
        openMode(HOME_REPAIR);
        player.setItemOnCursor(customPickaxe(100, "plexon-tools:kept"));
        click(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        click(46); // Combine tab
        assertEquals("COMBINE", api.activeSession(player.getUniqueId()).orElseThrow().page());
        click(45); // Repair tab
        var session = api.activeSession(player.getUniqueId()).orElseThrow();
        assertEquals("REPAIR", session.page());
        assertEquals("plexon-tools:kept", identity(session.repairInput()));
        assertEquals(0, economy.withdrawals);
    }

    @Test
    void resultPreviewRejectsShiftAndHotbarStyleExtractionAttempts() {
        FakeEconomy economy = economy(10_000.0);
        openMode(HOME_REPAIR);
        player.setItemOnCursor(customPickaxe(100, "plexon-tools:legendary"));
        click(REPAIR_INPUT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        InventoryClickEvent shift = click(REPAIR_OUTPUT, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertTrue(shift.isCancelled());
        assertEquals(0, economy.withdrawals);
        assertCursorEmpty();
        assertNotNull(api.activeSession(player.getUniqueId()).orElseThrow().repairInput());
    }

    private void openHome() {
        assertTrue(server.dispatchCommand(player, "blacksmith"));
        assertEquals(54, player.getOpenInventory().getTopInventory().getSize());
    }

    private void openMode(int homeSlot) {
        openHome();
        click(homeSlot);
        assertTrue(api.activeSession(player.getUniqueId()).isPresent());
    }

    private InventoryClickEvent click(int rawSlot) {
        return click(rawSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    private InventoryClickEvent click(int rawSlot, ClickType click, InventoryAction action) {
        InventoryClickEvent event = new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, rawSlot, click, action);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private String name(int rawSlot) {
        ItemStack item = player.getOpenInventory().getTopInventory().getItem(rawSlot);
        assertNotNull(item, "Expected UI item at slot " + rawSlot);
        assertNotNull(item.getItemMeta());
        assertNotNull(item.getItemMeta().displayName());
        return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
    }

    private FakeEconomy economy(double startingBalance) {
        FakeEconomy economy = new FakeEconomy(startingBalance);
        server.getServicesManager().register(Economy.class, economy, plugin, ServicePriority.Normal);
        return economy;
    }

    private ItemStack customPickaxe(int damage, String identity) {
        ItemStack stack = new ItemStack(Material.DIAMOND_PICKAXE);
        Damageable meta = (Damageable) stack.getItemMeta();
        meta.setDamage(damage);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "test_identity"), PersistentDataType.STRING, identity);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack enchantedBook() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(Enchantment.UNBREAKING, 2, false);
        book.setItemMeta(meta);
        return book;
    }

    private String identity(ItemStack stack) {
        if (stack == null) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(plugin, "test_identity"), PersistentDataType.STRING);
    }

    private void assertCursorEmpty() {
        ItemStack cursor = player.getItemOnCursor();
        assertTrue(cursor == null || cursor.getType().isAir() || cursor.getAmount() <= 0);
    }

    private int countIdentity(String identity) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && identity.equals(identity(stack))) count += stack.getAmount();
        }
        return count;
    }

    private static final class FakeEconomy implements Economy {
        private final Map<UUID, Double> balances = new HashMap<>();
        private final double initial;
        int withdrawals;

        private FakeEconomy(double initial) { this.initial = initial; }
        private double balance(OfflinePlayer player) { return balances.getOrDefault(player.getUniqueId(), initial); }
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
