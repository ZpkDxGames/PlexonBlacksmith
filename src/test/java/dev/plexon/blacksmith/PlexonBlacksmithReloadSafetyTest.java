package dev.plexon.blacksmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexon.blacksmith.api.BlacksmithSessionView;
import dev.plexon.blacksmith.api.PlexonBlacksmithAPI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlexonBlacksmithReloadSafetyTest {
    private static final int HOME_REPAIR = 20;
    private static final int REPAIR_INPUT = 20;

    private ServerMock server;
    private PlexonBlacksmithStable plugin;
    private PlayerMock player;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PlexonBlacksmithStable.class);
        player = server.addPlayer("ReloadTester");
        player.setOp(true);
        assertTrue(plugin.isEnabled());
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void malformedReloadKeepsAcceptedRuntimeAndActiveItemCustodySession() throws Exception {
        assertTrue(server.dispatchCommand(player, "blacksmith"));
        click(HOME_REPAIR);

        ItemStack damaged = new ItemStack(Material.DIAMOND_PICKAXE);
        Damageable meta = (Damageable) damaged.getItemMeta();
        meta.setDamage(100);
        damaged.setItemMeta(meta);
        player.setItemOnCursor(damaged);
        click(REPAIR_INPUT);

        PlexonBlacksmithAPI api = server.getServicesManager().load(PlexonBlacksmithAPI.class);
        assertNotNull(api);
        BlacksmithSessionView before = api.activeSession(player.getUniqueId()).orElseThrow();
        UUID sessionId = before.sessionId();
        assertNotNull(before.repairInput());

        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        Files.writeString(config, "features:\n  combine: [malformed\n", StandardCharsets.UTF_8);

        assertTrue(server.dispatchCommand(player, "blacksmith reload"));

        BlacksmithSessionView after = api.activeSession(player.getUniqueId()).orElseThrow();
        assertEquals(sessionId, after.sessionId(), "Rejected reload must not evict the active custody session");
        assertNotNull(after.repairInput(), "Rejected reload must keep the cached exact input authoritative");
        assertEquals(100, ((Damageable) after.repairInput().getItemMeta()).getDamage());
        assertEquals(Material.DIAMOND_PICKAXE, after.repairInput().getType());
    }

    private void click(int rawSlot) {
        InventoryClickEvent event = new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                rawSlot,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
    }
}
