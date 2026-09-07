package dev.plexon.blacksmith;

import dev.plexon.blacksmith.api.BlacksmithSessionView;
import dev.plexon.blacksmith.api.EnchantQuote;
import dev.plexon.blacksmith.api.PlexonBlacksmithAPI;
import dev.plexon.blacksmith.api.RepairQuote;
import dev.plexon.blacksmith.event.PlexonItemEnchantedEvent;
import dev.plexon.blacksmith.event.PlexonItemRepairedEvent;
import dev.plexon.blacksmith.integration.core.CoreBridge;
import dev.plexon.blacksmith.integration.core.CoreBridgeFactory;
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

import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Core-aware runtime Blacksmith inventory for Paper 26.2 with standalone fallback.
 *
 * GUIPlus is intentionally not used for the mutable service inventory because GUIPlus redraws
 * scene items and overwrites runtime input/output ItemStacks. The rest of the server may still
 * use GUIPlus normally.
 */
public class PlexonBlacksmithBridge extends JavaPlugin implements Listener, CommandExecutor {

    private enum Mode { REPAIR, ENCHANT }

    private static final int SIZE = 45;
    private static final int REPAIR_INPUT = 20;
    private static final int REPAIR_PROCESS = 22;
    private static final int REPAIR_OUTPUT = 24;
    private static final int ENCHANT_TARGET = 19;
    private static final int ENCHANT_BOOK = 21;
    private static final int ENCHANT_PROCESS = 23;
    private static final int ENCHANT_OUTPUT = 25;
    private static final int QUOTE_SLOT = 31;
    private static final int PREVIOUS_BUTTON = 37;
    private static final int GUIDE_BUTTON = 40;
    private static final int NEXT_BUTTON = 43;

    private static final class Session {
        final UUID playerId;
        final UUID sessionId = UUID.randomUUID();
        final Inventory inventory;
        Mode mode = Mode.REPAIR;
        ItemStack repairInput;
        ItemStack enchantTarget;
        ItemStack enchantBook;
        ItemStack result;
        double price;
        boolean transactionActive;

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

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final DecimalFormat money = new DecimalFormat("#,##0.00");
    private VaultHook vault;
    private CoreBridge coreBridge;
    private PlexonBlacksmithAPI publicApi;

