package dev.plexon.blacksmith;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PlexonBlacksmithPhase3ArchitectureTest {
    private static final Path UX = Path.of("src/main/java/dev/plexon/blacksmith/PlexonBlacksmithPhase3.java");

    @Test
    void phase3LayerDelegatesToPhase2AuthorityWithoutSecondTransactionEngine() throws Exception {
        String source = Files.readString(UX);
        assertTrue(source.contains("extends PlexonBlacksmithBridge"));
        assertTrue(source.contains("super.onClick(synthetic)"));
        assertTrue(source.contains("PlexonBlacksmithAPI"));
        assertFalse(source.contains("withdrawPlayer"), "UX layer must never withdraw money");
        assertFalse(source.contains("depositPlayer"), "UX layer must never implement refunds");
        assertFalse(source.contains("dropItem"), "UX layer must not invent item-return policy");
    }

    @Test
    void noRepeatingGuiOrPerTickRefreshWasIntroduced() throws Exception {
        String source = Files.readString(UX);
        assertFalse(source.contains("runTaskTimer"));
        assertFalse(source.contains("scheduleSyncRepeatingTask"));
        assertFalse(source.contains("BukkitRunnable"));
        assertFalse(source.contains("InventoryOpenEvent"));
    }

    @Test
    void pluginDescriptorUsesPhase3ProductLayerAndKeepsExistingCommandContract() throws Exception {
        String pluginYml = Files.readString(Path.of("src/main/resources/plugin.yml"));
        assertTrue(pluginYml.contains("main: dev.plexon.blacksmith.PlexonBlacksmithPhase3"));
        assertTrue(pluginYml.contains("blacksmith:"));
        assertTrue(pluginYml.contains("permission: plexon.blacksmith.use"));
        assertTrue(pluginYml.contains("- Vault"));
    }

    @Test
    void phase2ConfigKeysRemainPresent() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(config.contains("combine: true"));
        assertTrue(config.contains("enchant: true"));
        assertTrue(config.contains("expensive-threshold:"));
        assertTrue(config.contains("combine-always:"));
        assertTrue(config.contains("enchant-always:"));
        assertTrue(config.contains("repair-value-multiplier:"));
    }
}
