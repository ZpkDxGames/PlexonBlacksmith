package dev.plexon.blacksmith;

import dev.plexon.blacksmith.api.BlacksmithSessionView;
import dev.plexon.blacksmith.api.CombineQuote;
import dev.plexon.blacksmith.api.EnchantQuote;
import dev.plexon.blacksmith.api.PlexonBlacksmithAPI;
import dev.plexon.blacksmith.api.RepairQuote;
import dev.plexon.blacksmith.event.PlexonItemEnchantedEvent;
import dev.plexon.blacksmith.event.PlexonItemRepairedEvent;
import dev.plexon.blacksmith.event.PlexonItemsCombinedEvent;
import dev.plexon.blacksmith.integration.core.CoreBridge;
import dev.plexon.blacksmith.integration.core.CoreBridgeFactory;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plexon Phase 2 workstation. Mutable service inventories remain fully plugin-owned so cached
 * exact ItemStacks cannot be redrawn or flattened by an external GUI framework.
 */
public class PlexonBlacksmithBridge extends JavaPlugin implements Listener, CommandExecutor {

    private enum Mode { REPAIR, COMBINE, ENCHANT }

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int SIZE = 54;

    private static final int REPAIR_INPUT = 20;
    private static final int REPAIR_PROCESS = 22;
    private static final int REPAIR_OUTPUT = 24;

    private static final int COMBINE_PRIMARY = 19;
    private static final int COMBINE_DONOR = 21;
    private static final int COMBINE_PROCESS = 23;
    private static final int COMBINE_OUTPUT = 25;

    private static final int ENCHANT_TARGET = 19;
    private static final int ENCHANT_BOOK = 21;
    private static final int ENCHANT_PROCESS = 23;
    private static final int ENCHANT_OUTPUT = 25;

    private static final int QUOTE_SLOT = 31;
    private static final int PREVIOUS_BUTTON = 46;
    private static final int GUIDE_BUTTON = 49;
    private static final int NEXT_BUTTON = 52;
    private static final int CLOSE_BUTTON = 53;

    private record Settings(
            boolean combineEnabled,
            boolean enchantEnabled,
            int confirmationSeconds,
            double expensiveThreshold,
            boolean confirmCombine,
            boolean confirmEnchant,
            double repairMinimum,
            double repairNetherite,
            double repairDiamond,
            double repairElytra,
            double repairTrident,
            double repairIron,
            double repairGold,
            double repairStone,
            double repairWood,
            double repairRanged,
            double repairDefault,
            double combineBasePrice,
            double combineBonusFraction,
            double combineRepairValueMultiplier,
            double enchantBasePrice,
            double enchantRareRate,
            double enchantPremiumRate,
            double enchantCommonRate,
            double enchantDefaultRate) {
    }

    private static final class Session {
        final UUID playerId;
        final UUID sessionId = UUID.randomUUID();
        final Inventory inventory;
        Mode mode = Mode.REPAIR;
        ItemStack repairInput;
        ItemStack combinePrimary;
        ItemStack combineDonor;
        ItemStack enchantTarget;
        ItemStack enchantBook;
        ItemStack result;
        double price;
        boolean transactionActive;
        String confirmationKey;
        long confirmationExpiresAt;

        Session(UUID playerId, Inventory inventory) {
            this.playerId = playerId;
            this.inventory = inventory;
        }
    }

    private static final class EnchantResult {
        final ItemStack result;
        final double price;
        final int applied;
        final Map<String, Integer> appliedEnchantments;

        EnchantResult(ItemStack result, double price, int applied, Map<String, Integer> appliedEnchantments) {
            this.result = result;
            this.price = price;
            this.applied = applied;
            this.appliedEnchantments = Collections.unmodifiableMap(new LinkedHashMap<>(appliedEnchantments));
        }
    }

    private static final class CombineResult {
        final ItemStack result;
        final double price;
        final int previousDamage;
        final int newDamage;
        final int repairAmount;

        CombineResult(ItemStack result, double price, int previousDamage, int newDamage, int repairAmount) {
            this.result = result;
            this.price = price;
            this.previousDamage = previousDamage;
            this.newDamage = newDamage;
            this.repairAmount = repairAmount;
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final DecimalFormat money = new DecimalFormat("#,##0.00");
    private final List<String> configWarnings = new ArrayList<>();
    private VaultHook vault;
    private CoreBridge coreBridge;
    private PlexonBlacksmithAPI publicApi;
    private Settings settings;

    @Override
    public void onEnable() {
        coreBridge = CoreBridgeFactory.resolve(this);
        coreBridge.registerStarting();
        try {
            saveDefaultConfig();
            settings = loadSettings();
            Bukkit.getPluginManager().registerEvents(this, this);
            PluginCommand command = getCommand("blacksmith");
            if (command != null) command.setExecutor(this);

            vault = VaultHook.tryCreate();
            publicApi = new PublicApi();
            Bukkit.getServicesManager().register(PlexonBlacksmithAPI.class, publicApi, this, ServicePriority.Normal);

            if (vault == null) {
                String detail = "Blacksmith workstation loaded, but Vault economy is unavailable; paid transactions are blocked";
                getLogger().warning(detail + ".");
                coreBridge.markDegraded(detail);
            } else {
                coreBridge.markReady("Repair, combine, enchanting, confirmation safety, public API/events and " + vault.name() + " economy are operational");
            }
            if (!configWarnings.isEmpty()) {
                getLogger().warning("Configuration loaded with " + configWarnings.size() + " fallback warning(s); use /blacksmith diagnostics.");
            }
            getLogger().info("PlexonBlacksmith " + getPluginMeta().getVersion() + " enabled in " + coreBridge.mode() + " mode. Phase 2 workstation inventory is fully plugin-owned.");
        } catch (Throwable error) {
            if (coreBridge != null) coreBridge.markFailed("Startup failed: " + error.getClass().getSimpleName());
            getLogger().log(Level.SEVERE, "PlexonBlacksmith could not start safely.", error);
            unregisterPublicApi();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        closeAllSessions();
        unregisterPublicApi();
        if (coreBridge != null) coreBridge.unregister();
        sessions.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("diagnostics")) {
            if (!sender.hasPermission("plexon.blacksmith.admin")) {
                message(sender, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>You do not have permission to view diagnostics.</red>");
                return true;
            }
            sendDiagnostics(sender);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("plexon.blacksmith.admin")) {
                message(sender, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>You do not have permission to reload Blacksmith.</red>");
                return true;
            }
            reloadServices(sender);
            return true;
        }
        if (args.length > 0) {
            message(sender, "<gold>Blacksmith <dark_gray>»</dark_gray> <gray>Usage: <white>/blacksmith [diagnostics|reload]</white></gray>");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Blacksmith can only be opened by a player. Use /blacksmith diagnostics from console.");
            return true;
        }
        if (!player.hasPermission("plexon.blacksmith.use")) {
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>You do not have permission to use this workstation.</red>");
            return true;
        }
        openBlacksmith(player);
        return true;
    }

    private void openBlacksmith(Player player) {
        Session old = sessions.remove(player.getUniqueId());
        if (old != null) returnStoredInputs(player, old);

        Inventory inventory = Bukkit.createInventory(null, SIZE,
                mm("<gradient:#f59e0b:#fde68a><bold>BLACKSMITH</bold></gradient> <dark_gray>•</dark_gray> <white>WORKSTATION</white>"));
        Session session = new Session(player.getUniqueId(), inventory);
        sessions.put(player.getUniqueId(), session);
        render(session);
        player.openInventory(inventory);
    }

    private boolean owns(Player player, InventoryView view) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && view != null && view.getTopInventory() == session.inventory;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!owns(player, event.getView())) return;

        Session session = sessions.get(player.getUniqueId());
        Inventory top = session.inventory;
        int raw = event.getRawSlot();

        if (raw >= top.getSize()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                shiftRoute(player, session, event);
            } else if (event.getClick() == ClickType.DOUBLE_CLICK || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
            return;
        }

        event.setCancelled(true);
        if (raw < 0) return;

        if (raw == CLOSE_BUTTON) {
            player.closeInventory();
            return;
        }
        if (raw == PREVIOUS_BUTTON) {
            Mode previous = previousMode(session.mode);
            if (previous != session.mode) {
                session.mode = previous;
                invalidateConfirmation(session);
                render(session);
            }
            return;
        }
        if (raw == NEXT_BUTTON) {
            Mode next = nextMode(session.mode);
            if (next != session.mode) {
                session.mode = next;
                invalidateConfirmation(session);
                render(session);
            }
            return;
        }
        if (raw == GUIDE_BUTTON) {
            sendGuide(player);
            return;
        }

        switch (session.mode) {
            case REPAIR -> {
                if (raw == REPAIR_INPUT) handleInputClick(player, session, event, REPAIR_INPUT);
                else if (raw == REPAIR_OUTPUT) completeRepair(player, session, event);
            }
            case COMBINE -> {
                if (raw == COMBINE_PRIMARY) handleInputClick(player, session, event, COMBINE_PRIMARY);
                else if (raw == COMBINE_DONOR) handleInputClick(player, session, event, COMBINE_DONOR);
                else if (raw == COMBINE_OUTPUT) completeCombine(player, session, event);
            }
            case ENCHANT -> {
                if (raw == ENCHANT_TARGET) handleInputClick(player, session, event, ENCHANT_TARGET);
                else if (raw == ENCHANT_BOOK) handleInputClick(player, session, event, ENCHANT_BOOK);
                else if (raw == ENCHANT_OUTPUT) completeEnchant(player, session, event);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!owns(player, event.getView())) return;

        Session session = sessions.get(player.getUniqueId());
        int topSlots = 0;
        int target = -1;
        for (int raw : event.getRawSlots()) {
            if (raw < SIZE) {
                topSlots++;
                target = raw;
            }
        }
        if (topSlots == 0) return;

        event.setCancelled(true);
        if (topSlots != 1 || event.getRawSlots().size() != 1) return;
        if (!isInputSlot(session.mode, target)) return;

        ItemStack cursor = event.getOldCursor();
        if (isEmpty(cursor)) return;
        if (!placeFromCursor(player, session, target, cursor)) return;
        event.setCursor(decremented(cursor));
        invalidateConfirmation(session);
        render(session);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getView().getTopInventory() != session.inventory) return;
        sessions.remove(player.getUniqueId());
        returnStoredInputs(player, session);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) returnStoredInputs(event.getPlayer(), session);
    }