    @Override
    public void onEnable() {
        coreBridge = CoreBridgeFactory.resolve(this);
        coreBridge.registerStarting();
        try {
            Bukkit.getPluginManager().registerEvents(this, this);
            PluginCommand command = getCommand("blacksmith");
            if (command != null) command.setExecutor(this);

            vault = VaultHook.tryCreate();
            publicApi = new PublicApi();
            Bukkit.getServicesManager().register(PlexonBlacksmithAPI.class, publicApi, this, ServicePriority.Normal);

            if (vault == null) {
                String detail = "Blacksmith services are loaded, but Vault economy is unavailable; paid transactions are blocked";
                getLogger().warning(detail + ".");
                coreBridge.markDegraded(detail);
            } else {
                coreBridge.markReady("Repair, enchanting, GUI sessions, public API/events and " + vault.name() + " economy are operational");
            }
            getLogger().info("PlexonBlacksmith " + getPluginMeta().getVersion() + " enabled in " + coreBridge.mode() + " mode. Runtime inventory remains fully plugin-owned.");
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
                sender.sendMessage("§6Blacksmith §8» §cYou do not have permission to view diagnostics.");
                return true;
            }
            sendDiagnostics(sender);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("plexon.blacksmith.admin")) {
                sender.sendMessage("§6Blacksmith §8» §cYou do not have permission to reload Blacksmith.");
                return true;
            }
            reloadServices(sender);
            return true;
        }
        if (args.length > 0) {
            sender.sendMessage("§6Blacksmith §8» §7Usage: /blacksmith [diagnostics|reload]");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Blacksmith can only be opened by a player. Use /blacksmith diagnostics from console.");
            return true;
        }
        if (!player.hasPermission("plexon.blacksmith.use")) {
            player.sendMessage("§6Blacksmith §8» §cYou do not have permission to use this workshop.");
            return true;
        }
        openBlacksmith(player);
        return true;
    }

    private void openBlacksmith(Player player) {
        Session old = sessions.remove(player.getUniqueId());
        if (old != null) returnStoredInputs(player, old);

        Inventory inventory = Bukkit.createInventory(null, SIZE, "§6BLACKSMITH §8• §fFORGE");
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

        // Player inventory: preserve ordinary movement, but block cross-inventory collection paths.
        if (raw >= top.getSize()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                shiftRoute(player, session, event);
            } else if (event.getClick() == ClickType.DOUBLE_CLICK || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
            return;
        }

        // Everything in the top inventory is controlled by this plugin.
        event.setCancelled(true);

        if (raw == PREVIOUS_BUTTON) {
            if (session.mode == Mode.ENCHANT) {
                session.mode = Mode.REPAIR;
                render(session);
            }
            return;
        }
        if (raw == NEXT_BUTTON) {
            if (session.mode == Mode.REPAIR) {
                session.mode = Mode.ENCHANT;
                render(session);
            }
            return;
        }
        if (raw == GUIDE_BUTTON) {
            player.sendMessage("§6Blacksmith §8» §7Repair: place a damaged item, then click the result. §dEnchant: place equipment + enchanted book, then click the result. §7Unused inputs are returned when you close the GUI.");
            return;
        }

        if (session.mode == Mode.REPAIR) {
            if (raw == REPAIR_INPUT) {
                handleInputClick(player, session, event, REPAIR_INPUT);
            } else if (raw == REPAIR_OUTPUT) {
                completeRepair(player, session, event);
            }
        } else {
            if (raw == ENCHANT_TARGET) {
                handleInputClick(player, session, event, ENCHANT_TARGET);
            } else if (raw == ENCHANT_BOOK) {
                handleInputClick(player, session, event, ENCHANT_BOOK);
            } else if (raw == ENCHANT_OUTPUT) {
                completeEnchant(player, session, event);
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

        // Never allow Bukkit to distribute items over decorations/output slots.
        event.setCancelled(true);
        if (topSlots != 1 || event.getRawSlots().size() != 1) return;
        if (!isInputSlot(session.mode, target)) return;

        ItemStack cursor = event.getOldCursor();
        if (isEmpty(cursor)) return;
        if (!placeFromCursor(player, session, target, cursor)) return;
        event.setCursor(decremented(cursor));
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

        // Empty cursor: take cached input back.
        if (isEmpty(cursor)) {
            if (stored == null) return;
            player.setItemOnCursor(stored.clone());
            setCached(session, slot, null);
            render(session);
            return;
        }

        // Occupied slot: do not overwrite custom items implicitly.
        if (stored != null) {
            player.sendMessage("§6Blacksmith §8» §7Take the existing input out before placing another item.");
            return;
        }

        if (!placeFromCursor(player, session, slot, cursor)) return;
        player.setItemOnCursor(decremented(cursor));
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
        if (session.mode == Mode.REPAIR) {
            target = REPAIR_INPUT;
        } else if (clicked.getType() == Material.ENCHANTED_BOOK) {
            target = ENCHANT_BOOK;
        } else {
            target = ENCHANT_TARGET;
        }

        if (!slotEmpty(session, target)) {
            player.sendMessage("§6Blacksmith §8» §7That input is already occupied.");
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
        render(session);
    }

    private boolean validateForSlot(Player player, Mode mode, int slot, ItemStack stack) {
        if (isEmpty(stack)) return false;

        if (mode == Mode.REPAIR && slot == REPAIR_INPUT) {
            if (!isRepairable(stack)) {
                player.sendMessage("§6Blacksmith §8» §cOnly damaged, repairable items can go in Repair Input.");
                return false;
            }
            return true;
        }

        if (mode == Mode.ENCHANT && slot == ENCHANT_BOOK) {
            if (stack.getType() != Material.ENCHANTED_BOOK) {
                player.sendMessage("§6Blacksmith §8» §cThe Book input only accepts enchanted books.");
                return false;
            }
            return true;
        }

        if (mode == Mode.ENCHANT && slot == ENCHANT_TARGET) {
            if (stack.getType() == Material.ENCHANTED_BOOK) {
                player.sendMessage("§6Blacksmith §8» §cPut the enchanted book in the Book input.");
                return false;
            }
            return true;
        }
        return false;
    }

    private boolean isInputSlot(Mode mode, int slot) {
        return mode == Mode.REPAIR ? slot == REPAIR_INPUT : slot == ENCHANT_TARGET || slot == ENCHANT_BOOK;
    }

    private ItemStack getCached(Session session, int slot) {
        if (slot == REPAIR_INPUT) return session.repairInput;
        if (slot == ENCHANT_TARGET) return session.enchantTarget;
        if (slot == ENCHANT_BOOK) return session.enchantBook;
        return null;
    }

    private void setCached(Session session, int slot, ItemStack item) {
        if (slot == REPAIR_INPUT) session.repairInput = item;
        else if (slot == ENCHANT_TARGET) session.enchantTarget = item;
        else if (slot == ENCHANT_BOOK) session.enchantBook = item;
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

        if (session.mode == Mode.REPAIR) {
            inv.setItem(REPAIR_INPUT, session.repairInput == null
                ? ghost(Material.HOPPER, "§6Repair Input", "§7Place a damaged item here.")
                : session.repairInput.clone());
            inv.setItem(REPAIR_PROCESS, ghost(Material.ANVIL, "§fRepair", "§7Missing durability determines the price."));

            if (session.repairInput != null && isRepairable(session.repairInput)) {
                ItemStack result = repairResult(session.repairInput);
                double price = repairPrice(session.repairInput);
                session.result = result;
                session.price = price;
                inv.setItem(REPAIR_OUTPUT, preview(result,
                    "§6Repair cost: §a$" + money.format(price),
                    "§eClick to confirm repair"));
                inv.setItem(QUOTE_SLOT, ghost(Material.GOLD_NUGGET,
                    "§6Repair Quote §8• §a$" + money.format(price),
                    "§7Vault is charged only when you take the result."));
            } else {
                inv.setItem(REPAIR_OUTPUT, ghost(Material.LIGHT_GRAY_DYE, "§8Repair Result", "§7The repaired item will appear here."));
                inv.setItem(QUOTE_SLOT, ghost(Material.GRAY_DYE, "§8Waiting for item", "§7Place a damaged item to calculate the price."));
            }
        } else {
            inv.setItem(ENCHANT_TARGET, session.enchantTarget == null
                ? ghost(Material.HOPPER, "§dItem", "§7Place the item to enchant here.")
                : session.enchantTarget.clone());
            inv.setItem(ENCHANT_BOOK, session.enchantBook == null
                ? ghost(Material.BOOK, "§dEnchanted Book", "§7Place an enchanted book here.")
                : session.enchantBook.clone());
            inv.setItem(ENCHANT_PROCESS, ghost(Material.ENCHANTING_TABLE, "§fCombine", "§7Compatible upgrades are applied safely."));

            EnchantResult built = buildEnchantResult(session.enchantTarget, session.enchantBook);
            if (built != null) {
                session.result = built.result;
                session.price = built.price;
                inv.setItem(ENCHANT_OUTPUT, preview(built.result,
                    "§dEnchant cost: §a$" + money.format(built.price),
                    "§eClick to apply " + built.applied + (built.applied == 1 ? " enchantment" : " enchantments")));
                inv.setItem(QUOTE_SLOT, ghost(Material.GOLD_NUGGET,
                    "§dEnchant Quote §8• §a$" + money.format(built.price),
                    "§7Book is consumed only after successful payment."));
            } else {
                inv.setItem(ENCHANT_OUTPUT, ghost(Material.LIGHT_GRAY_DYE, "§8Enchant Result", "§7A compatible result will appear here."));
                inv.setItem(QUOTE_SLOT, ghost(Material.GRAY_DYE, "§8Waiting for valid combination", "§7Place an item and compatible enchanted book."));
            }
        }
    }

    private void drawFrame(Inventory inv, Mode mode) {
        Material[] top = {
            Material.ORANGE_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE,
            Material.BLACK_STAINED_GLASS_PANE, mode == Mode.REPAIR ? Material.ANVIL : Material.ENCHANTING_TABLE,
            Material.BLACK_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE,
            Material.ORANGE_STAINED_GLASS_PANE
        };
        for (int i = 0; i < 9; i++) {
            if (i == 4) {
                if (mode == Mode.REPAIR) {
                    inv.setItem(i, ghost(top[i], "§6§lRepair", "§8PAGE 1 / 2", "§7Place a damaged item directly into the input."));
                } else {
                    inv.setItem(i, ghost(top[i], "§d§lEnchant", "§8PAGE 2 / 2", "§7Place the item and enchanted book directly below."));
                }
            } else {
                inv.setItem(i, pane(top[i]));
            }
        }

        for (int slot = 9; slot < 36; slot++) {
            if (isServiceSlot(mode, slot)) continue;
            Material pane = (slot == 9 || slot == 17 || slot == 18 || slot == 26 || slot == 27 || slot == 35)
                ? Material.GRAY_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE;
            inv.setItem(slot, pane(pane));
        }

        for (int slot = 36; slot < 45; slot++) {
            if (slot == PREVIOUS_BUTTON || slot == GUIDE_BUTTON || slot == NEXT_BUTTON) continue;
            Material pane = (slot == 36 || slot == 44) ? Material.GRAY_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE;
            inv.setItem(slot, pane(pane));
        }

        if (mode == Mode.REPAIR) {
            inv.setItem(PREVIOUS_BUTTON, ghost(Material.GRAY_DYE, "§8Previous", "§7You are on the first page."));
            inv.setItem(NEXT_BUTTON, ghost(Material.ARROW, "§d§lEnchant →", "§7Open the Enchant page."));
        } else {
            inv.setItem(PREVIOUS_BUTTON, ghost(Material.ARROW, "§6§l← Repair", "§7Return to the Repair page."));
            inv.setItem(NEXT_BUTTON, ghost(Material.GRAY_DYE, "§8Next", "§7You are on the last page."));
        }
        inv.setItem(GUIDE_BUTTON, ghost(Material.WRITABLE_BOOK, "§f§lGuide",
            "§6Repair §7• damaged item → result",
            "§dEnchant §7• item + book → result",
            "",
            "§7Inputs stay cached while switching pages.",
            "§7Closing returns every unused input."));
    }

    private boolean isServiceSlot(Mode mode, int slot) {
        if (slot == QUOTE_SLOT) return true;
        if (mode == Mode.REPAIR) return slot == REPAIR_INPUT || slot == REPAIR_PROCESS || slot == REPAIR_OUTPUT;
        return slot == ENCHANT_TARGET || slot == ENCHANT_BOOK || slot == ENCHANT_PROCESS || slot == ENCHANT_OUTPUT;
    }

    private void completeRepair(Player player, Session session, InventoryClickEvent event) {
        if (!Bukkit.isPrimaryThread()) {
            getLogger().severe("Rejected asynchronous repair transaction for " + player.getName());
            return;
        }
        if (session.transactionActive) return;
        if (session.repairInput == null || !isRepairable(session.repairInput)) {
            player.sendMessage("§6Blacksmith §8» §7Place a damaged item in Repair Input first.");
            return;
        }
        if (!isEmpty(event.getCursor())) {
            player.sendMessage("§6Blacksmith §8» §7Clear your cursor before taking the result.");
            return;
        }

        ItemStack original = session.repairInput.clone();
        ItemStack result = repairResult(original);
        double price = repairPrice(original);
        int previousDamage = damageOf(original);
        UUID transactionId = UUID.randomUUID();
        session.transactionActive = true;
        boolean charged = false;
        boolean committed = false;
        try {
            if (!charge(player, price)) return;
            charged = true;

            // Cursor commit happens before the cached input is consumed. If it throws, rollback is lossless.
            player.setItemOnCursor(result.clone());
            session.repairInput = null;
            committed = true;
        } catch (Throwable failure) {
            if (charged && !committed) refundAfterFailure(player, price, failure);
            getLogger().log(Level.SEVERE, "Repair transaction " + transactionId + " failed before commit.", failure);
            player.sendMessage("§6Blacksmith §8» §cRepair failed safely. Your input was preserved" + (charged ? " and payment was refunded." : "."));
            return;
        } finally {
            session.transactionActive = false;
        }

        renderSafely(session);
        fireRepairEvent(player, transactionId, result, previousDamage, price);
        player.sendMessage("§6Blacksmith §8» §aRepaired for §f$" + money.format(price) + "§a.");
    }

    private void completeEnchant(Player player, Session session, InventoryClickEvent event) {
        if (!Bukkit.isPrimaryThread()) {
            getLogger().severe("Rejected asynchronous enchant transaction for " + player.getName());
            return;
        }
        if (session.transactionActive) return;
        EnchantResult built = buildEnchantResult(session.enchantTarget, session.enchantBook);
        if (built == null) {
            player.sendMessage("§6Blacksmith §8» §7Place an item and a compatible enchanted book first.");
            return;
        }
        if (!isEmpty(event.getCursor())) {
            player.sendMessage("§6Blacksmith §8» §7Clear your cursor before taking the result.");
            return;
        }

        ItemStack originalTarget = session.enchantTarget.clone();
        ItemStack originalBook = session.enchantBook.clone();
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
            player.sendMessage("§6Blacksmith §8» §cEnchant failed safely. Your inputs were preserved" + (charged ? " and payment was refunded." : "."));
            return;
        } finally {
            session.transactionActive = false;
        }

        renderSafely(session);
        fireEnchantEvent(player, transactionId, built, originalBook);
        player.sendMessage("§6Blacksmith §8» §aEnchantments applied for §f$" + money.format(built.price) + "§a.");
    }

    private boolean charge(Player player, double price) {
        if (vault == null) vault = VaultHook.tryCreate();
        if (vault == null) {
            if (coreBridge != null) coreBridge.markDegraded("Vault economy is unavailable; paid transactions are blocked");
            player.sendMessage("§6Blacksmith §8» §cVault economy is unavailable.");
            return false;
        }
        if (!vault.has(player, price)) {
            player.sendMessage("§6Blacksmith §8» §cYou need §f$" + money.format(price) + "§c.");
            return false;
        }
        if (!vault.withdraw(player, price)) {
            player.sendMessage("§6Blacksmith §8» §cPayment failed. Nothing was consumed.");
            return false;
        }
        return true;
    }

    private void refundAfterFailure(Player player, double price, Throwable cause) {
        if (vault != null && vault.refund(player, price)) return;
        getLogger().log(Level.SEVERE, "CRITICAL: Vault refund failed for " + player.getUniqueId() + " amount $" + money.format(price), cause);
        player.sendMessage("§6Blacksmith §8» §cA payment refund failed. Contact an administrator with transaction time immediately.");
    }

    private boolean isRepairable(ItemStack stack) {
        if (isEmpty(stack)) return false;
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return false;
        if (meta.isUnbreakable()) return false;
        int max = maxDamage(stack, damageable);
        return max > 0 && damageable.getDamage() > 0;
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
        return Math.max(25.0, roundMoney(fullRepairPrice(input.getType()) * fraction));
    }

    private double fullRepairPrice(Material material) {
        String n = material.name();
        if (n.contains("NETHERITE") || n.equals("MACE")) return 2400.0;
        if (n.contains("DIAMOND")) return 1200.0;
        if (n.equals("ELYTRA")) return 2000.0;
        if (n.equals("TRIDENT")) return 1600.0;
        if (n.contains("IRON") || n.contains("CHAINMAIL")) return 350.0;
        if (n.contains("GOLD")) return 180.0;
        if (n.contains("STONE")) return 120.0;
        if (n.contains("WOODEN")) return 80.0;
        if (n.equals("BOW") || n.equals("CROSSBOW") || n.equals("FISHING_ROD") || n.equals("SHIELD")) return 450.0;
        return 500.0;
    }

    private EnchantResult buildEnchantResult(ItemStack target, ItemStack book) {
        if (isEmpty(target) || isEmpty(book) || book.getType() != Material.ENCHANTED_BOOK) return null;
        ItemMeta bookMeta = book.getItemMeta();
        if (!(bookMeta instanceof EnchantmentStorageMeta storage)) return null;
        if (storage.getStoredEnchants().isEmpty()) return null;

        ItemStack result = target.clone();
        result.setAmount(1);
        ItemMeta targetMeta = result.getItemMeta();
        if (targetMeta == null) return null;

        int applied = 0;
        double price = 100.0;
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
        if (key.contains("mending") || key.contains("swift_sneak") || key.contains("soul_speed") || key.contains("wind_burst")) return 400.0;
        if (key.contains("fortune") || key.contains("silk_touch") || key.contains("looting") || key.contains("protection") || key.contains("sharpness") || key.contains("power")) return 225.0;
        if (key.contains("unbreaking") || key.contains("efficiency") || key.contains("respiration") || key.contains("feather_falling")) return 125.0;
        return 75.0;
    }

    private ItemStack preview(ItemStack clean, String priceLine, String actionLine) {
        ItemStack preview = clean.clone();
        ItemMeta meta = preview.getItemMeta();
        if (meta == null) return preview;
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add("§8BLACKSMITH SERVICE");
        lore.add(priceLine);
        lore.add(actionLine);
        meta.setLore(lore);
        preview.setItemMeta(meta);
        return preview;
    }

    private ItemStack pane(Material material) {
        return ghost(material, " ");
    }

    private ItemStack ghost(Material material, String name, String... loreLines) {
        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> lore = new ArrayList<>();
            for (String line : loreLines) lore.add(line);
            meta.setLore(lore);
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
        if (session.enchantTarget != null) giveSafely(player, session.enchantTarget);
        if (session.enchantBook != null) giveSafely(player, session.enchantBook);
        session.repairInput = null;
        session.enchantTarget = null;
        session.enchantBook = null;
        session.result = null;
        session.price = 0.0;
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
            getLogger().log(Level.WARNING, "Transaction committed but Blacksmith GUI refresh failed for session " + session.sessionId, error);
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
            } else if (session.repairInput != null || session.enchantTarget != null || session.enchantBook != null) {
                getLogger().severe("Session " + session.sessionId + " became ownerless during shutdown. PlayerQuit handling should have returned these inputs earlier.");
            }
        }
    }

    private void reloadServices(CommandSender sender) {
        closeAllSessions();
        vault = VaultHook.tryCreate();
        if (vault == null) {
            if (coreBridge != null) coreBridge.markDegraded("Reloaded; Vault economy is unavailable; paid transactions are blocked");
            sender.sendMessage("§6Blacksmith §8» §eReloaded in degraded mode: Vault economy is unavailable.");
        } else {
            if (coreBridge != null) coreBridge.markReady("Reloaded successfully; economy provider " + vault.name() + " is operational");
            sender.sendMessage("§6Blacksmith §8» §aReloaded successfully. Economy: §f" + vault.name());
        }
    }

    private void sendDiagnostics(CommandSender sender) {
        sender.sendMessage("§8§m----------------------------------------");
        sender.sendMessage("§6§lPlexonBlacksmith Diagnostics");
        sender.sendMessage("§7Plugin: §f" + getPluginMeta().getVersion());
        sender.sendMessage("§7Paper/Bukkit: §f" + Bukkit.getVersion());
        sender.sendMessage("§7Java: §f" + System.getProperty("java.version"));
        sender.sendMessage("§7Mode: §f" + (coreBridge == null ? "STANDALONE" : coreBridge.mode()));
        sender.sendMessage("§7Core plugin/API: §f" + (coreBridge == null ? "- / -" : coreBridge.pluginVersion() + " / " + coreBridge.apiVersion()));
        sender.sendMessage("§7Supported Core API: §f" + CoreBridge.SUPPORTED_API_RANGE);
        sender.sendMessage("§7Module state: §f" + (coreBridge == null ? "NOT_INITIALIZED" : coreBridge.registrationState()));
        sender.sendMessage("§7Core detail: §f" + (coreBridge == null ? "-" : coreBridge.detail()));
        sender.sendMessage("§7Economy: §f" + economyName());
        sender.sendMessage("§7Active GUI sessions: §f" + sessions.size());
        sender.sendMessage("§7Active transactions: §f" + sessions.values().stream().filter(s -> s.transactionActive).count());
        sender.sendMessage("§7Public API: §f" + (publicApi == null ? "UNAVAILABLE" : "REGISTERED"));
        sender.sendMessage("§7Repair event: §fREADY");
        sender.sendMessage("§7Enchant event: §fREADY");
        sender.sendMessage("§8§m----------------------------------------");
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
                    session.enchantTarget,
                    session.enchantBook,
                    session.result,
                    session.price,
                    session.transactionActive));
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
