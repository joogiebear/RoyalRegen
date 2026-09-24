package com.mystipixel.royalregen;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
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

    /**
     * What one harvestable block gives, and the conditions on taking it.
     *
     * @param requireMature refuses a plant that has not finished growing. On by default for crops
     *                      only (see {@link #isCrop}); sugar cane, cactus, bamboo and kelp also carry
     *                      an age, but it counts toward the next block of the stack rather than
     *                      ripeness, so requiring it there refused almost every harvest
     * @param requireLeaves logs only — refuses a log that is not part of a living tree, which is
     *                      what makes a world-scoped lumber zone safe on a built map: the trees are
     *                      harvestable and the walls made of the same log are not
     * @param regenMillis   how long this block takes to come back, defaulting to the zone's own
     *                      timer. Per-block because value varies far more than location does: a
     *                      mine holds coal and diamond in the same stone, and zones cannot overlap,
     *                      so the only way to price them differently is here
     * @param fell          logs only — harvesting one brings the rest of the connected tree down
     *                      with it, spread over several ticks
     */
    public record Rule(List<ItemStack> drops, boolean requireMature, boolean requireLeaves,
                       long regenMillis, boolean fell) {
    }

    private final String id;
    private final String world;
    /** True when the zone has no corners and covers its whole world. */
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
            logger.warning("Zone '" + id + "' has no world — skipping it.");
            return null;
        }
        // Corners are optional. Omitting both makes the zone cover its whole world, which is what you
        // want for something like crops: they grow everywhere on a map and are never structural, so
        // there is no meaningful cuboid to draw around them. Give corners to anything that IS a
        // building material — logs and stone are walls as often as they are scenery, and a
        // world-scoped zone would let players harvest the map itself.
        ConfigurationSection min = sec.getConfigurationSection("min");
        ConfigurationSection max = sec.getConfigurationSection("max");
        boolean wholeWorld = min == null && max == null;
        if (!wholeWorld && (min == null || max == null)) {
            logger.warning("Zone '" + id + "' has only one corner — give it both, or neither for the"
                    + " whole world. Skipping it.");
            return null;
        }
        ConfigurationSection blocks = sec.getConfigurationSection("blocks");
        if (blocks == null || blocks.getKeys(false).isEmpty()) {
            logger.warning("Zone '" + id + "' lists no blocks, so nothing in it could be harvested"
                    + " — skipping it.");
            return null;
        }

        long zoneMillis = millis(sec, 45_000L, id, logger);

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
            boolean mature = rule == null
                    ? isCrop(material)
                    : rule.getBoolean("require-mature", isCrop(material));
            boolean leaves = rule != null && rule.getBoolean("require-leaves", false);
            long ruleMillis = rule != null ? millis(rule, zoneMillis, id, logger) : zoneMillis;
            boolean fell = rule != null && rule.getBoolean("fell", false);
            rules.put(material, new Rule(List.copyOf(drops), mature, leaves, ruleMillis, fell));
        }
        if (rules.isEmpty()) {
            logger.warning("Zone '" + id + "' had no usable blocks — skipping it.");
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

    /**
     * A section's {@code regen-seconds} in milliseconds, or the fallback when it has none.
     *
     * <p>Any number is accepted, so {@code 1.5} means a second and a half; the old integer-only read
     * ignored it without a word and used the zone's timer instead.
     */
    private static long millis(ConfigurationSection sec, long fallback, String zone, Logger logger) {
        if (!sec.isSet("regen-seconds")) {
            return fallback;
        }
        Object raw = sec.get("regen-seconds");
        if (!(raw instanceof Number number)) {
            logger.warning("Zone '" + zone + "': regen-seconds '" + raw + "' isn't a number — using "
                    + (fallback / 1000.0) + "s.");
            return fallback;
        }
        return Math.max(1000L, Math.round(number.doubleValue() * 1000));
    }

    /**
     * Whether this block is a crop, whose age means ripeness.
     *
     * <p>Decides the {@code require-mature} default, and whether a harvest leaves a bare stem behind.
     * Plants that grow as a stack are deliberately not crops: their age only counts toward the next
     * block, so most of a ripe sugar cane stands at age 0.
     */
    public static boolean isCrop(Material material) {
        return Tag.CROPS.isTagged(material) || material == Material.NETHER_WART
                || material == Material.COCOA || material == Material.SWEET_BERRY_BUSH;
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
