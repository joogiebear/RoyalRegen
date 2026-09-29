package com.mystipixel.royalregen;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrushTest {

    @Test
    void bonusOnlyReachesBonusDrops() {
        assertEquals(5.0, Brush.chance(4, true, 1), 1e-9);
        assertEquals(4.0, Brush.chance(4, false, 1), 1e-9);
    }

    @Test
    void chanceIsClampedToAPercentage() {
        assertEquals(100.0, Brush.chance(99, true, 5), 1e-9);
        assertEquals(0.0, Brush.chance(1, true, -5), 1e-9);
    }

    @Test
    void zeroNeverHitsAndHundredAlwaysDoes() {
        assertFalse(Brush.hit(0, 0.0));
        assertTrue(Brush.hit(100, Math.nextDown(1.0)));
    }

    @Test
    void rollIsComparedInPercentagePoints() {
        assertTrue(Brush.hit(4, 0.039));
        assertFalse(Brush.hit(4, 0.04));
        assertTrue(Brush.hit(0.2, 0.0019));
        assertFalse(Brush.hit(0.2, 0.002));
    }

    @Test
    void ecoItemsTagWinsOverTheMaterial() {
        assertEquals("ecoitems:archaeologists_brush",
                Brush.toolId("Archaeologists_Brush", "minecraft:brush"));
        assertEquals("minecraft:brush", Brush.toolId(null, "minecraft:brush"));
        assertEquals("minecraft:brush", Brush.toolId(" ", "minecraft:brush"));
    }

    @Test
    void commandPlaceholdersAreFilledAndSlashDropped() {
        String out = Brush.expand("/ecomobs spawn boss %x% %y% %z% %world% for %player% (%tool%)",
                Map.of("x", "60", "y", "80", "z", "150", "world", "world", "player", "Steve",
                        "tool", "minecraft:brush"));
        assertEquals("ecomobs spawn boss 60 80 150 world for Steve (minecraft:brush)", out);
    }

    @Test
    void loadsTheDocumentedExample() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString("""
                    brush:
                      enabled: true
                      replace-vanilla-loot: true
                      tools:
                        minecraft:brush: 0
                        ecoitems:archaeologists_brush: 1
                        ecoitems:master_brush: 2
                      drops:
                        - chance: 4
                          bonus: true
                          commands: ["ecoitems give %player% stone_fragment 1", "ecoskills give %player% mining 5"]
                        - chance: 2
                          commands: ["ecoitems give %player% coin_fragment 1"]
                      rare-event:
                        chance: 0.2
                        commands: ["ecomobs spawn buried_sentinel %x% %y% %z% %world%"]
                    """);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Brush brush = Brush.load(yaml.getConfigurationSection("brush"), "z", "b", Logger.getAnonymousLogger());
        assertNotNull(brush);
        assertTrue(brush.replaceVanillaLoot());
        assertEquals(2, brush.drops().size());
        assertEquals(2, brush.drops().get(0).commands().size());
        assertTrue(brush.drops().get(0).bonus());
        assertFalse(brush.drops().get(1).bonus());
        assertEquals(0.2, brush.rare().chance(), 1e-9);
        assertEquals(2.0, brush.toolBonus("ecoitems:master_brush"), 1e-9);
        assertEquals(0.0, brush.toolBonus("minecraft:brush"), 1e-9);
        assertEquals(0.0, brush.toolBonus("ecoitems:unknown"), 1e-9);
    }

    @Test
    void aDisabledOrMissingSectionLoadsNothing() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("brush.enabled", false);
        assertNull(Brush.load(yaml.getConfigurationSection("brush"), "z", "b", Logger.getAnonymousLogger()));
        assertNull(Brush.load(null, "z", "b", Logger.getAnonymousLogger()));
    }
}
