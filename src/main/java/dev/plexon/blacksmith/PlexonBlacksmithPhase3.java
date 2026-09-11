package dev.plexon.blacksmith;

import dev.plexon.blacksmith.api.BlacksmithSessionView;
import dev.plexon.blacksmith.api.CombineQuote;
import dev.plexon.blacksmith.api.EnchantQuote;
import dev.plexon.blacksmith.api.PlexonBlacksmithAPI;
import dev.plexon.blacksmith.api.RepairQuote;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Phase 3 player-product layer. The Phase 2 superclass remains the sole workstation transaction,
 * quote, item-custody and rollback authority. This class only owns routing and presentation.
 */
public class PlexonBlacksmithPhase3 extends PlexonBlacksmithBridge {
    private enum TargetMode { REPAIR, COMBINE, ENCHANT }

    private record HomeView(Inventory inventory, boolean guide) {}

    private record BalanceSnapshot(boolean available, double balance, String provider) {
        static BalanceSnapshot unavailable(String provider) {
            return new BalanceSnapshot(false, 0.0, provider == null ? "Vault" : provider);
        }
    }

    private record OperationStatus(
            String title,
            String detail,
            double price,
            BalanceSnapshot balance,
            boolean ready,
            boolean featureEnabled) {}

    private record PresentationToken(UUID sessionId, String page, int inputsHash,
                                     long priceBits, boolean confirmationArmed) {}

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int SIZE = 54;

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

    private static final int LEGACY_PREVIOUS = 46;
    private static final int LEGACY_NEXT = 52;

    private static final int MODE_REPAIR = 45;
    private static final int MODE_COMBINE = 46;
    private static final int MODE_ENCHANT = 47;
    private static final int BACK_HOME = 48;
    private static final int PRIMARY_ACTION = 49;
    private static final int GUIDE = 50;
    private static final int STATUS = 51;
    private static final int CLOSE = 52;
    private static final int RESERVED = 53;

    private final Map<UUID, HomeView> homeViews = new HashMap<>();
    private final Map<UUID, Inventory> workstationViews = new HashMap<>();
    private final Map<UUID, PresentationToken> renderedTokens = new HashMap<>();
    private final Map<UUID, OperationStatus> transientStatus = new HashMap<>();
    private final Map<UUID, ItemStack[]> guideSnapshots = new HashMap<>();
    private final Set<UUID> guideOverlay = new HashSet<>();
    private final Set<UUID> returnHomeAfterClose = new HashSet<>();
    private final Set<UUID> submitting = new HashSet<>();
    private final DecimalFormat money = new DecimalFormat("#,##0.00");

    @Override
    public void onEnable() {
        super.onEnable();
        if (isEnabled()) {
            getLogger().info("Phase 3 player UX active: Blacksmith Home, explicit actions, safe previews and authoritative quote projection.");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) return super.onCommand(sender, command, label, args);
        if (!(sender instanceof Player player)) return super.onCommand(sender, command, label, args);
        if (!player.hasPermission("plexon.blacksmith.use")) return super.onCommand(sender, command, label, args);
        openHome(player);
        return true;
    }

