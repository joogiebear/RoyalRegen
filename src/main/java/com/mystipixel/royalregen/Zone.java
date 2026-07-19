package com.mystipixel.royalregen;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A cuboid where listed blocks may be harvested and then come back.
 *
 * <p>Only blocks named here are breakable — that whitelist is the protection. Everything else in the
 * zone is left to whatever else guards the world, so scenery inside a farm stays safe.
 */
public final class Zone {

    /** What one harvestable block gives, and whether it has to be grown first. */
    public record Rule(List<ItemStack> drops, boolean requireMature) {
    }

    private final String id;
    private final String world;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final long regenMillis;
    private final Map<Material, Rule> rules;

    private Zone(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                 long regenMillis, Map<Material, Rule> rules) {
        this.id = id;
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.regenMillis = regenMillis;
        this.rules = rules;
    }

    /** Read one zone, or null (with a reason logged) if it can't produce a usable one. */
    public static Zone load(String id, ConfigurationSection sec, Logger logger) {
        String world = sec.getString("world", "");
        if (world.isBlank()) {
            logger.warning("Zone '" + id + "' has no world — skipping it.");
            return null;
        }
        ConfigurationSection min = sec.getConfigurationSection("min");
        ConfigurationSection max = sec.getConfigurationSection("max");
        if (min == null || max == null) {
            logger.warning("Zone '" + id + "' needs both a min and a max corner — skipping it.");
            return null;
        }
        ConfigurationSection blocks = sec.getConfigurationSection("blocks");
        if (blocks == null || blocks.getKeys(false).isEmpty()) {
            logger.warning("Zone '" + id + "' lists no blocks, so nothing in it could be harvested"
                    + " — skipping it.");
            return null;
        }

        Map<Material, Rule> rules = new LinkedHashMap<>();
        for (String key : blocks.getKeys(false)) {
            Material material = material(key);
            if (material == null || !material.isBlock()) {
                logger.warning("Zone '" + id + "': '" + key + "' isn't a block — ignoring it.");
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
            boolean mature = rule == null || rule.getBoolean("require-mature", true);
            rules.put(material, new Rule(List.copyOf(drops), mature));
        }
        if (rules.isEmpty()) {
            logger.warning("Zone '" + id + "' had no usable blocks — skipping it.");
            return null;
        }

        int seconds = Math.max(1, sec.getInt("regen-seconds", 45));
        return new Zone(id, world,
                Math.min(min.getInt("x"), max.getInt("x")), Math.min(min.getInt("y"), max.getInt("y")),
                Math.min(min.getInt("z"), max.getInt("z")), Math.max(min.getInt("x"), max.getInt("x")),
                Math.max(min.getInt("y"), max.getInt("y")), Math.max(min.getInt("z"), max.getInt("z")),
                seconds * 1000L, rules);
    }

    /**
     * Resolve a block name, with or without its namespace.
     *
     * <p>Bukkit's matcher wants either a lowercase namespaced key or a bare enum name, and uppercasing
     * a whole key gives it neither — "MINECRAFT:POTATOES" resolves to nothing. Strip the namespace and
     * match on the name, so both {@code minecraft:potatoes} and {@code POTATOES} work in config.
     */
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
            logger.warning("Zone '" + zone + "': drop '" + parts[0] + "' isn't an item — ignoring it.");
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException bad) {
                logger.warning("Zone '" + zone + "': bad amount in '" + line + "' — using 1.");
            }
        }
        return new ItemStack(material, amount);
    }

    public boolean contains(Block block) {
        if (!block.getWorld().getName().equalsIgnoreCase(world)) {
            return false;
        }
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(Location location) {
        return location.getWorld() != null
                && location.getWorld().getName().equalsIgnoreCase(world)
                && location.getBlockX() >= minX && location.getBlockX() <= maxX
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
}
