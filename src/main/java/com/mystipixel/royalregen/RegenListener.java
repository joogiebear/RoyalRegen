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
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Harvesting inside a regen zone, and keeping everything else in it intact.
 *
 * <p>Inside a zone this plugin is the authority: it allows the listed blocks and denies everything
 * else itself. That is deliberate. Leaving the world's protection to do the denying meant the break
 * event was already cancelled — and a cancelled break is invisible to every other plugin, so
 * collections, jobs and skills never saw a harvest happen.
 */
public final class RegenListener implements Listener {

    private final RoyalRegenPlugin plugin;

    public RegenListener(RoyalRegenPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Refuse a break that is not a harvest, as early as possible.
     *
     * <p>This runs at {@code LOWEST} rather than alongside the harvest below, because a refusal has
     * to land before anything else reacts to the break. Skills, jobs and quest tasks listen around
     * {@code NORMAL}; denying at {@code HIGHEST} let them award XP for a break that was then
     * cancelled, so a player could stand at any protected block and farm progress off a block that
     * never broke.
     *
     * <p>The three refusals are here together for that reason — a block still coming back, and a crop
     * that has not grown, are equally "not a harvest" and would pay out just the same.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreakDeny(BlockBreakEvent event) {
        Block block = event.getBlock();
        Zone zone = plugin.zoneAt(block);
        if (zone == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;                                      // building, not farming
        }

        Zone.Rule rule = zone.rule(block.getType());
        if (rule == null) {
            // Not harvestable here. This plugin denies it, because the world's protection has been
            // opened up for this area — otherwise a farm would be a hole in the map's protection.
            event.setCancelled(true);
            plugin.messages().send(player, "not-harvestable");
            return;
        }
        if (plugin.regen().isPending(block)) {
            event.setCancelled(true);
            return;                                      // already harvested, waiting to come back
        }
        if (rule.requireMature() && !isMature(block)) {
            event.setCancelled(true);
            plugin.messages().send(player, "not-grown");
        }
    }

    /**
     * Harvest a listed block and schedule its return.
     *
     * <p>The event is left <em>uncancelled</em> so the rest of the server sees a real break. Runs
     * late, and only reaches blocks {@link #onBreakDeny} allowed through — so by here the break is
     * definitely a harvest, and everything that watched it happen was right to.
     *
     * <h2>Why an empty drop list means "leave it alone"</h2>
     *
     * <p>Suppressing vanilla drops does not merely replace them: {@code BlockDropItemEvent} is only
     * fired when a break actually drops something, so turning drops off stops that event happening
     * at all. Every perk that reads or modifies a drop list is then blind — Fortune and its
     * relatives, reforge and talisman drop multipliers, telekinesis, and eco's DropQueue. They do
     * not fail; they find nothing to multiply and quietly do nothing.
     *
     * <p>That is the worst shape a bug can take on a progression server. A player equips a reforge
     * that promises double crops, farms a regen zone, gets exactly what they got before, and there
     * is no error anywhere to explain it.
     *
     * <p>So with no {@code drops:} configured the vanilla pipeline is left completely untouched.
     * Perks work with no integration code because nothing is being intercepted. Crops also get
     * their real seed variance back, which a fixed list cannot express.
     *
     * <p>A configured {@code drops:} list still overrides, for blocks where vanilla is wrong. Be
     * aware that it reintroduces exactly the problem above for that block — the drops are no longer
     * vanilla drops, so nothing multiplies them. Use it where that is the point, not as a throttle;
     * {@code regen-seconds} throttles without lying to the rest of the server.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Zone zone = plugin.zoneAt(block);
        if (zone == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Zone.Rule rule = zone.rule(block.getType());
        if (rule == null) {
            return;                                      // refused already; nothing to harvest
        }

        plugin.regen().harvest(block, zone.regenMillis());
        if (rule.drops().isEmpty()) {
            return;                                      // vanilla drops stand — see above
        }

        event.setDropItems(false);                       // explicit override; vanilla replaced
        for (ItemStack drop : rule.drops()) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.2, 0.5), drop.clone());
        }
    }

    /**
     * Nothing may be placed inside a zone.
     *
     * <p>The area's protection is relaxed so harvesting can work, so building has to be denied here
     * instead — otherwise opening a farm would also open it to being built over.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (plugin.zoneAt(event.getBlock()) != null) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "no-building");
        }
    }

    /**
     * Stop farmland being trampled inside a zone.
     *
     * <p>Trampling isn't a break — it arrives as a PHYSICAL interact — so nothing above catches it.
     * Without this, a farm's soil turns to dirt the first time someone runs across it, and no amount of
     * block regeneration brings it back because no block was ever broken.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block != null && block.getType() == Material.FARMLAND && plugin.zoneAt(block) != null) {
            event.setCancelled(true);
        }
    }

    /** Announce a zone as the player walks into it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Only when they actually change block — this event fires for every look and step otherwise.
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        plugin.discovery().update(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.discovery().forget(event.getPlayer());
    }

    private static boolean isMature(Block block) {
        return !(block.getBlockData() instanceof Ageable ageable)
                || ageable.getAge() >= ageable.getMaximumAge();
    }
}
