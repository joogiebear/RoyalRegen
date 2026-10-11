package com.mystipixel.royalregen;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Brushable;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

// Inside a zone this plugin allows the listed blocks and denies everything else. With
// override-protection false (the default) the world's protection is opened for the zone and nothing
// cancels a harvest; with true onBreak revives the protection plugin's cancellation at HIGHEST in
// cornered zones, which also overrides any other plugin's cancellation there.
public final class RegenListener implements Listener {

    // generous: hand-built trees are large, and the search stops at the first leaf
    private static final int TREE_SCAN_LIMIT = 512;

    // breaking one pops every block above it, and those never fire a break event of their own
    private static final Set<Material> STACKED = EnumSet.of(Material.SUGAR_CANE, Material.CACTUS,
            Material.BAMBOO, Material.KELP, Material.KELP_PLANT,
            Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT);

    /** Lets a player edit a zone's protected blocks without switching gamemode. */
    public static final String BYPASS = "royalregen.bypass";

    // EcoItems stores the item's id under ecoitems:item
    private static final NamespacedKey ECOITEMS_TAG = new NamespacedKey("ecoitems", "item");

    private final RoyalRegenPlugin plugin;

    // set while we break felled logs ourselves so they don't each fell a tree; main thread only
    private boolean felling;

    public RegenListener(RoyalRegenPlugin plugin) {
        this.plugin = plugin;
    }

    // LOWEST so refusals land before skills, jobs and quests (around NORMAL) award progress for a
    // break that never happens
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreakDeny(BlockBreakEvent event) {
        Block block = event.getBlock();
        Zone zone = plugin.zoneAt(block);
        if (zone == null) {
            return;
        }
        Player player = event.getPlayer();
        if (editing(player)) {
            return;
        }

        Zone.Rule rule = zone.rule(block.getType());
        if (rule == null) {
            // the world's protection may be opened for this area, so we do the denying
            event.setCancelled(true);
            tell(player, "not-harvestable");
            return;
        }
        if (plugin.regen().isPending(block)) {
            event.setCancelled(true);
            return;
        }
        if (rule.requireMature() && !isMature(block)) {
            event.setCancelled(true);
            tell(player, "not-grown");
            return;
        }
        if (rule.requireLeaves() && !partOfTree(block)) {
            event.setCancelled(true);
            tell(player, "not-a-tree");
        }
    }