    private void openHome(Player player) {
        UUID playerId = player.getUniqueId();
        guideOverlay.remove(playerId);
        guideSnapshots.remove(playerId);
        returnHomeAfterClose.remove(playerId);
        renderedTokens.remove(playerId);
        transientStatus.remove(playerId);

        Inventory inventory = Bukkit.createInventory(null, SIZE,
                mm("<gradient:#f59e0b:#fde68a><bold>BLACKSMITH</bold></gradient> <dark_gray>•</dark_gray> <white>HOME</white>"));
        fillFrame(inventory);
        inventory.setItem(4, ghost(Material.ANVIL,
                "<gradient:#f59e0b:#fde68a><bold>PLEXON BLACKSMITH</bold></gradient>",
                "<gray>Choose a workstation service.</gray>",
                "<dark_gray>No item or currency is consumed from this screen.</dark_gray>"));
        inventory.setItem(HOME_REPAIR, ghost(Material.ANVIL,
                "<gold><bold>REPAIR</bold></gold>",
                "<gray>Restore durability while preserving the item.</gray>",
                "", "<white>Click to open Repair.</white>"));
        inventory.setItem(HOME_COMBINE, getConfig().getBoolean("features.combine", true)
                ? ghost(Material.SMITHING_TABLE,
                    "<yellow><bold>COMBINE</bold></yellow>",
                    "<gray>Merge matching compatible damageable items.</gray>",
                    "<dark_gray>The primary item's identity remains authoritative.</dark_gray>",
                    "", "<white>Click to open Combine.</white>")
                : ghost(Material.BARRIER,
                    "<red><bold>COMBINE DISABLED</bold></red>",
                    "<gray>This service is disabled by the server configuration.</gray>"));
        inventory.setItem(HOME_ENCHANT, getConfig().getBoolean("features.enchant", true)
                ? ghost(Material.ENCHANTING_TABLE,
                    "<light_purple><bold>ENCHANT</bold></light_purple>",
                    "<gray>Apply compatible upgrades from an enchanted book.</gray>",
                    "<dark_gray>Unsupported or conflicting upgrades are never advertised as valid.</dark_gray>",
                    "", "<white>Click to open Enchant.</white>")
                : ghost(Material.BARRIER,
                    "<red><bold>ENCHANT DISABLED</bold></red>",
                    "<gray>This service is disabled by the server configuration.</gray>"));
        inventory.setItem(HOME_GUIDE, ghost(Material.WRITABLE_BOOK,
                "<white><bold>GUIDE</bold></white>",
                "<gray>What each service accepts, costs and changes.</gray>",
                "<gray>Also explains item-return safety.</gray>",
                "", "<white>Click to read.</white>"));
        inventory.setItem(CLOSE, ghost(Material.BARRIER,
                "<red><bold>CLOSE</bold></red>", "<gray>Leave the Blacksmith.</gray>"));

        homeViews.put(playerId, new HomeView(inventory, false));
        workstationViews.remove(playerId);
        player.openInventory(inventory);
    }

    private void openHomeGuide(Player player, Inventory inventory) {
        fillFrame(inventory);
        inventory.setItem(4, ghost(Material.WRITABLE_BOOK,
                "<gradient:#f59e0b:#fde68a><bold>BLACKSMITH GUIDE</bold></gradient>",
                "<gray>Only supported workstation behavior is shown here.</gray>"));
        inventory.setItem(19, ghost(Material.ANVIL, "<gold><bold>REPAIR</bold></gold>",
                "<gray>Use one damaged, repairable item.</gray>",
                "<gray>The result is the same exact item with permitted durability restored.</gray>"));
        inventory.setItem(21, ghost(Material.SMITHING_TABLE, "<yellow><bold>COMBINE</bold></yellow>",
                "<gray>Use a primary item plus a matching compatible donor.</gray>",
                "<gray>Different types or custom identities cannot be fused.</gray>"));
        inventory.setItem(23, ghost(Material.ENCHANTING_TABLE, "<light_purple><bold>ENCHANT</bold></light_purple>",
                "<gray>Use a target item plus an enchanted book.</gray>",
                "<gray>Only compatible higher-level upgrades are applied.</gray>"));
        inventory.setItem(31, ghost(Material.GOLD_NUGGET, "<gold><bold>COSTS</bold></gold>",
                "<gray>Every price comes from the existing Blacksmith quote authority.</gray>",
                "<gray>Opening, browsing and previewing never withdraw money.</gray>"));
        inventory.setItem(33, ghost(Material.CHEST, "<aqua><bold>ITEM SAFETY</bold></aqua>",
                "<gray>Inputs stay in session custody while the workstation is open.</gray>",
                "<gray>Closing without completing an operation returns unused inputs.</gray>"));
        inventory.setItem(BACK_HOME, ghost(Material.ARROW, "<white><bold>BACK</bold></white>",
                "<gray>Return to Blacksmith Home.</gray>"));
        inventory.setItem(CLOSE, ghost(Material.BARRIER, "<red><bold>CLOSE</bold></red>",
                "<gray>Leave the Blacksmith.</gray>"));
        homeViews.put(player.getUniqueId(), new HomeView(inventory, true));
    }

    private void openMode(Player player, TargetMode target) {
        UUID playerId = player.getUniqueId();
        homeViews.remove(playerId);
        transientStatus.remove(playerId);
        PluginCommand command = getCommand("blacksmith");
        if (command == null) {
            player.sendActionBar(mm("<red>Blacksmith command is unavailable.</red>"));
            return;
        }
        super.onCommand(player, command, "blacksmith", new String[0]);
        workstationViews.put(playerId, player.getOpenInventory().getTopInventory());
        routeMode(player, target);
        decorateWorkstation(player);
    }

