package dev.plexon.blacksmith.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** Fired once after an enchant transaction has fully committed. */
public final class PlexonItemEnchantedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID transactionId;
    private final String eventId;
    private final ItemStack itemSnapshot;
    private final ItemStack secondarySnapshot;
    private final Map<String, Integer> appliedEnchantments;
    private final double pricePaid;
    private final String currencyProvider;

    public PlexonItemEnchantedEvent(
            Player player,
            UUID transactionId,
            String eventId,
            ItemStack itemSnapshot,
            ItemStack secondarySnapshot,
            Map<String, Integer> appliedEnchantments,
            double pricePaid,
            String currencyProvider) {
        this.player = Objects.requireNonNull(player, "player");
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.eventId = requireText(eventId, "eventId");
        this.itemSnapshot = Objects.requireNonNull(itemSnapshot, "itemSnapshot").clone();
        this.secondarySnapshot = Objects.requireNonNull(secondarySnapshot, "secondarySnapshot").clone();
        this.appliedEnchantments = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(appliedEnchantments, "appliedEnchantments")));
        this.pricePaid = pricePaid;
        this.currencyProvider = requireText(currencyProvider, "currencyProvider");
    }

    public Player getPlayer() { return player; }
    public UUID getTransactionId() { return transactionId; }
    public String getEventId() { return eventId; }
    public ItemStack getItemSnapshot() { return itemSnapshot.clone(); }
    public ItemStack getSecondarySnapshot() { return secondarySnapshot.clone(); }
    public Map<String, Integer> getAppliedEnchantments() { return appliedEnchantments; }
    public double getPricePaid() { return pricePaid; }
    public String getCurrencyProvider() { return currencyProvider; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