    // item frames and paintings are entities, so onPlace never sees them
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || editing(player)) {
            return;
        }
        if (plugin.zoneAt(event.getEntity().getLocation().getBlock()) != null) {
            event.setCancelled(true);
            plugin.messages().send(player, "no-building");
        }
    }

    // WorldGuard has no armour stand flag, so not placing them is their only protection. Boats and
    // minecarts also come through here and are deliberately allowed.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getEntityType() != EntityType.ARMOR_STAND) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || editing(player)) {
            return;
        }
        if (plugin.zoneAt(event.getBlock()) != null) {
            event.setCancelled(true);
            plugin.messages().send(player, "no-building");
        }
    }

    // silent for logs a felling breaks on the player's behalf, or a tree sends a line per refused log
    private void tell(Player player, String key) {
        if (!felling) {
            plugin.messages().send(player, key);
        }
    }

    private static boolean editing(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || player.hasPermission(BYPASS);
    }

    // A few logs per tick: a whole tree in one tick renders as one distorted crack. Each log is
    // broken through the player so a real BlockBreakEvent fires and skills, jobs and regen all see it.
    private void fellTree(Player player, Zone zone, Block origin) {
        String permission = plugin.getConfig().getString("felling.permission", "royalregen.fell");
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            return;
        }
        int limit = Math.max(1, plugin.getConfig().getInt("felling.limit", 250));
        int perTick = Math.max(1, plugin.getConfig().getInt("felling.per-tick", 6));

        List<Block> logs = connectedLogs(zone, origin, limit);
        if (logs.isEmpty()) {
            return;
        }

        new BukkitRunnable() {
            private int index = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                int broken = 0;
                while (index < logs.size() && broken < perTick) {
                    Block next = logs.get(index++);
                    if (!Tag.LOGS.isTagged(next.getType())) {
                        continue;
                    }
                    felling = true;
                    try {
                        player.breakBlock(next);
                    } finally {
                        felling = false;
                    }
                    broken++;
                }
                if (index >= logs.size()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // Upward and sideways, nearest first, origin excluded. Stops at the zone edge: a log past it
    // would never come back.
    private static List<Block> connectedLogs(Zone zone, Block origin, int limit) {
        List<Block> found = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);

        while (!queue.isEmpty() && found.size() < limit) {
            Block current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Block next = current.getRelative(dx, dy, dz);
                        if (Tag.LOGS.isTagged(next.getType()) && zone.contains(next) && seen.add(next)) {
                            found.add(next);
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return found;
    }

    // A log is part of a tree if connected logs lead up or sideways to leaves. Built trunks lean and
    // branch, so a straight column check fails them. Never searches downward, or a wall would reach
    // the ground and wander. A heuristic: a post under a decorative canopy also counts.
    private boolean partOfTree(Block block) {
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(block);
        seen.add(block);

        int scanned = 0;
        while (!queue.isEmpty() && scanned < TREE_SCAN_LIMIT) {
            Block current = queue.poll();
            scanned++;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Block next = current.getRelative(dx, dy, dz);
                        Material type = next.getType();
                        if (Tag.LEAVES.isTagged(type)) {
                            return true;
                        }
                        // a pending log is air for now but still links the trunk to its canopy
                        if ((Tag.LOGS.isTagged(type) || plugin.regen().isPending(next))
                                && seen.add(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return false;
    }

    // Not ignoreCancelled: with override-protection on, the world's protection has already
    // cancelled the break and this revives it. Every refusal from onBreakDeny is re-checked first,
    // or a refused block would be un-cancelled back into a harvest.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Zone zone = plugin.zoneAt(block);
        if (zone == null) {
            return;
        }
        if (event.isCancelled() && !zone.revivesCancelledBreaks(plugin.overrideProtection())) {
            return;
        }
        Player player = event.getPlayer();
        if (editing(player)) {
            return;
        }
        Zone.Rule rule = zone.rule(block.getType());
        if (rule == null) {
            return;
        }
        if (plugin.regen().isPending(block)) {
            return;
        }
        if (rule.requireMature() && !isMature(block)) {
            return;
        }
        if (rule.requireLeaves() && !partOfTree(block)) {
            return;
        }

        event.setCancelled(false);

        plugin.regen().harvest(block, rule.regenMillis(), rule.placeholder());
        harvestStackAbove(zone, block, rule.regenMillis());

        if (rule.fell() && !felling) {
            fellTree(player, zone, block);
        }
        // Keep vanilla drops unless a list is configured: suppressing them stops BlockDropItemEvent
        // firing, which silently disables Fortune, telekinesis and drop multipliers.
        if (rule.drops().isEmpty()) {
            return;
        }

        event.setDropItems(false);
        for (ItemStack drop : rule.drops()) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.2, 0.5), drop.clone());
        }
    }

    // Paper has no brush event. EntityChangeBlockEvent fires on every dust stage; the finish is the
    // one whose new data is no longer Brushable. It fires even with no loot table, unlike
    // BlockDropItemEvent. MONITOR so a cancelled brush pays nothing; there is no revival here.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrush(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getBlockData() instanceof Brushable) {
            return;
        }
        Block block = event.getBlock();
        if (!(block.getBlockData() instanceof Brushable) || editing(player)) {
            return;
        }
        Zone zone = plugin.zoneAt(block);
        Zone.Rule rule = zone == null ? null : zone.rule(block.getType());
        if (rule == null || rule.brush() == null || plugin.regen().isPending(block)) {
            return;
        }

        payOut(player, block, rule.brush());
        plugin.regen().harvestBrushed(block, rule.regenMillis(), rule.placeholder());
    }

    // cancelling discards the vanilla brush loot (only blocks generated with a loot table have any)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrushDrop(BlockDropItemEvent event) {
        if (!(event.getBlockState() instanceof BrushableBlock) || editing(event.getPlayer())) {
            return;
        }
        Zone zone = plugin.zoneAt(event.getBlock());
        Zone.Rule rule = zone == null ? null : zone.rule(event.getBlockState().getType());
        if (rule != null && rule.brush() != null && rule.brush().replaceVanillaLoot()) {
            event.setCancelled(true);
        }
    }

    private void payOut(Player player, Block block, Brush brush) {
        ItemStack held = player.getActiveItem();
        if (held.getType().isAir()) {
            held = player.getInventory().getItemInMainHand();
        }
        String ecoId = held.hasItemMeta() ? held.getItemMeta().getPersistentDataContainer()
                .get(ECOITEMS_TAG, PersistentDataType.STRING) : null;
        String tool = Brush.toolId(ecoId, held.getType().getKey().toString());
        double bonus = brush.toolBonus(tool);

        Map<String, String> values = Map.of(
                "player", player.getName(), "uuid", player.getUniqueId().toString(),
                "x", String.valueOf(block.getX()), "y", String.valueOf(block.getY()),
                "z", String.valueOf(block.getZ()), "above_y", String.valueOf(block.getY() + 1),
                "world", block.getWorld().getName(), "tool", tool);

        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Brush.Drop drop : brush.drops()) {
            if (Brush.hit(Brush.chance(drop.chance(), drop.bonus(), bonus), random.nextDouble())) {
                run(drop.commands(), values);
            }
        }
        if (brush.rare() != null && Brush.hit(brush.rare().chance(), random.nextDouble())) {
            run(brush.rare().commands(), values);
        }
    }

    private void run(List<String> commands, Map<String, String> values) {
        for (String command : commands) {
            String line = Brush.expand(command, values);
            try {
                if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), line)) {
                    plugin.getLogger().warning("Brush command was not recognised: " + line);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Brush command failed (" + line + "): " + e.getMessage());
            }
        }
    }

    // the blocks above pop on physics without an event, so record them here or only one comes back
    private void harvestStackAbove(Zone zone, Block block, long regenMillis) {
        if (!STACKED.contains(block.getType())) {
            return;
        }
        Block above = block.getRelative(BlockFace.UP);
        while (STACKED.contains(above.getType()) && zone.contains(above)) {
            plugin.regen().harvest(above, regenMillis);
            above = above.getRelative(BlockFace.UP);
        }
    }

    // the area's protection may be opened for harvesting, so building is denied here
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (editing(event.getPlayer())) {
            return;
        }
        if (plugin.zoneAt(event.getBlock()) != null) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "no-building");
        }
    }

    // Buckets aren't BlockPlaceEvents. Poured liquid or removed irrigation pops crops without a
    // break event, so they would never come back.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        denyBucket(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        ItemStack result = event.getItemStack();
        if (result != null && result.getType() == Material.MILK_BUCKET) {
            return;                                      // milking a cow
        }
        denyBucket(event);
    }

    private void denyBucket(PlayerBucketEvent event) {
        if (editing(event.getPlayer())) {
            return;
        }
        if (plugin.zoneAt(event.getBlock()) != null || plugin.zoneAt(event.getBlockClicked()) != null) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "no-building");
        }
    }

    // mob trampling arrives as an entity interact, not the player's PHYSICAL one
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTrample(EntityInteractEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.FARMLAND && plugin.zoneAt(block) != null) {
            event.setCancelled(true);
        }
    }

    // dried farmland pops its crop without a break, so the crop would never come back
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFarmlandDry(BlockFadeEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.FARMLAND && plugin.zoneAt(block) != null) {
            event.setCancelled(true);
        }
    }

    // felled logs leave the canopy unattached; decayed leaves would make the regrown trunk fail
    // require-leaves
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (plugin.zoneAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    // trampling is a PHYSICAL interact, not a break, so regen would never restore the soil
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        plugin.discovery().update(event.getPlayer(), event.getTo());
    }

    // teleports have their own handler list, so onMove never sees them
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        plugin.discovery().update(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.discovery().update(event.getPlayer(), event.getPlayer().getLocation());
    }

    // a passenger's movement doesn't fire a player move
    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                plugin.discovery().update(player, event.getTo());
            }
        }
    }

    private static boolean sameBlock(Location from, Location to) {
        return from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()
                && java.util.Objects.equals(from.getWorld(), to.getWorld());
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