    private void handleInputClick(Player player, Session session, InventoryClickEvent event, int slot) {
        ItemStack stored = getCached(session, slot);
        ItemStack cursor = event.getCursor();

        if (isEmpty(cursor)) {
            if (stored == null) return;
            player.setItemOnCursor(stored.clone());
            setCached(session, slot, null);
            invalidateConfirmation(session);
            render(session);
            return;
        }

        if (stored != null) {
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <gray>Take the existing input out before placing another item.</gray>");
            return;
        }

        if (!placeFromCursor(player, session, slot, cursor)) return;
        player.setItemOnCursor(decremented(cursor));
        invalidateConfirmation(session);
        render(session);
    }

    private boolean placeFromCursor(Player player, Session session, int slot, ItemStack cursor) {
        if (!slotEmpty(session, slot)) return false;
        if (!validateForSlot(player, session.mode, slot, cursor)) return false;
        ItemStack one = cursor.clone();
        one.setAmount(1);
        setCached(session, slot, one);
        return true;
    }

    private void shiftRoute(Player player, Session session, InventoryClickEvent event) {
        ItemStack clicked = event.getCurrentItem();
        if (isEmpty(clicked)) return;

        int target;
        switch (session.mode) {
            case REPAIR -> target = REPAIR_INPUT;
            case COMBINE -> target = session.combinePrimary == null ? COMBINE_PRIMARY : COMBINE_DONOR;
            case ENCHANT -> target = clicked.getType() == Material.ENCHANTED_BOOK ? ENCHANT_BOOK : ENCHANT_TARGET;
            default -> throw new IllegalStateException("Unknown mode " + session.mode);
        }

        if (!slotEmpty(session, target)) {
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <gray>That input is already occupied.</gray>");
            return;
        }
        if (!validateForSlot(player, session.mode, target, clicked)) return;

        ItemStack one = clicked.clone();
        one.setAmount(1);
        setCached(session, target, one);

        if (clicked.getAmount() <= 1) {
            event.setCurrentItem(null);
        } else {
            ItemStack remaining = clicked.clone();
            remaining.setAmount(clicked.getAmount() - 1);
            event.setCurrentItem(remaining);
        }
        invalidateConfirmation(session);
        render(session);
    }

    private boolean validateForSlot(Player player, Mode mode, int slot, ItemStack stack) {
        if (isEmpty(stack)) return false;

        if (mode == Mode.REPAIR && slot == REPAIR_INPUT) {
            if (!isRepairable(stack)) {
                message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Repair Input accepts only damaged, repairable items.</red>");
                return false;
            }
            return true;
        }

        if (mode == Mode.COMBINE) {
            if (!settings.combineEnabled()) {
                message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Combine is disabled by configuration.</red>");
                return false;
            }
            if (!isDamageableItem(stack)) {
                message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Combine accepts only compatible damageable items.</red>");
                return false;
            }
            return slot == COMBINE_PRIMARY || slot == COMBINE_DONOR;
        }

        if (mode == Mode.ENCHANT && slot == ENCHANT_BOOK) {
            if (!settings.enchantEnabled()) {
                message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Enchanting is disabled by configuration.</red>");
                return false;
            }
            if (stack.getType() != Material.ENCHANTED_BOOK) {
                message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>The Book input accepts only enchanted books.</red>");
                return false;
            }
            return true;
        }

        if (mode == Mode.ENCHANT && slot == ENCHANT_TARGET) {
            if (!settings.enchantEnabled()) {
                message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Enchanting is disabled by configuration.</red>");
                return false;
            }
            if (stack.getType() == Material.ENCHANTED_BOOK) {
                message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Put the enchanted book in the Book input.</red>");
                return false;
            }
            return true;
        }
        return false;
    }

    private boolean isInputSlot(Mode mode, int slot) {
        return switch (mode) {
            case REPAIR -> slot == REPAIR_INPUT;
            case COMBINE -> slot == COMBINE_PRIMARY || slot == COMBINE_DONOR;
            case ENCHANT -> slot == ENCHANT_TARGET || slot == ENCHANT_BOOK;
        };
    }

    private ItemStack getCached(Session session, int slot) {
        if (slot == REPAIR_INPUT) return session.repairInput;
        if (session.mode == Mode.COMBINE) {
            if (slot == COMBINE_PRIMARY) return session.combinePrimary;
            if (slot == COMBINE_DONOR) return session.combineDonor;
        }
        if (session.mode == Mode.ENCHANT) {
            if (slot == ENCHANT_TARGET) return session.enchantTarget;
            if (slot == ENCHANT_BOOK) return session.enchantBook;
        }
        return null;
    }

