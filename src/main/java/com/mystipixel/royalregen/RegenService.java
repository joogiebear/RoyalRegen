package com.mystipixel.royalregen;

import com.mystipixel.royalregen.util.DataFiles;
import com.mystipixel.royalregen.util.SnapshotWriter;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Brushable;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Remembers what a harvested block used to be and puts it back. Main thread only. Insertion order
 * is not due order: regen times are per block.
 */
public final class RegenService {

    // row is the pending.yml line, built once here so the periodic save only copies strings
    private record Pending(BlockData original, long dueAt, String row) {
        static Pending of(Spot at, BlockData original, long dueAt) {
            return new Pending(original, dueAt, at.world() + "|" + at.x() + "|" + at.y() + "|" + at.z()
                    + "|" + dueAt + "|" + original.getAsString());
        }
    }

    // by world name, not Location, so rows survive a world that loads late or is unloaded
    private record Spot(String world, int x, int y, int z) {
        static Spot of(Block block) {
            return new Spot(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    private final RoyalRegenPlugin plugin;
    private final Map<Spot, Pending> pending = new LinkedHashMap<>();

    // set on every change to pending; the write-behind save only snapshots when true
    private boolean dirty;
    private SnapshotWriter<List<String>> writer;

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
        pending.put(key, Pending.of(key, original, System.currentTimeMillis() + regenMillis));
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
        pending.put(key, Pending.of(key, original, System.currentTimeMillis() + regenMillis));
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

    /**
     * Snapshot the pending map if it changed since the last save and hand it to the background writer, so
     * a crash loses nothing. Main thread only; the YAML dump and the disk write happen off it.
     */
    public void savePendingIfDirty() {
        if (writer == null || (!dirty && !writer.lastWriteFailed())) {
            return;
        }
        writer.submit(snapshot());
        dirty = false;
    }

    /** Write the pending map synchronously and stop the writer. Called on shutdown. */
    public void closePending() {
        if (writer != null) {
            writer.close(snapshot());
            writer = null;
        }
    }

    private List<String> snapshot() {
        List<String> rows = new ArrayList<>(pending.size());
        for (Pending entry : pending.values()) {
            rows.add(entry.row());
        }
        return List.copyOf(rows);
    }

    static String serialise(List<String> rows) {
        YamlConfiguration out = new YamlConfiguration();
        out.set("pending", rows);
        return out.saveToString();
    }

    /**
     * Load restores left behind by an unclean shutdown and start the background writer for {@code file}.
     * Rows for worlds not loaded yet are kept: another plugin often loads them later.
     */
    public void loadPending(File file) {
        writer = new SnapshotWriter<>(file, plugin.getLogger(), RegenService::serialise);
        int queued = 0;
        Set<String> missingWorlds = new TreeSet<>();
        for (String row : readRows(file, plugin.getLogger())) {
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
                pending.put(at, Pending.of(at, plugin.getServer().createBlockData(parts[5]),
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

    // an unreadable file is copied aside before the next save replaces it, and its rows are not loaded
    static List<String> readRows(File file, Logger logger) {
        if (!file.exists()) {
            return List.of();
        }
        String problem;
        try {
            // the old in-place save left an empty file when the server died mid-write; a real save never does
            if (file.length() == 0) {
                problem = "it is empty";
            } else {
                return DataFiles.load(file).getStringList("pending");
            }
        } catch (IOException | InvalidConfigurationException e) {
            problem = "it could not be read (" + e.getMessage() + ")";
        }
        try {
            File copy = DataFiles.keepCorruptCopy(file);
            logger.severe(file.getName() + " is damaged: " + problem + ". No pending restores were loaded, so "
                    + "blocks harvested before the last shutdown will not come back on their own. The damaged "
                    + "file was kept as " + copy.getName() + ".");
        } catch (IOException copyFailed) {
            logger.log(Level.SEVERE, file.getName() + " is damaged: " + problem + ". No pending restores were "
                    + "loaded, and a copy could not be kept, so the next save will overwrite it. Back it up now "
                    + "if you want to recover it by hand.", copyFailed);
        }
        return List.of();
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
