package dev.plexon.blacksmith;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Stable entrypoint that preserves the accepted Phase 2/Phase 3 Blacksmith runtime and adds a
 * fail-closed configuration reload boundary. Bukkit's normal reloadConfig path logs malformed
 * YAML and may continue with defaults; Blacksmith must not evict item-custody sessions or change
 * prices/features unless the candidate file can first be parsed successfully.
 */
public class PlexonBlacksmithStable extends PlexonBlacksmithPhase3 {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (isAuthorizedReload(sender, args) && !reloadCandidateIsParseable(sender)) {
            return true;
        }
        return super.onCommand(sender, command, label, args);
    }

    private boolean isAuthorizedReload(CommandSender sender, String[] args) {
        return args.length > 0
                && args[0].equalsIgnoreCase("reload")
                && sender.hasPermission("plexon.blacksmith.admin");
    }

    private boolean reloadCandidateIsParseable(CommandSender sender) {
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration candidate = new YamlConfiguration();
        try {
            candidate.load(configFile);
            return true;
        } catch (IOException | InvalidConfigurationException error) {
            getLogger().log(Level.WARNING,
                    "Rejected Blacksmith reload because config.yml is missing or invalid; current runtime and active sessions remain unchanged.",
                    error);
            sender.sendMessage("Blacksmith » Reload rejected: config.yml is missing or invalid. The current runtime remains active.");
            return false;
        }
    }
}
