package com.mystipixel.royalregen;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * What finishing a brush on a suspicious block pays out. The maths is static so it can be tested
 * without a server.
 *
 * @param replaceVanillaLoot discard the vanilla brush item, so this table is the whole reward
 * @param tools              percentage points added to every {@code bonus} drop, by tool id
 * @param drops              rolled independently, so one brush can pay several or none
 * @param rare               rolled once per completed brush, or null
 */
public record Brush(boolean replaceVanillaLoot, Map<String, Double> tools, List<Drop> drops, Rare rare) {

    /** The tool id used when the held item is not an EcoItem. */
    public static final String VANILLA_TOOL = "minecraft:brush";

    /**
     * @param chance   percent, 0-100
     * @param bonus    whether the tool's bonus is added to {@code chance}
     * @param commands console commands, run once each on a hit
     */
    public record Drop(double chance, boolean bonus, List<String> commands) {
    }

    /** A once-per-brush chance at something out of the ordinary, such as a mob. */
    public record Rare(double chance, List<String> commands) {
    }

    // null when the block has no brush section or it is switched off
    static Brush load(ConfigurationSection sec, String zone, String block, Logger logger) {
        if (sec == null || !sec.getBoolean("enabled", true)) {
            return null;
        }
        String where = "Zone '" + zone + "' " + block + ": ";

        Map<String, Double> tools = new LinkedHashMap<>();
        ConfigurationSection toolSec = sec.getConfigurationSection("tools");
        if (toolSec != null) {
            for (String id : toolSec.getKeys(false)) {
                Object raw = toolSec.get(id);
                if (raw instanceof Number bonus) {
                    tools.put(id.toLowerCase(Locale.ROOT), bonus.doubleValue());
                } else {
                    logger.warning(where + "brush tool '" + id + "' needs a number, ignoring it.");
                }
            }
        }

        List<Drop> drops = new ArrayList<>();
        for (Map<?, ?> row : sec.getMapList("drops")) {
            List<String> commands = commands(row.get("commands"));
            if (commands.isEmpty()) {
                logger.warning(where + "a brush drop has no commands, ignoring it.");
                continue;
            }
            Double chance = percent(row.get("chance"), where, logger);
            if (chance != null) {
                drops.add(new Drop(chance, Boolean.TRUE.equals(row.get("bonus")), commands));
            }
        }

        Rare rare = null;
        ConfigurationSection rareSec = sec.getConfigurationSection("rare-event");
        if (rareSec != null) {
            List<String> commands = commands(rareSec.get("commands"));
            Double chance = percent(rareSec.get("chance"), where, logger);
            if (chance != null && !commands.isEmpty()) {
                rare = new Rare(chance, commands);
            }
        }

        if (drops.isEmpty() && rare == null) {
            logger.warning(where + "brush is enabled but has no usable drops or rare-event, so a brush"
                    + " would only regenerate the block.");
        }
        return new Brush(sec.getBoolean("replace-vanilla-loot", true), Map.copyOf(tools),
                List.copyOf(drops), rare);
    }

    private static Double percent(Object raw, String where, Logger logger) {
        if (!(raw instanceof Number number)) {
            logger.warning(where + "brush chance '" + raw + "' isn't a number, ignoring that entry.");
            return null;
        }
        double value = number.doubleValue();
        if (value < 0 || value > 100) {
            logger.warning(where + "brush chance " + value + " is outside 0-100, clamping it.");
        }
        return Math.max(0, Math.min(100, value));
    }

    private static List<String> commands(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object line : list) {
                if (line != null && !line.toString().isBlank()) {
                    out.add(line.toString().trim());
                }
            }
        }
        return out;
    }

    /** The tool's bonus in percentage points, 0 when the tool isn't listed. */
    public double toolBonus(String toolId) {
        return tools.getOrDefault(toolId.toLowerCase(Locale.ROOT), 0.0);
    }

    /** A drop's chance after the tool's bonus, kept within 0-100. The bonus only reaches {@code bonus} drops. */
    public static double chance(double base, boolean bonus, double toolBonus) {
        return Math.max(0, Math.min(100, bonus ? base + toolBonus : base));
    }

    /**
     * Whether a roll succeeds. {@code roll} is uniform in [0, 1), so 0% can never hit and 100% always does.
     */
    public static boolean hit(double chancePercent, double roll) {
        return roll * 100 < chancePercent;
    }

    /**
     * The id the tool is looked up by: {@code ecoitems:<id>} for an EcoItem, otherwise the item's own
     * key. Only EcoItems' own tag counts, since other plugins tag vanilla items too.
     *
     * @param ecoItemsId the value of the {@code ecoitems:item} tag, or null
     */
    public static String toolId(String ecoItemsId, String materialKey) {
        if (ecoItemsId != null && !ecoItemsId.isBlank()) {
            return "ecoitems:" + ecoItemsId.toLowerCase(Locale.ROOT);
        }
        return materialKey.toLowerCase(Locale.ROOT);
    }

    /** Fill {@code %name%} placeholders in a command; a leading slash is dropped. */
    public static String expand(String command, Map<String, String> values) {
        String out = command.startsWith("/") ? command.substring(1) : command;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return out;
    }
}