    private void setCached(Session session, int slot, ItemStack item) {
        if (session.mode == Mode.REPAIR && slot == REPAIR_INPUT) session.repairInput = item;
        else if (session.mode == Mode.COMBINE && slot == COMBINE_PRIMARY) session.combinePrimary = item;
        else if (session.mode == Mode.COMBINE && slot == COMBINE_DONOR) session.combineDonor = item;
        else if (session.mode == Mode.ENCHANT && slot == ENCHANT_TARGET) session.enchantTarget = item;
        else if (session.mode == Mode.ENCHANT && slot == ENCHANT_BOOK) session.enchantBook = item;
    }

    private boolean slotEmpty(Session session, int slot) {
        return getCached(session, slot) == null;
    }

    private void render(Session session) {
        Inventory inv = session.inventory;
        for (int slot = 0; slot < SIZE; slot++) inv.setItem(slot, null);
        drawFrame(inv, session.mode);
        session.result = null;
        session.price = 0.0;

        switch (session.mode) {
            case REPAIR -> renderRepair(session);
            case COMBINE -> renderCombine(session);
            case ENCHANT -> renderEnchant(session);
        }
    }

    private void renderRepair(Session session) {
        Inventory inv = session.inventory;
        inv.setItem(REPAIR_INPUT, session.repairInput == null
                ? ghost(Material.HOPPER, "<gold><bold>Input</bold></gold>", "<gray>Place one damaged item here.</gray>", "<dark_gray>Exact metadata is cached.</dark_gray>")
                : session.repairInput.clone());
        inv.setItem(REPAIR_PROCESS, ghost(Material.ANVIL, "<white><bold>Repair</bold></white>", "<gray>Restores durability only.</gray>", "<dark_gray>PDC/components remain untouched.</dark_gray>"));

        if (session.repairInput != null && isRepairable(session.repairInput)) {
            ItemStack result = repairResult(session.repairInput);
            double price = repairPrice(session.repairInput);
            session.result = result;
            session.price = price;
            String key = confirmationKey(Mode.REPAIR, price, session.repairInput);
            boolean requires = requiresConfirmation(Mode.REPAIR, price);
            boolean armed = requires && isConfirmationArmed(session, key);
            inv.setItem(REPAIR_OUTPUT, preview(result,
                    "<gray>Cost</gray> <white>$" + money.format(price) + "</white>",
                    requires ? (armed ? "<green><bold>CLICK AGAIN TO CONFIRM</bold></green>" : "<yellow>Click to arm confirmation</yellow>")
                            : "<green>Click to complete repair</green>"));
            inv.setItem(QUOTE_SLOT, quote(Material.GOLD_NUGGET, "<gold><bold>REPAIR READY</bold></gold>", price,
                    "<gray>Material</gray> <white>None</white>",
                    requires ? "<gray>Confirmation</gray> <white>Required</white>" : "<gray>Confirmation</gray> <white>Not required</white>"));
        } else {
            inv.setItem(REPAIR_OUTPUT, ghost(Material.LIGHT_GRAY_DYE, "<gray><bold>RESULT</bold></gray>", "<dark_gray>Waiting for a repairable item.</dark_gray>"));
            inv.setItem(QUOTE_SLOT, status(Material.GRAY_DYE, "<gray><bold>WAITING</bold></gray>", "<dark_gray>Place a damaged item to calculate an exact quote.</dark_gray>"));
        }
    }

    private void renderCombine(Session session) {
        Inventory inv = session.inventory;
        if (!settings.combineEnabled()) {
            inv.setItem(COMBINE_PROCESS, status(Material.BARRIER, "<red><bold>COMBINE LOCKED</bold></red>", "<gray>Disabled in config.yml.</gray>"));
            inv.setItem(QUOTE_SLOT, status(Material.GRAY_DYE, "<gray><bold>LOCKED</bold></gray>", "<dark_gray>No items or currency can be consumed.</dark_gray>"));
            return;
        }

        inv.setItem(COMBINE_PRIMARY, session.combinePrimary == null
                ? ghost(Material.HOPPER, "<gold><bold>Primary Item</bold></gold>", "<gray>The result inherits this item's identity.</gray>", "<dark_gray>Only damage may change.</dark_gray>")
                : session.combinePrimary.clone());
        inv.setItem(COMBINE_DONOR, session.combineDonor == null
                ? ghost(Material.CHEST, "<yellow><bold>Donor Item</bold></yellow>", "<gray>Consumed only after successful commit.</gray>", "<dark_gray>Must match primary metadata except damage.</dark_gray>")
                : session.combineDonor.clone());
        inv.setItem(COMBINE_PROCESS, ghost(Material.SMITHING_TABLE, "<white><bold>Combine</bold></white>", "<gray>Merges compatible remaining durability.</gray>", "<dark_gray>No custom metadata is merged.</dark_gray>"));

        CombineResult built = buildCombineResult(session.combinePrimary, session.combineDonor);
        if (built != null) {
            session.result = built.result;
            session.price = built.price;
            String key = confirmationKey(Mode.COMBINE, built.price, session.combinePrimary, session.combineDonor);
            boolean armed = isConfirmationArmed(session, key);
            inv.setItem(COMBINE_OUTPUT, preview(built.result,
                    "<gray>Cost</gray> <white>$" + money.format(built.price) + "</white>",
                    armed ? "<green><bold>CLICK AGAIN TO CONFIRM</bold></green>" : "<yellow>Click to arm donor consumption</yellow>"));
            inv.setItem(QUOTE_SLOT, quote(Material.GOLD_NUGGET, "<gold><bold>COMBINE READY</bold></gold>", built.price,
                    "<gray>Material</gray> <white>1 donor item</white>",
                    "<gray>Durability restored</gray> <white>" + built.repairAmount + "</white>"));
        } else {
            inv.setItem(COMBINE_OUTPUT, ghost(Material.LIGHT_GRAY_DYE, "<gray><bold>RESULT</bold></gray>", "<dark_gray>No safe combine result yet.</dark_gray>"));
            inv.setItem(QUOTE_SLOT, status(Material.GRAY_DYE, "<gray><bold>WAITING</bold></gray>",
                    "<dark_gray>Use matching custom identity/material with useful donor durability.</dark_gray>"));
        }
    }

