package com.mystipixel.royalregen;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Brushable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Remembers what a harvested block used to be and puts it back. Main thread only. Insertion order
 * is not due order: regen times are per block.
 */
public final class RegenService {

    private record Pending(BlockData original, long dueAt) {
    }

    // by world name, not Location, so rows survive a world that loads late or is unloaded
    private record Spot(String world, int x, int y, int z) {
        static Spot of(Block block) {
            return new Spot(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    private final RoyalRegenPlugin plugin;
    private final Map<Spot, Pending> pending = new LinkedHashMap<>();

    // set on every change to pending; the write-behind save only touches disk when true
    private boolean dirty;

    public RegenService(RoyalRegenPlugin plugin) {
        this.plugin = plugin;
    }

    public int pendingCount() {
        return pending.size();
    }

    public boolean isPending(Block block) {
        return pending.containsKey(Spot.of(block));
    }

    /** Take the block now and schedule its return. Crops are reset to age 0 rather than left as air. */
    public void harvest(Block block, long regenMillis) {
        harvest(block, regenMillis, null);
    }

    /**
     * As {@link #harvest(Block, long)}, with {@code placeholder} standing in until the block returns,
     * or null to leave it empty. Crops ignore it.
     */
    public void harvest(Block block, long regenMillis, org.bukkit.Material placeholder) {
        Spot key = Spot.of(block);
        if (pending.containsKey(key)) {
            return;
        }
        BlockData original = block.getBlockData();
        pending.put(key, new Pending(original, System.currentTimeMillis() + regenMillis));
        dirty = true;

        // next tick: the server sets the block to air after the break event, overwriting anything now
        if (original instanceof Ageable && Zone.isCrop(original.getMaterial())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!block.getType().isAir()) {
                    return;                     // something else already refilled it
                }
                BlockData reset = original.clone();
                ((Ageable) reset).setAge(0);
                block.setBlockData(reset, false);
            });
        } else if (placeholder != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (block.getType().isAir()) {  // else something already refilled it
                    block.setType(placeholder, false);
                }
            });
        }
    }

    /**
     * A finished brush: schedule the suspicious block's return and stand the placeholder in its place.
     * The block turns into plain sand or gravel rather than air, and is stored with its dusting reset.
     */
    public void harvestBrushed(Block block, long regenMillis, org.bukkit.Material placeholder) {
        Spot key = Spot.of(block);
        if (pending.containsKey(key)) {
            return;
        }
        BlockData original = block.getBlockData().clone();
        if (original instanceof Brushable brushable) {
            brushable.setDusted(0);
        }
        pending.put(key, new Pending(original, System.currentTimeMillis() + regenMillis));
        dirty = true;

        // next tick, once the brush has swapped in the plain form; that form always goes (sand would fall)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (block.getType() != original.getMaterial() && pending.containsKey(key)) {
                block.setType(placeholder != null ? placeholder : org.bukkit.Material.AIR, false);
            }
        });
    }

    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now < entry.getValue().dueAt()) {
                continue;                    // per-block regen times: a later entry can be due sooner
            }
            // only forget it once it is back; unloaded chunks are retried on a later tick
            if (restore(entry.getKey(), entry.getValue().original())) {
                it.remove();
                dirty = true;
            }
        }
    }

    /**
     * Put everything back at once, whatever its timer says. Called on shutdown. Blocks in unloaded
     * worlds stay pending and are written to pending.yml.
     */
    public int restoreAll() {
        int n = 0;
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Spot at = entry.getKey();
            World world = plugin.getServer().getWorld(at.world());
            if (world == null) {
                continue;
            }
            // loads the chunk if needed: shutdown is the last chance
            try {
                world.getBlockAt(at.x(), at.y(), at.z()).setBlockData(entry.getValue().original(), false);
                it.remove();
                n++;
            } catch (Exception e) {
                plugin.getLogger().warning("Could not restore a block at " + at
                        + " during shutdown: " + e.getMessage());
            }
        }
        if (!pending.isEmpty()) {
            plugin.getLogger().info(pending.size() + " pending restore(s) are in worlds that aren't"
                    + " loaded; they are kept in pending.yml for the next start.");
        }
        dirty = true;
        return n;
    }

    /** Write the pending map to disk if it changed since the last save, so a crash loses nothing. */
    public void savePendingIfDirty(java.io.File file) {
        if (!dirty) {
            return;
        }
        org.bukkit.configuration.file.YamlConfiguration out =
                new org.bukkit.configuration.file.YamlConfiguration();
        java.util.List<String> rows = new java.util.ArrayList<>(pending.size());
        for (var entry : pending.entrySet()) {
            Spot at = entry.getKey();
            rows.add(at.world() + "|" + at.x() + "|" + at.y() + "|"
                    + at.z() + "|" + entry.getValue().dueAt() + "|"
                    + entry.getValue().original().getAsString());
        }
        out.set("pending", rows);
        try {
            out.save(file);
            dirty = false;
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not save pending.yml: a crash from here loses "
                    + rows.size() + " pending restore(s): " + e.getMessage());
        }
    }

    /**
     * Load restores left behind by an unclean shutdown. Rows for worlds not loaded yet are kept:
     * another plugin often loads them later.
     */
    public void loadPending(java.io.File file) {
        if (!file.exists()) {
            return;
        }
        int queued = 0;
        Set<String> missingWorlds = new TreeSet<>();
        for (String row : org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
                .getStringList("pending")) {
            String[] parts = row.split("\\|", 6);
            if (parts.length != 6) {
                plugin.getLogger().warning("pending.yml has a malformed row: " + row);
                continue;
            }
            if (plugin.getServer().getWorld(parts[0]) == null) {
                missingWorlds.add(parts[0]);
            }
            try {
                Spot at = new Spot(parts[0], Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                pending.put(at, new Pending(plugin.getServer().createBlockData(parts[5]),
                        Long.parseLong(parts[4])));
                queued++;
            } catch (IllegalArgumentException bad) {
                plugin.getLogger().warning("pending.yml row could not be parsed: " + row);
            }
        }
        if (queued > 0) {
            dirty = true;
            plugin.getLogger().info("Recovered " + queued + " pending restore(s) from an unclean "
                    + "shutdown; they return on their timers (or immediately if already due).");
        }
        if (!missingWorlds.isEmpty()) {
            plugin.getLogger().info("Some recovered restores are in worlds that aren't loaded yet "
                    + missingWorlds + "; they return once those worlds load.");
        }
    }

    // false when the world or chunk isn't loaded, so the caller retries later
    private boolean restore(Spot at, BlockData original) {
        World world = plugin.getServer().getWorld(at.world());
        if (world == null || !world.isChunkLoaded(at.x() >> 4, at.z() >> 4)) {
            return false;
        }
        world.getBlockAt(at.x(), at.y(), at.z()).setBlockData(original, false);
        return true;
    }
}
