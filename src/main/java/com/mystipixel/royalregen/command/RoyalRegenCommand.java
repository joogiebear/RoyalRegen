package com.mystipixel.royalregen.command;

import com.mystipixel.royalregen.RoyalRegenPlugin;
import com.mystipixel.royalregen.Zone;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** {@code /royalregen reload|status|pos1|pos2|create}: admin tools, including in-game zone creation. */
public final class RoyalRegenCommand implements CommandExecutor, TabCompleter, Listener {

    // zone ids become config keys
    private static final Pattern ZONE_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final RoyalRegenPlugin plugin;
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public RoyalRegenCommand(RoyalRegenPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!sender.hasPermission("royalregen.admin")) {
            plugin.messages().reply(sender, "admin.no-permission");
            return true;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (action) {
            case "reload" -> {
                plugin.messages().reload();
                plugin.reloadZones();
                plugin.messages().send(sender, "reloaded", "%zones%", String.valueOf(plugin.zones().size()));
            }
            case "status" -> {
                plugin.messages().reply(sender, "admin.status.header",
                        "%zones%", String.valueOf(plugin.zones().size()),
                        "%pending%", String.valueOf(plugin.regen().pendingCount()));
                for (Zone zone : plugin.zones()) {
                    plugin.messages().reply(sender, "admin.status.zone", "%zone%", zone.id(),
                            "%world%", zone.world(), "%blocks%", String.valueOf(zone.blockCount()),
                            "%seconds%", String.valueOf(zone.regenMillis() / 1000));
                }
            }
            case "pos1", "pos2" -> corner(sender, action);
            case "create" -> create(sender, args);
            default -> plugin.messages().reply(sender, "admin.usage", "%label%", label);
        }
        return true;
    }

    private void corner(CommandSender sender, String which) {
        if (!(sender instanceof Player player)) {
            plugin.messages().reply(sender, "admin.corner.players-only");
            return;
        }
        Location at = player.getLocation();
        (which.equals("pos1") ? pos1 : pos2).put(player.getUniqueId(), at);
        plugin.messages().reply(sender, "admin.corner.set", "%corner%", String.valueOf(which.charAt(3)),
                "%x%", String.valueOf(at.getBlockX()), "%y%", String.valueOf(at.getBlockY()),
                "%z%", String.valueOf(at.getBlockZ()), "%world%", at.getWorld().getName());
    }

    // Writes a zone from the stored corners into config.yml and loads it. The block list is seeded
    // from the block the admin is looking at, with require-mature set for crops.
    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().reply(sender, "admin.create.players-only");
            return;
        }
        if (args.length < 2 || !ZONE_ID.matcher(args[1].toLowerCase(Locale.ROOT)).matches()) {
            plugin.messages().reply(sender, "admin.create.usage");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        // Reload from disk so saving doesn't discard the admin's edits, but parse it on the side
        // first: a broken file reloads as empty, and saving that would wipe every other zone.
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        if (file.exists()) {
            try {
                new org.bukkit.configuration.file.YamlConfiguration().load(file);
            } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException broken) {
                plugin.messages().reply(sender, "admin.create.config-broken",
                        "%error%", String.valueOf(broken.getMessage()));
                return;
            }
        }
        plugin.reloadConfig();
        if (plugin.getConfig().isConfigurationSection("zones." + id)) {
            plugin.messages().reply(sender, "admin.create.exists", "%zone%", id);
            return;
        }
        Location a = pos1.get(player.getUniqueId());
        Location b = pos2.get(player.getUniqueId());
        if (a == null || b == null) {
            plugin.messages().reply(sender, "admin.create.no-corners");
            return;
        }
        if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            plugin.messages().reply(sender, "admin.create.different-worlds");
            return;
        }
        Block looking = player.getTargetBlockExact(6);
        if (looking == null || looking.getType().isAir() || !looking.getType().isSolid()
                && !(looking.getBlockData() instanceof org.bukkit.block.data.Ageable)) {
            plugin.messages().reply(sender, "admin.create.no-target");
            return;
        }
        int seconds = 45;
        if (args.length >= 3) {
            try {
                seconds = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException bad) {
                plugin.messages().reply(sender, "admin.create.bad-seconds");
                return;
            }
        }

        String base = "zones." + id;
        var cfg = plugin.getConfig();
        cfg.set(base + ".enabled", true);
        cfg.set(base + ".world", a.getWorld().getName());
        cfg.set(base + ".display-name", plugin.messages().get("zone-defaults.display-name", "%id%", id));
        cfg.set(base + ".min.x", Math.min(a.getBlockX(), b.getBlockX()));
        cfg.set(base + ".min.y", Math.min(a.getBlockY(), b.getBlockY()));
        cfg.set(base + ".min.z", Math.min(a.getBlockZ(), b.getBlockZ()));
        cfg.set(base + ".max.x", Math.max(a.getBlockX(), b.getBlockX()));
        cfg.set(base + ".max.y", Math.max(a.getBlockY(), b.getBlockY()));
        cfg.set(base + ".max.z", Math.max(a.getBlockZ(), b.getBlockZ()));
        cfg.set(base + ".regen-seconds", seconds);
        String blockKey = looking.getType().getKey().toString();
        if (Zone.isCrop(looking.getType())) {
            cfg.set(base + ".blocks." + blockKey + ".require-mature", true);
        } else {
            cfg.createSection(base + ".blocks." + blockKey);
        }
        plugin.saveConfig();
        plugin.reloadZones();

        plugin.messages().reply(sender, "admin.create.done", "%zone%", id, "%block%", blockKey,
                "%seconds%", String.valueOf(seconds));
        plugin.messages().reply(sender, "admin.create.hint", "%zone%", id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pos1.remove(event.getPlayer().getUniqueId());
        pos2.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("royalregen.admin")) {
            return List.of("reload", "status", "pos1", "pos2", "create").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