    private void renderEnchant(Session session) {
        Inventory inv = session.inventory;
        if (!settings.enchantEnabled()) {
            inv.setItem(ENCHANT_PROCESS, status(Material.BARRIER, "<red><bold>ENCHANT LOCKED</bold></red>", "<gray>Disabled in config.yml.</gray>"));
            inv.setItem(QUOTE_SLOT, status(Material.GRAY_DYE, "<gray><bold>LOCKED</bold></gray>", "<dark_gray>No book or currency can be consumed.</dark_gray>"));
            return;
        }

        inv.setItem(ENCHANT_TARGET, session.enchantTarget == null
                ? ghost(Material.HOPPER, "<light_purple><bold>Target Item</bold></light_purple>", "<gray>Place the item to enchant here.</gray>", "<dark_gray>Target metadata remains authoritative.</dark_gray>")
                : session.enchantTarget.clone());
        inv.setItem(ENCHANT_BOOK, session.enchantBook == null
                ? ghost(Material.ENCHANTED_BOOK, "<light_purple><bold>Enchanted Book</bold></light_purple>", "<gray>Compatible upgrades only.</gray>", "<dark_gray>Consumed after successful commit.</dark_gray>")
                : session.enchantBook.clone());
        inv.setItem(ENCHANT_PROCESS, ghost(Material.ENCHANTING_TABLE, "<white><bold>Apply Enchantments</bold></white>", "<gray>Conflicts and non-upgrades are skipped.</gray>"));

        EnchantResult built = buildEnchantResult(session.enchantTarget, session.enchantBook);
        if (built != null) {
            session.result = built.result;
            session.price = built.price;
            String key = confirmationKey(Mode.ENCHANT, built.price, session.enchantTarget, session.enchantBook);
            boolean armed = isConfirmationArmed(session, key);
            inv.setItem(ENCHANT_OUTPUT, preview(built.result,
                    "<gray>Cost</gray> <white>$" + money.format(built.price) + "</white>",
                    armed ? "<green><bold>CLICK AGAIN TO CONFIRM</bold></green>" : "<yellow>Click to arm book consumption</yellow>"));
            inv.setItem(QUOTE_SLOT, quote(Material.GOLD_NUGGET, "<light_purple><bold>ENCHANT READY</bold></light_purple>", built.price,
                    "<gray>Material</gray> <white>1 enchanted book</white>",
                    "<gray>Applied upgrades</gray> <white>" + built.applied + "</white>"));
        } else {
            inv.setItem(ENCHANT_OUTPUT, ghost(Material.LIGHT_GRAY_DYE, "<gray><bold>RESULT</bold></gray>", "<dark_gray>No compatible enchant upgrade yet.</dark_gray>"));
            inv.setItem(QUOTE_SLOT, status(Material.GRAY_DYE, "<gray><bold>WAITING</bold></gray>", "<dark_gray>Place a target and compatible enchanted book.</dark_gray>"));
        }
    }

    private void drawFrame(Inventory inv, Mode mode) {
        for (int slot = 0; slot < SIZE; slot++) {
            Material material = edge(slot) ? Material.GRAY_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE;
            inv.setItem(slot, pane(material));
        }

        Material icon = switch (mode) {
            case REPAIR -> Material.ANVIL;
            case COMBINE -> Material.SMITHING_TABLE;
            case ENCHANT -> Material.ENCHANTING_TABLE;
        };
        String title = switch (mode) {
            case REPAIR -> "<gradient:#f59e0b:#fde68a><bold>REPAIR</bold></gradient>";
            case COMBINE -> "<gradient:#fbbf24:#fef3c7><bold>COMBINE</bold></gradient>";
            case ENCHANT -> "<gradient:#c084fc:#f5d0fe><bold>ENCHANT</bold></gradient>";
        };
        inv.setItem(4, ghost(icon, title,
                "<dark_gray>PAGE " + (mode.ordinal() + 1) + " / 3</dark_gray>",
                "<gray>Exact preview • deterministic cost • safe commit</gray>"));

        if (mode == Mode.REPAIR) {
            inv.setItem(PREVIOUS_BUTTON, status(Material.GRAY_DYE, "<gray><bold>BACK</bold></gray>", "<dark_gray>First page.</dark_gray>"));
        } else {
            inv.setItem(PREVIOUS_BUTTON, ghost(Material.ARROW, "<white><bold>← BACK</bold></white>", "<gray>Open " + modeName(previousMode(mode)) + ".</gray>"));
        }
        inv.setItem(GUIDE_BUTTON, ghost(Material.WRITABLE_BOOK, "<white><bold>WORKSTATION GUIDE</bold></white>",
                "<gold>Repair</gold> <gray>• one damaged item</gray>",
                "<yellow>Combine</yellow> <gray>• primary + matching donor</gray>",
                "<light_purple>Enchant</light_purple> <gray>• target + enchanted book</gray>",
                "",
                "<dark_gray>Inputs remain cached between pages.</dark_gray>",
                "<dark_gray>Closing returns every unused input.</dark_gray>"));
        if (mode == Mode.ENCHANT) {
            inv.setItem(NEXT_BUTTON, status(Material.GRAY_DYE, "<gray><bold>NEXT</bold></gray>", "<dark_gray>Last page.</dark_gray>"));
        } else {
            inv.setItem(NEXT_BUTTON, ghost(Material.ARROW, "<white><bold>NEXT →</bold></white>", "<gray>Open " + modeName(nextMode(mode)) + ".</gray>"));
        }
        inv.setItem(CLOSE_BUTTON, ghost(Material.BARRIER, "<red><bold>CLOSE</bold></red>", "<gray>Return all unused inputs.</gray>"));
    }

    private boolean edge(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        return row == 0 || row == 5 || column == 0 || column == 8;
    }

    private String modeName(Mode mode) {
        return switch (mode) {
            case REPAIR -> "Repair";
            case COMBINE -> "Combine";
            case ENCHANT -> "Enchant";
        };
    }

    private Mode previousMode(Mode mode) {
        return switch (mode) {
            case REPAIR -> Mode.REPAIR;
            case COMBINE -> Mode.REPAIR;
            case ENCHANT -> Mode.COMBINE;
        };
    }

    private Mode nextMode(Mode mode) {
        return switch (mode) {
            case REPAIR -> Mode.COMBINE;
            case COMBINE -> Mode.ENCHANT;
            case ENCHANT -> Mode.ENCHANT;
        };
    }

    private void sendGuide(Player player) {
        message(player, "<gold><bold>BLACKSMITH</bold></gold> <dark_gray>•</dark_gray> <white>Repair changes durability only.</white>");
        message(player, "<yellow><bold>COMBINE</bold></yellow> <dark_gray>•</dark_gray> <white>Primary + metadata-matching donor; donor is consumed after confirmation.</white>");
        message(player, "<light_purple><bold>ENCHANT</bold></light_purple> <dark_gray>•</dark_gray> <white>Target + enchanted book; compatible higher levels only.</white>");
        message(player, "<gray>Unused cached inputs are returned on close, quit, reload and plugin shutdown.</gray>");
    }

    private void completeRepair(Player player, Session session, InventoryClickEvent event) {
        if (!beginTransactionValidation(player, session, event, "repair")) return;
        if (session.repairInput == null || !isRepairable(session.repairInput)) {
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <gray>Place a damaged item in Repair Input first.</gray>");
            return;
        }

        ItemStack original = session.repairInput.clone();
        ItemStack result = repairResult(original);
        double price = repairPrice(original);
        String key = confirmationKey(Mode.REPAIR, price, original);
        if (requiresConfirmation(Mode.REPAIR, price) && !confirmOrArm(player, session, key)) return;

        int previousDamage = damageOf(original);
        UUID transactionId = UUID.randomUUID();
        session.transactionActive = true;
        boolean charged = false;
        boolean committed = false;
        try {
            if (!charge(player, price)) return;
            charged = true;

            player.setItemOnCursor(result.clone());
            session.repairInput = null;
            committed = true;
        } catch (Throwable failure) {
            if (charged && !committed) refundAfterFailure(player, price, failure);
            getLogger().log(Level.SEVERE, "Repair transaction " + transactionId + " failed before commit.", failure);
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Repair failed safely. Input was preserved" + (charged ? " and payment refund was requested." : ".") + "</red>");
            return;
        } finally {
            session.transactionActive = false;
            invalidateConfirmation(session);
        }

        renderSafely(session);
        fireRepairEvent(player, transactionId, result, previousDamage, price);
        message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <green>Repaired for <white>$" + money.format(price) + "</white>.</green>");
    }