    private void routeMode(Player player, TargetMode target) {
        PlexonBlacksmithAPI api = api();
        if (api == null) return;
        for (int guard = 0; guard < 3; guard++) {
            BlacksmithSessionView view = api.activeSession(player.getUniqueId()).orElse(null);
            if (view == null) return;
            TargetMode current = parseMode(view.page());
            if (current == target) return;
            int slot = current.ordinal() < target.ordinal() ? LEGACY_NEXT : LEGACY_PREVIOUS;
            invokeLegacyClick(player, slot);
        }
    }

    @Override
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();
        Inventory top = event.getView().getTopInventory();
        HomeView home = homeViews.get(playerId);
        if (home != null && top == home.inventory()) {
            event.setCancelled(true);
            handleHomeClick(player, home, event.getRawSlot());
            return;
        }

        Inventory workstation = workstationViews.get(playerId);
        if (workstation == null || top != workstation) {
            super.onClick(event);
            return;
        }

        if (guideOverlay.contains(playerId)) {
            event.setCancelled(true);
            handleGuideOverlayClick(player, event.getRawSlot());
            return;
        }

        BlacksmithSessionView view = currentView(player);
        if (view == null) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }

        int raw = event.getRawSlot();
        if (raw >= SIZE) {
            transientStatus.remove(playerId);
            super.onClick(event);
            decorateWorkstation(player);
            return;
        }

        event.setCancelled(true);
        if (raw < 0) return;
        if (raw == CLOSE) {
            player.closeInventory();
            return;
        }
        if (raw == BACK_HOME) {
            returnHomeAfterClose.add(playerId);
            player.closeInventory();
            return;
        }
        if (raw == GUIDE) {
            guideSnapshots.put(playerId, copyContents(workstation.getContents()));
            guideOverlay.add(playerId);
            renderGuideOverlay(player, workstation);
            return;
        }
        if (raw == MODE_REPAIR || raw == MODE_COMBINE || raw == MODE_ENCHANT) {
            TargetMode target = raw == MODE_REPAIR ? TargetMode.REPAIR
                    : raw == MODE_COMBINE ? TargetMode.COMBINE : TargetMode.ENCHANT;
            transientStatus.remove(playerId);
            routeMode(player, target);
            decorateWorkstation(player);
            return;
        }
        if (raw == PRIMARY_ACTION) {
            submitPrimary(player, view);
            return;
        }
        if (raw == outputSlot(view.page())) {
            player.sendActionBar(mm("<gray>That is a result preview. Use the highlighted action below.</gray>"));
            return;
        }
        if (raw == RESERVED || raw == STATUS) return;

        if (isRejectedRepairInput(view, raw, event.getCursor())) {
            transientStatus.put(playerId, rejectedRepairStatus(event.getCursor()));
            decorateWorkstation(player);
            return;
        }

        transientStatus.remove(playerId);
        super.onClick(event);
        decorateWorkstation(player);
    }

    private void handleHomeClick(Player player, HomeView home, int raw) {
        if (raw < 0) return;
        if (home.guide()) {
            if (raw == BACK_HOME) openHome(player);
            else if (raw == CLOSE) player.closeInventory();
            return;
        }
        if (raw == HOME_REPAIR) openMode(player, TargetMode.REPAIR);
        else if (raw == HOME_COMBINE && getConfig().getBoolean("features.combine", true)) openMode(player, TargetMode.COMBINE);
        else if (raw == HOME_ENCHANT && getConfig().getBoolean("features.enchant", true)) openMode(player, TargetMode.ENCHANT);
        else if (raw == HOME_GUIDE) openHomeGuide(player, home.inventory());
        else if (raw == CLOSE) player.closeInventory();
    }

    private void handleGuideOverlayClick(Player player, int raw) {
        UUID playerId = player.getUniqueId();
        Inventory workstation = workstationViews.get(playerId);
        if (raw == BACK_HOME) {
            guideOverlay.remove(playerId);
            restoreGuideSnapshot(playerId, workstation);
            decorateWorkstation(player);
        } else if (raw == CLOSE) {
            player.closeInventory();
        } else if (raw == 19 || raw == 21 || raw == 23) {
            guideOverlay.remove(playerId);
            restoreGuideSnapshot(playerId, workstation);
            TargetMode target = raw == 19 ? TargetMode.REPAIR : raw == 21 ? TargetMode.COMBINE : TargetMode.ENCHANT;
            routeMode(player, target);
            decorateWorkstation(player);
        }
    }

    @Override
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();
        HomeView home = homeViews.get(playerId);
        if (home != null && event.getView().getTopInventory() == home.inventory()) {
            event.setCancelled(true);
            return;
        }
        Inventory workstation = workstationViews.get(playerId);
        if (workstation != null && event.getView().getTopInventory() == workstation) {
            if (guideOverlay.contains(playerId)) {
                event.setCancelled(true);
                return;
            }
            transientStatus.remove(playerId);
            super.onDrag(event);
            decorateWorkstation(player);
            return;
        }
        super.onDrag(event);
    }

    @Override
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();
        HomeView home = homeViews.get(playerId);
        if (home != null && event.getView().getTopInventory() == home.inventory()) {
            homeViews.remove(playerId);
            return;
        }

        Inventory workstation = workstationViews.get(playerId);
        boolean owned = workstation != null && event.getView().getTopInventory() == workstation;
        super.onClose(event);
        if (!owned) return;

        workstationViews.remove(playerId);
        guideOverlay.remove(playerId);
        guideSnapshots.remove(playerId);
        renderedTokens.remove(playerId);
        transientStatus.remove(playerId);
        submitting.remove(playerId);
        if (returnHomeAfterClose.remove(playerId) && player.isOnline()) openHome(player);
    }

    @Override
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        homeViews.remove(playerId);
        workstationViews.remove(playerId);
        guideOverlay.remove(playerId);
        guideSnapshots.remove(playerId);
        returnHomeAfterClose.remove(playerId);
        renderedTokens.remove(playerId);
        transientStatus.remove(playerId);
        submitting.remove(playerId);
        super.onQuit(event);
    }

    private void submitPrimary(Player player, BlacksmithSessionView renderedView) {
        UUID playerId = player.getUniqueId();
        OperationStatus status = operationStatus(player, renderedView);
        if (!status.ready()) {
            player.sendActionBar(mm("<yellow>" + safe(status.detail()) + "</yellow>"));
            decorateWorkstation(player);
            return;
        }

        PresentationToken expected = renderedTokens.get(playerId);
        BlacksmithSessionView current = currentView(player);
        if (current == null || expected == null || !expected.equals(token(current))) {
            player.sendActionBar(mm("<yellow>The items changed while the menu was open. The quote was refreshed.</yellow>"));
            decorateWorkstation(player);
            return;
        }
        if (!submitting.add(playerId)) return;
        try {
            invokeLegacyClick(player, outputSlot(current.page()));
        } finally {
            submitting.remove(playerId);
        }
        if (currentView(player) != null) decorateWorkstation(player);
    }

    private void invokeLegacyClick(Player player, int rawSlot) {
        InventoryView view = player.getOpenInventory();
        InventoryClickEvent synthetic = new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, rawSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        super.onClick(synthetic);
    }

    private void decorateWorkstation(Player player) {
        UUID playerId = player.getUniqueId();
        Inventory inventory = workstationViews.get(playerId);
        if (inventory == null || player.getOpenInventory().getTopInventory() != inventory) return;
        if (guideOverlay.contains(playerId)) {
            renderGuideOverlay(player, inventory);
            return;
        }

        BlacksmithSessionView view = currentView(player);
        if (view == null) return;
        OperationStatus status = transientStatus.getOrDefault(playerId, operationStatus(player, view));
        TargetMode mode = parseMode(view.page());

        inventory.setItem(4, ghost(modeIcon(mode), modeTitle(mode),
                modeInstruction(mode),
                "<dark_gray>Preview first • explicit action below • unused inputs return on close</dark_gray>"));

        int output = outputSlot(view.page());
        if (view.previewResult() != null) {
            inventory.setItem(output, resultPreview(view.previewResult(), status));
        } else {
            inventory.setItem(output, ghost(Material.LIGHT_GRAY_DYE,
                    "<gray><bold>RESULT PREVIEW</bold></gray>",
                    "<dark_gray>" + safe(status.detail()) + "</dark_gray>",
                    "", "<gray>This slot never grants an item.</gray>"));
        }

        inventory.setItem(MODE_REPAIR, modeButton(TargetMode.REPAIR, mode));
        inventory.setItem(MODE_COMBINE, modeButton(TargetMode.COMBINE, mode));
        inventory.setItem(MODE_ENCHANT, modeButton(TargetMode.ENCHANT, mode));
        inventory.setItem(BACK_HOME, ghost(Material.ARROW,
                "<white><bold>BLACKSMITH HOME</bold></white>",
                "<gray>Return unused inputs, then choose another service.</gray>"));
        inventory.setItem(PRIMARY_ACTION, primaryButton(mode, status, view.confirmationArmed()));
        inventory.setItem(GUIDE, ghost(Material.WRITABLE_BOOK,
                "<white><bold>GUIDE</bold></white>",
                "<gray>Review this service without changing your inputs.</gray>"));
        inventory.setItem(STATUS, statusButton(status));
        inventory.setItem(CLOSE, ghost(Material.BARRIER,
                "<red><bold>CLOSE</bold></red>",
                "<gray>Return all unused inputs and leave.</gray>"));
        inventory.setItem(RESERVED, pane(Material.GRAY_STAINED_GLASS_PANE));
        renderedTokens.put(playerId, token(view));
    }

    private void renderGuideOverlay(Player player, Inventory inventory) {
        fillFrame(inventory);
        inventory.setItem(4, ghost(Material.WRITABLE_BOOK,
                "<gradient:#f59e0b:#fde68a><bold>WORKSTATION GUIDE</bold></gradient>",
                "<gray>Your current inputs remain safely cached while this guide is open.</gray>"));
        inventory.setItem(19, ghost(Material.ANVIL, "<gold><bold>REPAIR</bold></gold>",
                "<gray>One damaged repairable item.</gray>",
                "<gray>Only the permitted durability state changes.</gray>",
                "", "<white>Click to open Repair.</white>"));
        inventory.setItem(21, ghost(Material.SMITHING_TABLE, "<yellow><bold>COMBINE</bold></yellow>",
                "<gray>Primary + matching compatible donor.</gray>",
                "<gray>The donor is consumed only after the authoritative transaction commits.</gray>",
                "", "<white>Click to open Combine.</white>"));
        inventory.setItem(23, ghost(Material.ENCHANTING_TABLE, "<light_purple><bold>ENCHANT</bold></light_purple>",
                "<gray>Target + enchanted book.</gray>",
                "<gray>Only supported compatible upgrades can be applied.</gray>",
                "", "<white>Click to open Enchant.</white>"));
        inventory.setItem(31, ghost(Material.GOLD_NUGGET, "<gold><bold>QUOTES & COSTS</bold></gold>",
                "<gray>The displayed price comes from the same Phase 2 quote authority used by the transaction.</gray>",
                "<gray>Browsing and previewing never withdraw currency.</gray>"));
        inventory.setItem(33, ghost(Material.CHEST, "<aqua><bold>INPUT SAFETY</bold></aqua>",
                "<gray>Close or return Home to receive every unused cached input.</gray>",
                "<gray>Failed operations keep inputs and preserve Phase 2 refund behavior.</gray>"));
        inventory.setItem(BACK_HOME, ghost(Material.ARROW, "<white><bold>BACK TO WORKSTATION</bold></white>",
                "<gray>Restore the current service and quote.</gray>"));
        inventory.setItem(CLOSE, ghost(Material.BARRIER, "<red><bold>CLOSE</bold></red>",
                "<gray>Return unused inputs and leave.</gray>"));
    }

    private OperationStatus operationStatus(Player player, BlacksmithSessionView view) {
        TargetMode mode = parseMode(view.page());
        return switch (mode) {
            case REPAIR -> repairStatus(player, view);
            case COMBINE -> combineStatus(player, view);
            case ENCHANT -> enchantStatus(player, view);
        };
    }

    private OperationStatus repairStatus(Player player, BlacksmithSessionView view) {
        if (view.repairInput() == null) return waiting("WAITING FOR ITEM", "Place a damaged repairable item in the input slot.");
        PlexonBlacksmithAPI api = api();
        if (api == null) return blocked("SERVICE UNAVAILABLE", "The Blacksmith quote service is unavailable.", true);
        RepairQuote quote = api.quoteRepair(player, view.repairInput());
        if (!quote.repairable()) return rejectedRepairStatus(view.repairInput());
        return pricedStatus(player, "READY TO REPAIR", "Repair will restore the permitted durability state.", quote.price(), quote.currencyProvider(), true);
    }

    private OperationStatus combineStatus(Player player, BlacksmithSessionView view) {
        if (!getConfig().getBoolean("features.combine", true)) return blocked("FEATURE DISABLED", "Combine is disabled by the server configuration.", false);
        if (view.combinePrimary() == null) return waiting("WAITING FOR LEFT ITEM", "Add the primary damageable item first.");
        if (view.combineDonor() == null) return waiting("WAITING FOR RIGHT ITEM", "Add a matching compatible donor item.");
        PlexonBlacksmithAPI api = api();
        if (api == null) return blocked("SERVICE UNAVAILABLE", "The Blacksmith quote service is unavailable.", true);
        CombineQuote quote = api.quoteCombine(player, view.combinePrimary(), view.combineDonor());
        if (!quote.combinable()) {
            String detail = view.combinePrimary().getType() != view.combineDonor().getType()
                    ? "The items must be the same type."
                    : "These items cannot be combined under the current Blacksmith compatibility rules.";
            return blocked("ITEMS CANNOT BE COMBINED", detail, true);
        }
        return pricedStatus(player, "READY TO COMBINE", "The donor will be consumed only after a successful commit.", quote.price(), quote.currencyProvider(), true);
    }

    private OperationStatus enchantStatus(Player player, BlacksmithSessionView view) {
        if (!getConfig().getBoolean("features.enchant", true)) return blocked("FEATURE DISABLED", "Enchant is disabled by the server configuration.", false);
        if (view.enchantTarget() == null) return waiting("WAITING FOR TARGET", "Add the item you want to enchant.");
        if (view.enchantBook() == null) return waiting("WAITING FOR BOOK", "Add an enchanted book with a supported compatible upgrade.");
        PlexonBlacksmithAPI api = api();
        if (api == null) return blocked("SERVICE UNAVAILABLE", "The Blacksmith quote service is unavailable.", true);
        EnchantQuote quote = api.quoteEnchant(player, view.enchantTarget(), view.enchantBook());
        if (!quote.compatible()) return blocked("NO COMPATIBLE UPGRADE", "This enchanted book cannot apply a valid upgrade to the target item.", true);
        String detail = quote.appliedEnchantments().isEmpty()
                ? "Compatible enchantment upgrade ready."
                : quote.appliedEnchantments().size() + " compatible enchantment upgrade(s) will be applied.";
        return pricedStatus(player, "READY TO ENCHANT", detail, quote.price(), quote.currencyProvider(), true);
    }

    private OperationStatus rejectedRepairStatus(ItemStack item) {
        if (item != null && item.getItemMeta() instanceof Damageable damageable && damageable.getDamage() <= 0) {
            return blocked("ALREADY FULLY REPAIRED", "This item is already at full durability.", true);
        }
        return blocked("NOT REPAIRABLE", "This item cannot be repaired by the Blacksmith.", true);
    }

    private OperationStatus pricedStatus(Player player, String readyTitle, String detail,
                                         double price, String provider, boolean featureEnabled) {
        BalanceSnapshot balance = readBalance(player, provider);
        if ((provider == null || provider.equalsIgnoreCase("unavailable")) && !balance.available()) {
            return new OperationStatus("ECONOMY UNAVAILABLE", "Paid Blacksmith operations are unavailable right now.",
                    price, balance, false, featureEnabled);
        }
        if (balance.available() && balance.balance() + 1.0e-9 < price) {
            return new OperationStatus("INSUFFICIENT FUNDS",
                    "You need $" + money.format(price) + " to complete this operation.", price, balance, false, featureEnabled);
        }
        return new OperationStatus(readyTitle, detail, price, balance, true, featureEnabled);
    }

    private OperationStatus waiting(String title, String detail) {
        return new OperationStatus(title, detail, 0.0, BalanceSnapshot.unavailable("Vault"), false, true);
    }

    private OperationStatus blocked(String title, String detail, boolean featureEnabled) {
        return new OperationStatus(title, detail, 0.0, BalanceSnapshot.unavailable("Vault"), false, featureEnabled);
    }

    private BalanceSnapshot readBalance(Player player, String providerName) {
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            @SuppressWarnings({"rawtypes", "unchecked"})
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) economyClass);
            if (registration == null) return BalanceSnapshot.unavailable(providerName);
            Object provider = registration.getProvider();
            Method getBalance = economyClass.getMethod("getBalance", OfflinePlayer.class);
            Method getName = economyClass.getMethod("getName");
            double balance = ((Number) getBalance.invoke(provider, player)).doubleValue();
            Object name = getName.invoke(provider);
            return new BalanceSnapshot(Double.isFinite(balance), balance,
                    name == null ? (providerName == null ? "Vault" : providerName) : String.valueOf(name));
        } catch (Throwable ignored) {
            return BalanceSnapshot.unavailable(providerName);
        }
    }

    private ItemStack primaryButton(TargetMode mode, OperationStatus status, boolean confirmationArmed) {
        if (!status.ready()) {
            return ghost(Material.GRAY_DYE, "<gray><bold>ACTION UNAVAILABLE</bold></gray>",
                    "<gray>" + safe(status.detail()) + "</gray>");
        }
        String action = switch (mode) {
            case REPAIR -> "REPAIR ITEM";
            case COMBINE -> "COMBINE ITEMS";
            case ENCHANT -> "APPLY ENCHANTMENTS";
        };
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Cost</gray> <white>$" + money.format(status.price()) + "</white>");
        if (confirmationArmed) {
            lore.add("<yellow>This operation is armed for confirmation.</yellow>");
            lore.add("<green><bold>CLICK AGAIN TO CONFIRM</bold></green>");
        } else {
            lore.add("<green>Click to submit this exact current quote.</green>");
        }
        return ghost(Material.LIME_CONCRETE, "<green><bold>" + action + "</bold></green>", lore.toArray(String[]::new));
    }

    private ItemStack statusButton(OperationStatus status) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + safe(status.detail()) + "</gray>");
        if (status.price() > 0.0) lore.add("<gray>Quote</gray> <white>$" + money.format(status.price()) + "</white>");
        if (status.balance().available()) lore.add("<gray>Balance</gray> <white>$" + money.format(status.balance().balance()) + "</white>");
        if (status.price() > 0.0 && status.balance().provider() != null && !status.balance().provider().isBlank()) {
            lore.add("<dark_gray>Economy: " + safe(status.balance().provider()) + " via Vault</dark_gray>");
        }
        Material icon = status.ready() ? Material.LIME_DYE
                : status.title().contains("FUNDS") || status.title().contains("UNAVAILABLE") || status.title().contains("DISABLED")
                ? Material.RED_DYE : Material.YELLOW_DYE;
        return ghost(icon, (status.ready() ? "<green><bold>" : "<yellow><bold>") + safe(status.title()) + "</bold>" + (status.ready() ? "</green>" : "</yellow>"),
                lore.toArray(String[]::new));
    }

    private ItemStack resultPreview(ItemStack exactResult, OperationStatus status) {
        ItemStack preview = exactResult.clone();
        ItemMeta meta = preview.getItemMeta();
        if (meta == null) return preview;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(mm("<aqua><bold>RESULT PREVIEW</bold></aqua>"));
        if (status.price() > 0.0) lore.add(mm("<gray>Quote</gray> <white>$" + money.format(status.price()) + "</white>"));
        lore.add(mm("<dark_gray>Preview only — use the bottom action to commit.</dark_gray>"));
        meta.lore(lore);
        preview.setItemMeta(meta);
        return preview;
    }

    private ItemStack modeButton(TargetMode target, TargetMode current) {
        boolean selected = target == current;
        boolean enabled = target == TargetMode.REPAIR
                || target == TargetMode.COMBINE && getConfig().getBoolean("features.combine", true)
                || target == TargetMode.ENCHANT && getConfig().getBoolean("features.enchant", true);
        if (!enabled) return ghost(Material.BARRIER,
                "<red><bold>" + target.name() + " DISABLED</bold></red>",
                "<gray>This service is disabled.</gray>");
        Material icon = switch (target) {
            case REPAIR -> Material.ANVIL;
            case COMBINE -> Material.SMITHING_TABLE;
            case ENCHANT -> Material.ENCHANTING_TABLE;
        };
        return ghost(icon,
                selected ? "<gold><bold>● " + target.name() + "</bold></gold>" : "<white><bold>" + target.name() + "</bold></white>",
                selected ? "<gray>Current service.</gray>" : "<gray>Switch service; cached inputs remain safe.</gray>");
    }

    private boolean isRejectedRepairInput(BlacksmithSessionView view, int raw, ItemStack cursor) {
        if (!view.page().equals("REPAIR") || raw != REPAIR_INPUT || cursor == null || cursor.getType().isAir()) return false;
        PlexonBlacksmithAPI api = api();
        return api != null && !api.quoteRepair(null, cursor).repairable();
    }

    private PresentationToken token(BlacksmithSessionView view) {
        int inputsHash = Objects.hash(view.repairInput(), view.combinePrimary(), view.combineDonor(),
                view.enchantTarget(), view.enchantBook(), view.previewResult());
        return new PresentationToken(view.sessionId(), view.page(), inputsHash,
                Double.doubleToLongBits(view.previewPrice()), view.confirmationArmed());
    }

    private BlacksmithSessionView currentView(Player player) {
        PlexonBlacksmithAPI api = api();
        return api == null ? null : api.activeSession(player.getUniqueId()).orElse(null);
    }

    private PlexonBlacksmithAPI api() {
        return Bukkit.getServicesManager().load(PlexonBlacksmithAPI.class);
    }

    private int outputSlot(String page) {
        return page.equals("REPAIR") ? REPAIR_OUTPUT : page.equals("COMBINE") ? COMBINE_OUTPUT : ENCHANT_OUTPUT;
    }

    private TargetMode parseMode(String page) {
        return switch (page) {
            case "COMBINE" -> TargetMode.COMBINE;
            case "ENCHANT" -> TargetMode.ENCHANT;
            default -> TargetMode.REPAIR;
        };
    }

    private Material modeIcon(TargetMode mode) {
        return switch (mode) {
            case REPAIR -> Material.ANVIL;
            case COMBINE -> Material.SMITHING_TABLE;
            case ENCHANT -> Material.ENCHANTING_TABLE;
        };
    }

    private String modeTitle(TargetMode mode) {
        return switch (mode) {
            case REPAIR -> "<gradient:#f59e0b:#fde68a><bold>REPAIR</bold></gradient>";
            case COMBINE -> "<gradient:#fbbf24:#fef3c7><bold>COMBINE</bold></gradient>";
            case ENCHANT -> "<gradient:#c084fc:#f5d0fe><bold>ENCHANT</bold></gradient>";
        };
    }

    private String modeInstruction(TargetMode mode) {
        return switch (mode) {
            case REPAIR -> "<gray>Input → exact repaired preview → quoted action.</gray>";
            case COMBINE -> "<gray>Primary + matching donor → exact combined preview.</gray>";
            case ENCHANT -> "<gray>Target + enchanted book → compatible exact preview.</gray>";
        };
    }

    private ItemStack[] copyContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) copy[i] = contents[i] == null ? null : contents[i].clone();
        return copy;
    }

    private void restoreGuideSnapshot(UUID playerId, Inventory inventory) {
        ItemStack[] snapshot = guideSnapshots.remove(playerId);
        if (inventory != null && snapshot != null) inventory.setContents(snapshot);
    }

    private void fillFrame(Inventory inventory) {
        for (int slot = 0; slot < SIZE; slot++) {
            int row = slot / 9;
            int col = slot % 9;
            inventory.setItem(slot, pane(row == 0 || row == 5 || col == 0 || col == 8
                    ? Material.GRAY_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE));
        }
    }

    private ItemStack pane(Material material) {
        return ghost(material, "<black> </black>");
    }

    private ItemStack ghost(Material material, String name, String... loreLines) {
        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(mm(name));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) lore.add(line.isEmpty() ? Component.empty() : mm(line));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private Component mm(String value) {
        return MINI.deserialize(value).decoration(TextDecoration.ITALIC, false);
    }

    private String safe(String value) {
        if (value == null) return "";
        return value.replace("<", "‹").replace(">", "›");
    }
}
