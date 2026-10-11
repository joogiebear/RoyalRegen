package com.mystipixel.royalregen;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverrideProtectionTest {

    // Zone.load needs a running server to resolve block names, so these build the zone directly
    private static Zone cornered(String id) {
        return new Zone(id, "hub", false, 0, 60, 0, 10, 70, 10, 45_000L, Map.of(), id, true, id, "");
    }

    private static Zone wholeWorld(String id) {
        return new Zone(id, "survival", true, 0, 0, 0, 0, 0, 0, 45_000L, Map.of(), id, true, id, "");
    }

    @Test
    void theShippedConfigDefaultsToFalse() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            assertTrue(config.isSet("override-protection"));
            assertFalse(config.getBoolean("override-protection"));
        }
    }

    @Test
    void offRevivesNothing() {
        assertFalse(cornered("farm").revivesCancelledBreaks(false));
        assertFalse(wholeWorld("crops").revivesCancelledBreaks(false));
    }

    @Test
    void onRevivesInACorneredZone() {
        assertTrue(cornered("farm").revivesCancelledBreaks(true));
    }

    @Test
    void onIsIgnoredInAWholeWorldZone() {
        Zone crops = wholeWorld("crops");
        assertTrue(crops.wholeWorld());
        assertFalse(crops.revivesCancelledBreaks(true));
    }

    @Test
    void warningNamesEachWholeWorldZone() {
        String warning = RoyalRegenPlugin.overrideIgnoredWarning(true,
                List.of(wholeWorld("crops"), cornered("farm"), wholeWorld("nether-wart")));
        assertNotNull(warning);
        assertEquals("override-protection is true, but it is ignored for whole-world zone(s) crops, nether-wart."
                + " A zone without min and max covers islands, claims and minion fields, so breaks other"
                + " plugins cancel there stay cancelled. Give the zone corners, or open your protection"
                + " for it and set override-protection: false.", warning);
    }

    @Test
    void noWarningWhenOffOrEveryZoneHasCorners() {
        assertNull(RoyalRegenPlugin.overrideIgnoredWarning(false, List.of(wholeWorld("crops"))));
        assertNull(RoyalRegenPlugin.overrideIgnoredWarning(true, List.of(cornered("farm"))));
    }
}
