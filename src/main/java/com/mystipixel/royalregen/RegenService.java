package com.mystipixel.royalregen;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Remembers what a harvested block used to be and puts it back.
 *
 * <p>Restores are kept in memory and scanned once a second. Insertion order is NOT due order — a
 * block's rule can set its own regen time, so a slow block harvested first must not hold up a fast
 * one harvested after it.
 *
 * <p>Everything here runs on the server thread.
 */
public final class RegenService {

    private record Pending(BlockData original, long dueAt) {
    }

    /**
     * Where a pending block is, by world <em>name</em>.
     *
     * <p>Not a {@code Location}: a Location holds the world itself, so a row for a world that is not
     * loaded yet (Multiverse and friends load theirs after this plugin starts) could not be kept at
     * all, and one whose world was unloaded later threw "World unloaded" on every restore tick. A name
     * waits until the world is there.
     */
    private record Spot(String world, int x, int y, int z) {
        static Spot of(Block block) {
            return new Spot(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    private final RoyalRegenPlugin plugin;
    private final Map<Spot, Pending> pending = new LinkedHashMap<>();

    /** Set on every mutation of {@link #pending}; the write-behind save only touches disk when true. */
    private boolean dirty;

    public RegenService(RoyalRegenPlugin plugin) {
        this.plugin = plugin;
    }

    public int pendingCount() {
        return pending.size();
    }

    /** True when this block is already waiting to come back, so it can't be harvested twice. */
    public boolean isPending(Block block) {
        return pending.containsKey(Spot.of(block));
    }

    /**
     * Take the block now and schedule its return.
     *
     * <p>Crops are reset to age 0 rather than left as air: a bare stem reads as "harvested, growing
     * back", where a hole reads as something a player dug out of the farm. Only crops — an age-0
     * sugar cane is a full-size cane, so resetting one would put the harvested block straight back.
     */
    public void harvest(Block block, long regenMillis) {
        Spot key = Spot.of(block);
        if (pending.containsKey(key)) {
            return;
        }
        BlockData original = block.getBlockData();
        pending.put(key, new Pending(original, System.currentTimeMillis() + regenMillis));
        dirty = true;

        // A tick later, because the break event is left uncancelled so other plugins can see it —
        // which means the server sets this block to air immediately after this method returns, and
        // anything written now would simply be overwritten.
        if (original instanceof Ageable && Zone.isCrop(original.getMaterial())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!block.getType().isAir()) {
                    return;                     // something else already refilled it; leave it alone
                }
                BlockData reset = original.clone();
                ((Ageable) reset).setAge(0);
                block.setBlockData(reset, false);
            });
        }
    }

    /** Put back everything that is due. Called on a timer. */
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
            // Only forget it once it is actually back. A block in an unloaded chunk is retried on a
            // later tick; dropping it here would leave a permanent hole in the farm.
            if (restore(entry.getKey(), entry.getValue().original())) {
                it.remove();
                dirty = true;
            }
        }
    }

    /**
     * Put everything back at once, whatever its timer says.
     *
     * <p>Called on shutdown so a restart never leaves a farm full of holes. Without it, whatever was
     * harvested in the last few seconds before a stop would stay bare permanently.
     *
     * <p>A block whose world is not loaded cannot be put back now, so it stays pending and is written
     * to pending.yml for the next start, rather than being forgotten along with everything else.
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
            // Shutdown is the last chance, so this one does load the chunk if it has to — a briefly
            // loaded chunk costs nothing next to a farm that never grows back.
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

    // ── crash persistence ────────────────────────────────────────────────────────

    /**
     * Write the pending map to disk if it changed since the last save.
     *
     * <p>{@link #restoreAll} covers a clean stop, but a crash used to take the map with it — every
     * harvested block whose timer died with the process became a permanent hole in the farm. This is
     * the same reasoning as RoyalTrade's escrow file: a few tens of small rows every few seconds is
     * nothing next to a farm that never grows back.
     */
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
            plugin.getLogger().warning("Could not save pending.yml — a crash from here loses "
                    + rows.size() + " pending restore(s): " + e.getMessage());
        }
    }

    /**
     * Load restores left behind by an unclean shutdown, queueing them as already-due so the next
     * tick puts them back (with its usual unloaded-chunk retry). Entries for worlds that are not
     * loaded yet are kept too: the world is often just late, loaded by another plugin after this one
     * starts, and dropping the row would leave that block a permanent hole.
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

    /** Returns false when the world or chunk isn't loaded, so the caller knows to try again later. */
    private boolean restore(Spot at, BlockData original) {
        World world = plugin.getServer().getWorld(at.world());
        if (world == null || !world.isChunkLoaded(at.x() >> 4, at.z() >> 4)) {
            return false;
        }
        world.getBlockAt(at.x(), at.y(), at.z()).setBlockData(original, false);
        return true;
    }
}