    private void completeCombine(Player player, Session session, InventoryClickEvent event) {
        if (!settings.combineEnabled()) {
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Combine is disabled.</red>");
            return;
        }
        if (!beginTransactionValidation(player, session, event, "combine")) return;

        CombineResult built = buildCombineResult(session.combinePrimary, session.combineDonor);
        if (built == null) {
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <gray>Place a compatible primary and donor first.</gray>");
            return;
        }
        ItemStack primary = session.combinePrimary.clone();
        ItemStack donor = session.combineDonor.clone();
        String key = confirmationKey(Mode.COMBINE, built.price, primary, donor);
        if (requiresConfirmation(Mode.COMBINE, built.price) && !confirmOrArm(player, session, key)) return;

        UUID transactionId = UUID.randomUUID();
        session.transactionActive = true;
        boolean charged = false;
        boolean committed = false;
        try {
            if (!charge(player, built.price)) return;
            charged = true;

            player.setItemOnCursor(built.result.clone());
            session.combinePrimary = null;
            session.combineDonor = null;
            committed = true;
        } catch (Throwable failure) {
            if (charged && !committed) refundAfterFailure(player, built.price, failure);
            getLogger().log(Level.SEVERE, "Combine transaction " + transactionId + " failed before commit.", failure);
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Combine failed safely. Inputs were preserved" + (charged ? " and payment refund was requested." : ".") + "</red>");
            return;
        } finally {
            session.transactionActive = false;
            invalidateConfirmation(session);
        }

        renderSafely(session);
        fireCombineEvent(player, transactionId, primary, donor, built);
        message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <green>Combined for <white>$" + money.format(built.price) + "</white>.</green>");
    }

    private void completeEnchant(Player player, Session session, InventoryClickEvent event) {
        if (!settings.enchantEnabled()) {
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Enchanting is disabled.</red>");
            return;
        }
        if (!beginTransactionValidation(player, session, event, "enchant")) return;

        EnchantResult built = buildEnchantResult(session.enchantTarget, session.enchantBook);
        if (built == null) {
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <gray>Place an item and a compatible enchanted book first.</gray>");
            return;
        }

        ItemStack originalTarget = session.enchantTarget.clone();
        ItemStack originalBook = session.enchantBook.clone();
        String key = confirmationKey(Mode.ENCHANT, built.price, originalTarget, originalBook);
        if (requiresConfirmation(Mode.ENCHANT, built.price) && !confirmOrArm(player, session, key)) return;

        UUID transactionId = UUID.randomUUID();
        session.transactionActive = true;
        boolean charged = false;
        boolean committed = false;
        try {
            if (!charge(player, built.price)) return;
            charged = true;

            player.setItemOnCursor(built.result.clone());
            session.enchantTarget = null;
            session.enchantBook = null;
            committed = true;
        } catch (Throwable failure) {
            if (charged && !committed) refundAfterFailure(player, built.price, failure);
            getLogger().log(Level.SEVERE, "Enchant transaction " + transactionId + " failed before commit.", failure);
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Enchant failed safely. Inputs were preserved" + (charged ? " and payment refund was requested." : ".") + "</red>");
            return;
        } finally {
            session.transactionActive = false;
            invalidateConfirmation(session);
        }

        renderSafely(session);
        fireEnchantEvent(player, transactionId, built, originalBook);
        message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <green>Enchantments applied for <white>$" + money.format(built.price) + "</white>.</green>");
    }

    private boolean beginTransactionValidation(Player player, Session session, InventoryClickEvent event, String operation) {
        if (!Bukkit.isPrimaryThread()) {
            getLogger().severe("Rejected asynchronous " + operation + " transaction for " + player.getName());
            return false;
        }
        if (session.transactionActive) return false;
        if (!isEmpty(event.getCursor())) {
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <gray>Clear your cursor before taking the result.</gray>");
            return false;
        }
        return true;
    }

    private boolean requiresConfirmation(Mode mode, double price) {
        return switch (mode) {
            case REPAIR -> price >= settings.expensiveThreshold();
            case COMBINE -> settings.confirmCombine();
            case ENCHANT -> settings.confirmEnchant();
        };
    }

    private boolean confirmOrArm(Player player, Session session, String key) {
        long now = System.currentTimeMillis();
        if (key.equals(session.confirmationKey) && now <= session.confirmationExpiresAt) {
            invalidateConfirmation(session);
            return true;
        }
        session.confirmationKey = key;
        session.confirmationExpiresAt = now + settings.confirmationSeconds() * 1000L;
        renderSafely(session);
        message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <yellow>Confirmation armed. Click the result again within <white>" + settings.confirmationSeconds() + "s</white>.</yellow>");
        return false;
    }

    private boolean isConfirmationArmed(Session session, String key) {
        return key.equals(session.confirmationKey) && System.currentTimeMillis() <= session.confirmationExpiresAt;
    }

    private String confirmationKey(Mode mode, double price, ItemStack... stacks) {
        int hash = 17;
        for (ItemStack stack : stacks) hash = 31 * hash + (stack == null ? 0 : stack.hashCode());
        hash = 31 * hash + Long.hashCode(Double.doubleToLongBits(price));
        return mode.name() + ':' + Integer.toUnsignedString(hash, 16);
    }

    private void invalidateConfirmation(Session session) {
        session.confirmationKey = null;
        session.confirmationExpiresAt = 0L;
    }

    private boolean charge(Player player, double price) {
        if (vault == null) vault = VaultHook.tryCreate();
        if (vault == null) {
            if (coreBridge != null) coreBridge.markDegraded("Vault economy is unavailable; paid transactions are blocked");
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Vault economy is unavailable.</red>");
            return false;
        }
        if (!vault.has(player, price)) {
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>You need <white>$" + money.format(price) + "</white>.</red>");
            return false;
        }
        if (!vault.withdraw(player, price)) {
            message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>Payment failed. Nothing was consumed.</red>");
            return false;
        }
        return true;
    }

