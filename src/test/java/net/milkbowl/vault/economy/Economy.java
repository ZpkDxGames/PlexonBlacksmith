package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

public interface Economy {
    double getBalance(OfflinePlayer player);
    EconomyResponse withdrawPlayer(OfflinePlayer player, double amount);
    EconomyResponse depositPlayer(OfflinePlayer player, double amount);
    String getName();
}
