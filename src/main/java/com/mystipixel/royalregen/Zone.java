package com.mystipixel.royalregen;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Brushable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A cuboid where listed blocks may be harvested and then come back. Only the listed blocks are
 * breakable; everything else is left to the world's protection.
 */
public final class Zone {

    /**
     * What one harvestable block gives, and the conditions on taking it.
     *
     * @param requireMature refuses an unripe plant; defaults on for crops only (see {@link #isCrop})
     * @param requireLeaves logs only: refuses a log that is not part of a living tree
     * @param regenMillis   how long this block takes to come back; defaults to the zone's timer
     * @param fell          logs only: harvesting one brings the connected tree down over several ticks
     * @param placeholder   block shown until it returns (bedrock in a mine), or null for empty.
     *                      Crops ignore it.
     * @param brush         suspicious sand and gravel only: the brush payout, or null for none
     */
    public record Rule(List<ItemStack> drops, boolean requireMature, boolean requireLeaves,
                       long regenMillis, boolean fell, Material placeholder, Brush brush) {
    }

    private final String id;
    private final String world;
    private final boolean wholeWorld;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final long regenMillis;
    private final Map<Material, Rule> rules;
    private final String displayName;
    private final boolean announce;
    private final String discoveryTitle;
    private final String discoverySubtitle;

    private Zone(String id, String world, boolean wholeWorld,
                 int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                 long regenMillis, Map<Material, Rule> rules, String displayName, boolean announce,
                 String discoveryTitle, String discoverySubtitle) {
        this.id = id;
        this.world = world;
        this.wholeWorld = wholeWorld;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.regenMillis = regenMillis;
        this.rules = rules;
        this.displayName = displayName;
        this.announce = announce;
        this.discoveryTitle = discoveryTitle;
        this.discoverySubtitle = discoverySubtitle;
    }

    /** Read one zone, or null (with a reason logged) if it can't produce a usable one. */
    public static Zone load(String id, ConfigurationSection sec, Logger logger) {
        String world = sec.getString("world", "");
        if (world.isBlank()) {
            logger.warning("Zone '" + id + "' has no world, skipping it.");
            return null;
        }
        // No corners means the whole world: fine for crops, but logs and stone need corners or
        // players could harvest the map itself.
        ConfigurationSection min = sec.getConfigurationSection("min");
        ConfigurationSection max = sec.getConfigurationSection("max");
        boolean wholeWorld = min == null && max == null;
        if (!wholeWorld && (min == null || max == null)) {
            logger.warning("Zone '" + id + "' has only one corner. Give it both, or neither for the"
                    + " whole world. Skipping it.");
            return null;
        }
        ConfigurationSection blocks = sec.getConfigurationSection("blocks");
        if (blocks == null || blocks.getKeys(false).isEmpty()) {
            logger.warning("Zone '" + id + "' lists no blocks, so nothing in it could be harvested"
                    + ", skipping it.");
            return null;
        }

        long zoneMillis = millis(sec, 45_000L, id, logger);
        Material zonePlaceholder = placeholder(sec.getString("placeholder"), null, id, logger);

        Map<Material, Rule> rules = new LinkedHashMap<>();
        for (String key : blocks.getKeys(false)) {
            Material material = material(key);
            if (material == null || !material.isBlock()) {
                logger.warning("Zone '" + id + "': '" + key + "' isn't a block, ignoring it.");
                continue;
            }
            ConfigurationSection rule = blocks.getConfigurationSection(key);
            List<ItemStack> drops = new ArrayList<>();
            if (rule != null) {
                for (String line : rule.getStringList("drops")) {
                    ItemStack item = parseItem(line, id, logger);
                    if (item != null) {
                        drops.add(item);
                    }
                }
            }
            boolean mature = rule == null
                    ? isCrop(material)
                    : rule.getBoolean("require-mature", isCrop(material));
            boolean leaves = rule != null && rule.getBoolean("require-leaves", false);
            long ruleMillis = rule != null ? millis(rule, zoneMillis, id, logger) : zoneMillis;
            boolean fell = rule != null && rule.getBoolean("fell", false);
            Material placeholder = rule != null && rule.contains("placeholder")
                    ? placeholder(rule.getString("placeholder"), null, id, logger)
                    : zonePlaceholder;
            Brush brush = null;
            if (rule != null && rule.contains("brush")) {
                if (material.createBlockData() instanceof Brushable) {
                    brush = Brush.load(rule.getConfigurationSection("brush"), id, key, logger);
                } else {
                    logger.warning("Zone '" + id + "': '" + key + "' can't be brushed, ignoring its"
                            + " brush section.");
                }
            }
            rules.put(material, new Rule(List.copyOf(drops), mature, leaves, ruleMillis, fell, placeholder,
                    brush));
        }
        if (rules.isEmpty()) {
            logger.warning("Zone '" + id + "' had no usable blocks, skipping it.");
            return null;
        }

        String display = sec.getString("display-name", "&f" + id);
        ConfigurationSection disc = sec.getConfigurationSection("discovery");
        boolean announce = disc == null || disc.getBoolean("enabled", true);
        String title = disc != null ? disc.getString("title", "&6" + display) : "&6" + display;
        String subtitle = disc != null ? disc.getString("subtitle", "&7Discovered") : "&7Discovered";
        if (wholeWorld) {
            return new Zone(id, world, true, 0, 0, 0, 0, 0, 0,
                    zoneMillis, rules, display, announce, title, subtitle);
        }
        return new Zone(id, world, false,
                Math.min(min.getInt("x"), max.getInt("x")), Math.min(min.getInt("y"), max.getInt("y")),
                Math.min(min.getInt("z"), max.getInt("z")), Math.max(min.getInt("x"), max.getInt("x")),
                Math.max(min.getInt("y"), max.getInt("y")), Math.max(min.getInt("z"), max.getInt("z")),
                zoneMillis, rules, display, announce, title, subtitle);
    }

