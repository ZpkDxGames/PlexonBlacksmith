package dev.plexon.blacksmith.integration.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StandaloneCoreBridgeTest {
    @Test
    void absentCoreRemainsSafeStandalone() {
        CoreBridge bridge = new StandaloneCoreBridge(false, "-", "-", "PlexonCore is not installed");
        assertEquals(">=1.0 <2.0", CoreBridge.SUPPORTED_API_RANGE);
        assertEquals("blacksmith", CoreBridge.MODULE_ID);
        assertEquals("STANDALONE", bridge.mode());
        assertFalse(bridge.available());
        bridge.registerStarting();
        bridge.markReady("ignored");
        bridge.unregister();
        assertEquals("NOT_INSTALLED", bridge.registrationState());
    }
}
