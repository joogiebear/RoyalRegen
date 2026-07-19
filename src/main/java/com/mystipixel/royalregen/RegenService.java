package com.mystipixel.royalregen;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers what a harvested block used to be and puts it back.
 *
 * <p>Restores are kept in memory in insertion order, which is also due order because every zone uses a
 * fixed delay — so the tick only has to look at the front of the map rather than scanning all of it.
 *
 * <p>Everything here runs on the server thread.
 */
public final class RegenService {

    private record Pending(BlockData original, long dueAt) {
    }

    private final RoyalRegenPlugin plugin;
    private final Map<Location, Pending> pending = new LinkedHashMap<>();

    public RegenService(RoyalRegenPlugin plugin) {
        this.plugin = plugin;
    }

    public int pendingCount() {
        return pending.size();
    }

    /** True when this block is already waiting to come back, so it can't be harvested twice. */
    public boolean isPending(Block block) {
        return pending.containsKey(block.getLocation());
    }

    /**
     * Take the block now and schedule its return.
     *
     * <p>Crops are reset to age 0 rather than removed: a bare stem reads as "harvested, growing back",
     * where air reads as a hole someone dug in the farm.
     */
    public void harvest(Block block, long regenMillis) {
        Location key = block.getLocation();
        if (pending.containsKey(key)) {
            return;
        }
        BlockData original = block.getBlockData();
        pending.put(key, new Pending(original, System.currentTimeMillis() + regenMillis));

        BlockData reset = original.clone();
        if (reset instanceof Ageable ageable) {
            ageable.setAge(0);
            block.setBlockData(ageable, false);
        } else {
            block.setType(org.bukkit.Material.AIR, false);
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
                break;                       // insertion order is due order — nothing later is ready
            }
            // Only forget it once it is actually back. A block in an unloaded chunk is retried on a
            // later tick; dropping it here would leave a permanent hole in the farm.
            if (restore(entry.getKey(), entry.getValue().original())) {
                it.remove();
            }
        }
    }

    /**
     * Put everything back at once, whatever its timer says.
     *
     * <p>Called on shutdown so a restart never leaves a farm full of holes. Without it, whatever was
     * harvested in the last few seconds before a stop would stay bare permanently.
     */
    public int restoreAll() {
        int n = 0;
        for (var entry : pending.entrySet()) {
            // Shutdown is the last chance, so this one does load the chunk if it has to — a briefly
            // loaded chunk costs nothing next to a farm that never grows back.
            try {
                entry.getKey().getBlock().setBlockData(entry.getValue().original(), false);
                n++;
            } catch (Exception e) {
                plugin.getLogger().warning("Could not restore a block at " + entry.getKey()
                        + " during shutdown: " + e.getMessage());
            }
        }
        pending.clear();
        return n;
    }

    /** Returns false when the chunk isn't loaded, so the caller knows to try again later. */
    private boolean restore(Location location, BlockData original) {
        if (location.getWorld() == null || !location.getWorld().isChunkLoaded(
                location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }
        location.getBlock().setBlockData(original, false);
        return true;
    }
}
