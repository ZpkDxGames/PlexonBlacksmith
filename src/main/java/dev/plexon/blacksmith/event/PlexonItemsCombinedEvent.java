package dev.plexon.blacksmith.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** Fired exactly once after a combine transaction has committed successfully. */
public final class PlexonItemsCombinedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID transactionId;
    private final String eventId;
    private final ItemStack primarySnapshot;
    private final ItemStack donorSnapshot;
    private final ItemStack resultSnapshot;
    private final int previousDamage;
    private final int newDamage;
    private final int repairAmount;
    private final double pricePaid;
    private final String currencyProvider;

    public PlexonItemsCombinedEvent(
            Player player,
            UUID transactionId,
            String eventId,
            ItemStack primarySnapshot,
            ItemStack donorSnapshot,
            ItemStack resultSnapshot,
            int previousDamage,
            int newDamage,
            int repairAmount,
            double pricePaid,
            String currencyProvider) {
        this.player = Objects.requireNonNull(player, "player");
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.eventId = requireText(eventId, "eventId");
        this.primarySnapshot = Objects.requireNonNull(primarySnapshot, "primarySnapshot").clone();
        this.donorSnapshot = Objects.requireNonNull(donorSnapshot, "donorSnapshot").clone();
        this.resultSnapshot = Objects.requireNonNull(resultSnapshot, "resultSnapshot").clone();
        this.previousDamage = previousDamage;
        this.newDamage = newDamage;
        this.repairAmount = repairAmount;
        this.pricePaid = pricePaid;
        this.currencyProvider = requireText(currencyProvider, "currencyProvider");
    }

    public Player getPlayer() { return player; }
    public UUID getTransactionId() { return transactionId; }
    public String getEventId() { return eventId; }
    public ItemStack getPrimarySnapshot() { return primarySnapshot.clone(); }
    public ItemStack getDonorSnapshot() { return donorSnapshot.clone(); }
    public ItemStack getResultSnapshot() { return resultSnapshot.clone(); }
    public int getPreviousDamage() { return previousDamage; }
    public int getNewDamage() { return newDamage; }
    public int getRepairAmount() { return repairAmount; }
    public double getPricePaid() { return pricePaid; }
    public String getCurrencyProvider() { return currencyProvider; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