    private void refundAfterFailure(Player player, double price, Throwable cause) {
        if (vault != null) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                if (vault.refund(player, price)) return;
            }
        }
        getLogger().log(Level.SEVERE, "CRITICAL: Vault refund failed after three bounded attempts for " + player.getUniqueId() + " amount $" + money.format(price), cause);
        message(player, "<gold>Blacksmith <dark_gray>»</dark_gray> <red>A provider-level refund failed after retries. Contact an administrator with the transaction time immediately.</red>");
    }

    private boolean isDamageableItem(ItemStack stack) {
        if (isEmpty(stack)) return false;
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return false;
        if (meta.isUnbreakable()) return false;
        return maxDamage(stack, damageable) > 0;
    }

    private boolean isRepairable(ItemStack stack) {
        if (!isDamageableItem(stack)) return false;
        Damageable damageable = (Damageable) stack.getItemMeta();
        return damageable.getDamage() > 0;
    }

    private int maxDamage(ItemStack stack, Damageable damageable) {
        try {
            Method has = damageable.getClass().getMethod("hasMaxDamage");
            Method get = damageable.getClass().getMethod("getMaxDamage");
            if (Boolean.TRUE.equals(has.invoke(damageable))) {
                Object max = get.invoke(damageable);
                if (max instanceof Number n && n.intValue() > 0) return n.intValue();
            }
        } catch (Throwable ignored) {
        }
        return stack.getType().getMaxDurability();
    }

    private ItemStack repairResult(ItemStack input) {
        ItemStack result = input.clone();
        result.setAmount(1);
        ItemMeta meta = result.getItemMeta();
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(0);
            result.setItemMeta(meta);
        }
        return result;
    }

    private double repairPrice(ItemStack input) {
        ItemMeta meta = input.getItemMeta();
        if (!(meta instanceof Damageable d)) return 0.0;
        int max = maxDamage(input, d);
        if (max <= 0) return 0.0;
        double fraction = Math.max(0.0, Math.min(1.0, (double) d.getDamage() / (double) max));
        return Math.max(settings.repairMinimum(), roundMoney(fullRepairPrice(input.getType()) * fraction));
    }

    private double fullRepairPrice(Material material) {
        String n = material.name();
        if (n.contains("NETHERITE") || n.equals("MACE")) return settings.repairNetherite();
        if (n.contains("DIAMOND")) return settings.repairDiamond();
        if (n.equals("ELYTRA")) return settings.repairElytra();
        if (n.equals("TRIDENT")) return settings.repairTrident();
        if (n.contains("IRON") || n.contains("CHAINMAIL")) return settings.repairIron();
        if (n.contains("GOLD")) return settings.repairGold();
        if (n.contains("STONE")) return settings.repairStone();
        if (n.contains("WOODEN")) return settings.repairWood();
        if (n.equals("BOW") || n.equals("CROSSBOW") || n.equals("FISHING_ROD") || n.equals("SHIELD")) return settings.repairRanged();
        return settings.repairDefault();
    }

    private CombineResult buildCombineResult(ItemStack primary, ItemStack donor) {
        if (!settings.combineEnabled()) return null;
        if (!isDamageableItem(primary) || !isDamageableItem(donor)) return null;
        if (primary.getType() != donor.getType()) return null;
        if (!sameIdentityExceptDamage(primary, donor)) return null;

        Damageable primaryMeta = (Damageable) primary.getItemMeta();
        Damageable donorMeta = (Damageable) donor.getItemMeta();
        int max = maxDamage(primary, primaryMeta);
        int donorMax = maxDamage(donor, donorMeta);
        if (max <= 0 || donorMax != max) return null;

        int previousDamage = Math.max(0, Math.min(max, primaryMeta.getDamage()));
        int primaryRemaining = max - previousDamage;
        int donorRemaining = max - Math.max(0, Math.min(max, donorMeta.getDamage()));
        int bonus = Math.max(0, (int) Math.round(max * settings.combineBonusFraction()));
        int combinedRemaining = Math.min(max, primaryRemaining + donorRemaining + bonus);
        int newDamage = Math.max(0, max - combinedRemaining);
        if (newDamage >= previousDamage) return null;

        ItemStack result = primary.clone();
        result.setAmount(1);
        ItemMeta meta = result.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return null;
        damageable.setDamage(newDamage);
        result.setItemMeta(meta);

        int repairAmount = previousDamage - newDamage;
        double durabilityValue = fullRepairPrice(primary.getType()) * ((double) repairAmount / (double) max);
        double price = roundMoney(settings.combineBasePrice() + durabilityValue * settings.combineRepairValueMultiplier());
        return new CombineResult(result, price, previousDamage, newDamage, repairAmount);
    }

    private boolean sameIdentityExceptDamage(ItemStack first, ItemStack second) {
        ItemStack a = normalizedIdentity(first);
        ItemStack b = normalizedIdentity(second);
        return a != null && b != null && a.isSimilar(b);
    }

    private ItemStack normalizedIdentity(ItemStack input) {
        if (input == null) return null;
        ItemStack copy = input.clone();
        copy.setAmount(1);
        ItemMeta meta = copy.getItemMeta();
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(0);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    private EnchantResult buildEnchantResult(ItemStack target, ItemStack book) {
        if (!settings.enchantEnabled()) return null;
        if (isEmpty(target) || isEmpty(book) || book.getType() != Material.ENCHANTED_BOOK) return null;
        ItemMeta bookMeta = book.getItemMeta();
        if (!(bookMeta instanceof EnchantmentStorageMeta storage)) return null;
        if (storage.getStoredEnchants().isEmpty()) return null;

        ItemStack result = target.clone();
        result.setAmount(1);
        ItemMeta targetMeta = result.getItemMeta();
        if (targetMeta == null) return null;

        int applied = 0;
        double price = settings.enchantBasePrice();
        Set<Enchantment> appliedNow = new HashSet<>();
        Map<String, Integer> appliedEnchantments = new LinkedHashMap<>();

        for (Map.Entry<Enchantment, Integer> entry : storage.getStoredEnchants().entrySet()) {
            Enchantment enchant = entry.getKey();
            int requested = Math.min(entry.getValue(), enchant.getMaxLevel());
            if (requested <= targetMeta.getEnchantLevel(enchant)) continue;
            if (!enchant.canEnchantItem(result)) continue;
            if (targetMeta.hasConflictingEnchant(enchant)) continue;

            boolean conflict = false;
            for (Enchantment already : appliedNow) {
                if (already.conflictsWith(enchant) || enchant.conflictsWith(already)) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) continue;

            if (targetMeta.addEnchant(enchant, requested, false)) {
                applied++;
                appliedNow.add(enchant);
                appliedEnchantments.put(enchantmentKey(enchant), requested);
                price += enchantRate(enchant) * requested;
            }
        }

        if (applied == 0) return null;
        result.setItemMeta(targetMeta);
        return new EnchantResult(result, roundMoney(price), applied, appliedEnchantments);
    }

    private String enchantmentKey(Enchantment enchantment) {
        try {
            return enchantment.getKey().toString();
        } catch (Throwable ignored) {
            return enchantment.toString();
        }
    }

    private double enchantRate(Enchantment enchantment) {
        String key;
        try {
            key = enchantment.getKey().getKey().toLowerCase();
        } catch (Throwable ignored) {
            key = enchantment.toString().toLowerCase();
        }
        if (key.contains("mending") || key.contains("swift_sneak") || key.contains("soul_speed") || key.contains("wind_burst")) return settings.enchantRareRate();
        if (key.contains("fortune") || key.contains("silk_touch") || key.contains("looting") || key.contains("protection") || key.contains("sharpness") || key.contains("power")) return settings.enchantPremiumRate();
        if (key.contains("unbreaking") || key.contains("efficiency") || key.contains("respiration") || key.contains("feather_falling")) return settings.enchantCommonRate();
        return settings.enchantDefaultRate();
    }

    private ItemStack preview(ItemStack clean, String priceLine, String actionLine) {
        ItemStack preview = clean.clone();
        ItemMeta meta = preview.getItemMeta();
        if (meta == null) return preview;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(mm("<dark_gray>BLACKSMITH SERVICE PREVIEW</dark_gray>"));
        lore.add(mm(priceLine));
        lore.add(mm(actionLine));
        meta.lore(lore);
        preview.setItemMeta(meta);
        return preview;
    }

    private ItemStack quote(Material material, String state, double price, String... details) {
        List<String> lines = new ArrayList<>();
        lines.add("<gray>Currency</gray> <white>$" + money.format(price) + "</white>");
        Collections.addAll(lines, details);
        lines.add("");
        lines.add("<dark_gray>Charge occurs only during confirmed commit.</dark_gray>");
        return ghost(material, state, lines.toArray(String[]::new));
    }

    private ItemStack status(Material material, String name, String... lore) {
        return ghost(material, name, lore);
    }

    private ItemStack pane(Material material) {
        return ghost(material, "<black> </black>");
    }

    private ItemStack ghost(Material material, String name, String... loreLines) {
        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(mm(name));
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) lore.add(line.isEmpty() ? Component.empty() : mm(line));
            meta.lore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack decremented(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 1) return null;
        ItemStack remaining = stack.clone();
        remaining.setAmount(stack.getAmount() - 1);
        return remaining;
    }

    private void returnStoredInputs(Player player, Session session) {
        if (session.repairInput != null) giveSafely(player, session.repairInput);
        if (session.combinePrimary != null) giveSafely(player, session.combinePrimary);
        if (session.combineDonor != null) giveSafely(player, session.combineDonor);
        if (session.enchantTarget != null) giveSafely(player, session.enchantTarget);
        if (session.enchantBook != null) giveSafely(player, session.enchantBook);
        session.repairInput = null;
        session.combinePrimary = null;
        session.combineDonor = null;
        session.enchantTarget = null;
        session.enchantBook = null;
        session.result = null;
        session.price = 0.0;
        invalidateConfirmation(session);
    }

    private void giveSafely(Player player, ItemStack stack) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack.clone());
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0;
    }

    private static double roundMoney(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private int damageOf(ItemStack stack) {
        if (stack == null) return 0;
        ItemMeta meta = stack.getItemMeta();
        return meta instanceof Damageable damageable ? damageable.getDamage() : 0;
    }

    private void renderSafely(Session session) {
        try {
            if (sessions.get(session.playerId) == session) render(session);
        } catch (Throwable error) {
            getLogger().log(Level.WARNING, "Transaction state is safe, but Blacksmith GUI refresh failed for session " + session.sessionId, error);
        }
    }

    private void fireRepairEvent(Player player, UUID transactionId, ItemStack result, int previousDamage, double price) {
        String eventId = transactionId + ":repair";
        try {
            Bukkit.getPluginManager().callEvent(new PlexonItemRepairedEvent(
                    player, transactionId, eventId, result, previousDamage, 0, previousDamage, price, economyName()));
        } catch (Throwable error) {
            getLogger().log(Level.WARNING, "Repair transaction committed, but event dispatch failed for " + eventId, error);
        }
    }

    private void fireCombineEvent(Player player, UUID transactionId, ItemStack primary, ItemStack donor, CombineResult built) {
        String eventId = transactionId + ":combine";
        try {
            Bukkit.getPluginManager().callEvent(new PlexonItemsCombinedEvent(
                    player, transactionId, eventId, primary, donor, built.result,
                    built.previousDamage, built.newDamage, built.repairAmount, built.price, economyName()));
        } catch (Throwable error) {
            getLogger().log(Level.WARNING, "Combine transaction committed, but event dispatch failed for " + eventId, error);
        }
    }

    private void fireEnchantEvent(Player player, UUID transactionId, EnchantResult built, ItemStack originalBook) {
        String eventId = transactionId + ":enchant";
        try {
            Bukkit.getPluginManager().callEvent(new PlexonItemEnchantedEvent(
                    player, transactionId, eventId, built.result, originalBook, built.appliedEnchantments, built.price, economyName()));
        } catch (Throwable error) {
            getLogger().log(Level.WARNING, "Enchant transaction committed, but event dispatch failed for " + eventId, error);
        }
    }

    private String economyName() {
        return vault == null ? "unavailable" : vault.name();
    }

    private void unregisterPublicApi() {
        if (publicApi == null) return;
        try {
            Bukkit.getServicesManager().unregister(PlexonBlacksmithAPI.class, publicApi);
        } catch (Throwable error) {
            getLogger().log(Level.WARNING, "Could not unregister PlexonBlacksmithAPI cleanly.", error);
        } finally {
            publicApi = null;
        }
    }

    private void closeAllSessions() {
        for (Map.Entry<UUID, Session> entry : new HashMap<>(sessions).entrySet()) {
            Session session = sessions.remove(entry.getKey());
            if (session == null) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                returnStoredInputs(player, session);
                try {
                    if (player.getOpenInventory().getTopInventory() == session.inventory) player.closeInventory();
                } catch (Throwable ignored) {
                }
            } else if (hasStoredInputs(session)) {
                getLogger().severe("Session " + session.sessionId + " became ownerless during shutdown. PlayerQuit handling should have returned these inputs earlier.");
            }
        }
    }

    private boolean hasStoredInputs(Session session) {
        return session.repairInput != null || session.combinePrimary != null || session.combineDonor != null
                || session.enchantTarget != null || session.enchantBook != null;
    }

    private void reloadServices(CommandSender sender) {
        closeAllSessions();
        reloadConfig();
        settings = loadSettings();
        vault = VaultHook.tryCreate();
        if (vault == null) {
            if (coreBridge != null) coreBridge.markDegraded("Reloaded; Vault economy is unavailable; paid transactions are blocked");
            message(sender, "<gold>Blacksmith <dark_gray>»</dark_gray> <yellow>Reloaded in degraded mode: Vault economy is unavailable.</yellow>");
        } else {
            if (coreBridge != null) coreBridge.markReady("Reloaded successfully; economy provider " + vault.name() + " is operational");
            sender.sendMessage(Component.text("Blacksmith » Reloaded successfully. Economy: " + vault.name(), NamedTextColor.GREEN));
        }
    }

    private Settings loadSettings() {
        configWarnings.clear();
        return new Settings(
                getConfig().getBoolean("features.combine", true),
                getConfig().getBoolean("features.enchant", true),
                configInt("confirmation.timeout-seconds", 6, 2, 30),
                configDouble("confirmation.expensive-threshold", 1000.0, 0.0, 100_000_000.0),
                getConfig().getBoolean("confirmation.combine-always", true),
                getConfig().getBoolean("confirmation.enchant-always", true),
                configDouble("pricing.repair.minimum", 25.0, 0.0, 100_000_000.0),
                configDouble("pricing.repair.full.netherite", 2400.0, 0.0, 100_000_000.0),
                configDouble("pricing.repair.full.diamond", 1200.0, 0.0, 100_000_000.0),
                configDouble("pricing.repair.full.elytra", 2000.0, 0.0, 100_000_000.0),
                configDouble("pricing.repair.full.trident", 1600.0, 0.0, 100_000_000.0),
                configDouble("pricing.repair.full.iron", 350.0, 0.0, 100_000_000.0),
                configDouble("pricing.repair.full.gold", 180.0, 0.0, 100_000_000.0),
                configDouble("pricing.repair.full.stone", 120.0, 0.0, 100_000_000.0),
                configDouble("pricing.repair.full.wood", 80.0, 0.0, 100_000_000.0),
                configDouble("pricing.repair.full.ranged", 450.0, 0.0, 100_000_000.0),
                configDouble("pricing.repair.full.default", 500.0, 0.0, 100_000_000.0),
                configDouble("pricing.combine.base", 125.0, 0.0, 100_000_000.0),
                configDouble("pricing.combine.durability-bonus-percent", 12.0, 0.0, 100.0) / 100.0,
                configDouble("pricing.combine.repair-value-multiplier", 0.35, 0.0, 10.0),
                configDouble("pricing.enchant.base", 100.0, 0.0, 100_000_000.0),
                configDouble("pricing.enchant.rates.rare", 400.0, 0.0, 100_000_000.0),
                configDouble("pricing.enchant.rates.premium", 225.0, 0.0, 100_000_000.0),
                configDouble("pricing.enchant.rates.common", 125.0, 0.0, 100_000_000.0),
                configDouble("pricing.enchant.rates.default", 75.0, 0.0, 100_000_000.0));
    }

    private int configInt(String path, int fallback, int min, int max) {
        int value = getConfig().getInt(path, fallback);
        if (value < min || value > max) {
            configWarnings.add(path + "=" + value + " outside " + min + ".." + max + "; using " + fallback);
            return fallback;
        }
        return value;
    }

    private double configDouble(String path, double fallback, double min, double max) {
        double value = getConfig().getDouble(path, fallback);
        if (!Double.isFinite(value) || value < min || value > max) {
            configWarnings.add(path + "=" + value + " outside " + min + ".." + max + "; using " + fallback);
            return fallback;
        }
        return value;
    }

    private void sendDiagnostics(CommandSender sender) {
        sender.sendMessage(mm("<dark_gray>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</dark_gray>"));
        sender.sendMessage(mm("<gradient:#f59e0b:#fde68a><bold>PlexonBlacksmith Diagnostics</bold></gradient>"));
        diagnostic(sender, "Plugin", getPluginMeta().getVersion());
        diagnostic(sender, "Paper/Bukkit", Bukkit.getVersion());
        diagnostic(sender, "Java", System.getProperty("java.version"));
        diagnostic(sender, "Mode", coreBridge == null ? "STANDALONE" : coreBridge.mode());
        diagnostic(sender, "Core plugin/API", coreBridge == null ? "- / -" : coreBridge.pluginVersion() + " / " + coreBridge.apiVersion());
        diagnostic(sender, "Supported Core API", CoreBridge.SUPPORTED_API_RANGE);
        diagnostic(sender, "Module state", coreBridge == null ? "NOT_INITIALIZED" : coreBridge.registrationState());
        diagnostic(sender, "Core detail", coreBridge == null ? "-" : coreBridge.detail());
        diagnostic(sender, "Economy via Vault", economyName());
        diagnostic(sender, "Theosis contract", "Vault provider authority; no duplicate economy path");
        diagnostic(sender, "Combine", settings.combineEnabled() ? "ENABLED" : "LOCKED");
        diagnostic(sender, "Enchant", settings.enchantEnabled() ? "ENABLED" : "LOCKED");
        diagnostic(sender, "Active GUI sessions", String.valueOf(sessions.size()));
        diagnostic(sender, "Active transactions", String.valueOf(sessions.values().stream().filter(s -> s.transactionActive).count()));
        diagnostic(sender, "Armed confirmations", String.valueOf(sessions.values().stream().filter(s -> s.confirmationKey != null && System.currentTimeMillis() <= s.confirmationExpiresAt).count()));
        diagnostic(sender, "Configuration", configWarnings.isEmpty() ? "VALID" : "WARNINGS=" + configWarnings.size());
        for (String warning : configWarnings) sender.sendMessage(Component.text("  ! " + warning, NamedTextColor.YELLOW));
        diagnostic(sender, "Public API", publicApi == null ? "UNAVAILABLE" : "REGISTERED");
        diagnostic(sender, "Events", "REPAIR / COMBINE / ENCHANT READY");
        diagnostic(sender, "Certification", "SOURCE/CI CANDIDATE; PLEXONCRAFT RUNTIME PENDING");
        sender.sendMessage(mm("<dark_gray>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</dark_gray>"));
    }

    private void diagnostic(CommandSender sender, String label, String value) {
        sender.sendMessage(Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE)));
    }

    private Component mm(String input) {
        return MINI.deserialize(input).decoration(TextDecoration.ITALIC, false);
    }

    private void message(CommandSender sender, String input) {
        sender.sendMessage(mm(input));
    }

    private final class PublicApi implements PlexonBlacksmithAPI {
        @Override
        public boolean canRepair(ItemStack item) {
            return item != null && isRepairable(item.clone());
        }

        @Override
        public RepairQuote quoteRepair(Player player, ItemStack item) {
            ItemStack input = item == null ? null : item.clone();
            if (input == null || !isRepairable(input)) {
                return new RepairQuote(false, input, null, damageOf(input), damageOf(input), 0, 0.0, economyName());
            }
            int previous = damageOf(input);
            ItemStack result = repairResult(input);
            return new RepairQuote(true, input, result, previous, 0, previous, repairPrice(input), economyName());
        }

        @Override
        public boolean canCombine(ItemStack primary, ItemStack donor) {
            return buildCombineResult(primary == null ? null : primary.clone(), donor == null ? null : donor.clone()) != null;
        }

        @Override
        public CombineQuote quoteCombine(Player player, ItemStack primary, ItemStack donor) {
            ItemStack primaryCopy = primary == null ? null : primary.clone();
            ItemStack donorCopy = donor == null ? null : donor.clone();
            CombineResult built = buildCombineResult(primaryCopy, donorCopy);
            if (built == null) {
                return new CombineQuote(false, primaryCopy, donorCopy, null, damageOf(primaryCopy), damageOf(primaryCopy), 0, 0.0, economyName());
            }
            return new CombineQuote(true, primaryCopy, donorCopy, built.result, built.previousDamage, built.newDamage, built.repairAmount, built.price, economyName());
        }

        @Override
        public EnchantQuote quoteEnchant(Player player, ItemStack item, ItemStack book) {
            ItemStack itemCopy = item == null ? null : item.clone();
            ItemStack bookCopy = book == null ? null : book.clone();
            EnchantResult built = buildEnchantResult(itemCopy, bookCopy);
            if (built == null) {
                return new EnchantQuote(false, itemCopy, bookCopy, null, Map.of(), 0.0, economyName());
            }
            return new EnchantQuote(true, itemCopy, bookCopy, built.result, built.appliedEnchantments, built.price, economyName());
        }

        @Override
        public Optional<BlacksmithSessionView> activeSession(UUID playerId) {
            Session session = sessions.get(playerId);
            if (session == null) return Optional.empty();
            return Optional.of(new BlacksmithSessionView(
                    session.playerId,
                    session.sessionId,
                    session.mode.name(),
                    session.repairInput,
                    session.combinePrimary,
                    session.combineDonor,
                    session.enchantTarget,
                    session.enchantBook,
                    session.result,
                    session.price,
                    session.transactionActive,
                    session.confirmationKey != null && System.currentTimeMillis() <= session.confirmationExpiresAt));
        }
    }

    private static final class VaultHook {
        private final Object economy;
        private final Method getBalance;
        private final Method withdraw;
        private final Method deposit;
        private final Method success;
        private final Method getName;

        private VaultHook(Object economy, Method getBalance, Method withdraw, Method deposit, Method success, Method getName) {
            this.economy = economy;
            this.getBalance = getBalance;
            this.withdraw = withdraw;
            this.deposit = deposit;
            this.success = success;
            this.getName = getName;
        }

        static VaultHook tryCreate() {
            try {
                Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
                @SuppressWarnings({"rawtypes", "unchecked"})
                RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) economyClass);
                if (registration == null) return null;
                Object provider = registration.getProvider();
                Method getBalance = economyClass.getMethod("getBalance", OfflinePlayer.class);
                Method withdraw = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                Method deposit = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
                Method getName = economyClass.getMethod("getName");
                Class<?> responseClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
                Method success = responseClass.getMethod("transactionSuccess");
                return new VaultHook(provider, getBalance, withdraw, deposit, success, getName);
            } catch (Throwable ignored) {
                return null;
            }
        }

        boolean has(Player player, double amount) {
            try {
                return ((Number) getBalance.invoke(economy, player)).doubleValue() + 1.0e-9 >= amount;
            } catch (Throwable ignored) {
                return false;
            }
        }

        boolean withdraw(Player player, double amount) {
            try {
                Object response = withdraw.invoke(economy, player, amount);
                return Boolean.TRUE.equals(success.invoke(response));
            } catch (Throwable ignored) {
                return false;
            }
        }

        boolean refund(Player player, double amount) {
            try {
                Object response = deposit.invoke(economy, player, amount);
                return Boolean.TRUE.equals(success.invoke(response));
            } catch (Throwable ignored) {
                return false;
            }
        }

        String name() {
            try {
                Object value = getName.invoke(economy);
                return value == null ? "Vault" : String.valueOf(value);
            } catch (Throwable ignored) {
                return "Vault";
            }
        }
    }
}