    // "none" or "air" means no placeholder; unset falls back
    private static Material placeholder(String key, Material fallback, String zone, Logger logger) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        if (key.equalsIgnoreCase("none") || key.equalsIgnoreCase("air")) {
            return null;
        }
        Material material = material(key);
        if (material == null || !material.isBlock() || material.isAir()) {
            logger.warning("Zone '" + zone + "': placeholder '" + key + "' isn't a block, leaving harvested"
                    + " blocks empty instead.");
            return fallback;
        }
        return material;
    }

    // any number is accepted, so 1.5 means a second and a half
    private static long millis(ConfigurationSection sec, long fallback, String zone, Logger logger) {
        if (!sec.isSet("regen-seconds")) {
            return fallback;
        }
        Object raw = sec.get("regen-seconds");
        if (!(raw instanceof Number number)) {
            logger.warning("Zone '" + zone + "': regen-seconds '" + raw + "' isn't a number, using "
                    + (fallback / 1000.0) + "s.");
            return fallback;
        }
        return Math.max(1000L, Math.round(number.doubleValue() * 1000));
    }

    /**
     * Whether this block's age means ripeness. Stacked plants (sugar cane, cactus) are not crops:
     * their age counts toward the next block, so a ripe cane is usually at age 0.
     */
    public static boolean isCrop(Material material) {
        return Tag.CROPS.isTagged(material) || material == Material.NETHER_WART
                || material == Material.COCOA || material == Material.SWEET_BERRY_BUSH;
    }

    // accepts minecraft:potatoes or POTATOES; an uppercased namespaced key matches nothing
    private static Material material(String key) {
        String name = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        Material direct = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return direct != null ? direct : Material.matchMaterial(key.toLowerCase(Locale.ROOT));
    }

    private static ItemStack parseItem(String line, String zone, Logger logger) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(":");
        Material material = Material.matchMaterial(parts[0].trim().toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            logger.warning("Zone '" + zone + "': drop '" + parts[0] + "' isn't an item, ignoring it.");
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException bad) {
                logger.warning("Zone '" + zone + "': bad amount in '" + line + "', using 1.");
            }
        }
        return new ItemStack(material, amount);
    }

    public boolean contains(Block block) {
        if (!block.getWorld().getName().equalsIgnoreCase(world)) {
            return false;
        }
        if (wholeWorld) {
            return true;
        }
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equalsIgnoreCase(world)) {
            return false;
        }
        if (wholeWorld) {
            return true;
        }
        return location.getBlockX() >= minX && location.getBlockX() <= maxX
                && location.getBlockY() >= minY && location.getBlockY() <= maxY
                && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }

    /** The rule for this block, or null when it isn't harvestable here. */
    public Rule rule(Material material) {
        return rules.get(material);
    }

    public String id() { return id; }
    public String world() { return world; }
    public long regenMillis() { return regenMillis; }
    public int blockCount() { return rules.size(); }
    public String displayName() { return displayName; }
    public boolean announce() { return announce; }
    public String discoveryTitle() { return discoveryTitle; }
    public String discoverySubtitle() { return discoverySubtitle; }
}
