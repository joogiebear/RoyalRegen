package com.mystipixel.royalregen;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Harvesting inside a regen zone, and keeping everything else in it intact.
 */
public final class RegenListener implements Listener {

    private final RoyalRegenPlugin plugin;

    public RegenListener(RoyalRegenPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Harvest a listed block, in a zone, and schedule its return.
     *
     * <p>Runs at {@code HIGHEST} and deliberately does <em>not</em> ignore cancelled events: a
     * protection plugin will already have cancelled this — the hub denies building outright — and the
     * whole point is to carve out an exception for these blocks in these places. Everything not listed
     * stays cancelled, so scenery inside a farm is as protected as it was.
     *
     * <p>The event stays cancelled either way. Rather than letting vanilla break the block, the plugin
     * resets and restores it and hands out the configured drops, so what a harvest is worth is a config
     * decision rather than whatever the block happens to drop.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Zone zone = plugin.zoneAt(block);
        if (zone == null) {
            return;
        }
        Zone.Rule rule = zone.rule(block.getType());
        if (rule == null) {
            return;                                     // not harvestable here — leave it denied
        }
        event.setCancelled(true);                       // we do the work; vanilla does not

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;                                     // building, not farming
        }
        if (plugin.regen().isPending(block)) {
            return;                                     // already harvested, waiting to come back
        }
        if (rule.requireMature() && !isMature(block)) {
            plugin.messages().send(player, "not-grown");
            return;
        }

        plugin.regen().harvest(block, zone.regenMillis());
        for (ItemStack drop : rule.drops()) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.2, 0.5), drop.clone());
        }
    }

    /**
     * Stop farmland being trampled inside a zone.
     *
     * <p>Trampling isn't a break — it arrives as a PHYSICAL interact — so nothing above catches it.
     * Without this, a farm's soil turns to dirt the first time someone runs across it and no amount of
     * block regeneration brings it back, because no block was ever broken.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTrample(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.FARMLAND) {
            return;
        }
        if (plugin.zoneAt(block) != null) {
            event.setCancelled(true);
        }
    }

    private static boolean isMature(Block block) {
        return !(block.getBlockData() instanceof Ageable ageable)
                || ageable.getAge() >= ageable.getMaximumAge();
    }
}
