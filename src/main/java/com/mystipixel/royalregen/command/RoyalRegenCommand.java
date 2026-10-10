package com.mystipixel.royalregen.command;

import com.mystipixel.royalregen.RoyalRegenPlugin;
import com.mystipixel.royalregen.Zone;
import com.mystipixel.royalregen.util.Text;
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
            sender.sendMessage(Text.chat("&cYou don't have permission to do that."));
            return true;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (action) {
            case "reload" -> {
                plugin.reloadZones();
                plugin.messages().reload();
                plugin.messages().send(sender, "reloaded", "%zones%", String.valueOf(plugin.zones().size()));
            }
            case "status" -> {
                sender.sendMessage(Text.chat("&6RoyalRegen &8» &f" + plugin.zones().size()
                        + "&7 zone(s), &f" + plugin.regen().pendingCount() + "&7 block(s) waiting to return"));
                for (Zone zone : plugin.zones()) {
                    sender.sendMessage(Text.chat("  &8· &e" + zone.id() + " &7in &f" + zone.world()
                            + "&7: " + zone.blockCount() + " block type(s), "
                            + (zone.regenMillis() / 1000) + "s regen"));
                }
            }
            case "pos1", "pos2" -> corner(sender, action);
            case "create" -> create(sender, args);
            default -> sender.sendMessage(Text.chat(
                    "&cUsage: &e/" + label + " reload|status|pos1|pos2|create <id> [regen-seconds]"));
        }
        return true;
    }

    private void corner(CommandSender sender, String which) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.chat("&cOnly players can set corners."));
            return;
        }
        Location at = player.getLocation();
        (which.equals("pos1") ? pos1 : pos2).put(player.getUniqueId(), at);
        sender.sendMessage(Text.chat("&6RoyalRegen &8» &7Corner &f" + which.charAt(3) + "&7 set to &f"
                + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ()
                + "&7 in &f" + at.getWorld().getName() + "&7."));
    }

    // Writes a zone from the stored corners into config.yml and loads it. The block list is seeded
    // from the block the admin is looking at, with require-mature set for crops.
    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.chat("&cOnly players can create zones."));
            return;
        }
        if (args.length < 2 || !ZONE_ID.matcher(args[1].toLowerCase(Locale.ROOT)).matches()) {
            sender.sendMessage(Text.chat("&cUsage: &e/royalregen create <id> [regen-seconds]"
                    + " &7(id: a-z, 0-9, _ and -)"));
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
                sender.sendMessage(Text.chat("&cconfig.yml doesn't parse right now, so the zone wasn't"
                        + " saved. Fix it first: &e" + broken.getMessage()));
                return;
            }
        }
        plugin.reloadConfig();
        if (plugin.getConfig().isConfigurationSection("zones." + id)) {
            sender.sendMessage(Text.chat("&cA zone called '&e" + id + "&c' already exists in config.yml."));
            return;
        }
        Location a = pos1.get(player.getUniqueId());
        Location b = pos2.get(player.getUniqueId());
        if (a == null || b == null) {
            sender.sendMessage(Text.chat("&cSet both corners first: &e/royalregen pos1 &cand &e/royalregen pos2&c."));
            return;
        }
        if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            sender.sendMessage(Text.chat("&cYour two corners are in different worlds. Set them again."));
            return;
        }
        Block looking = player.getTargetBlockExact(6);
        if (looking == null || looking.getType().isAir() || !looking.getType().isSolid()
                && !(looking.getBlockData() instanceof org.bukkit.block.data.Ageable)) {
            sender.sendMessage(Text.chat("&cLook at the block players should harvest here. It seeds"
                    + " the zone's block list."));
            return;
        }
        int seconds = 45;
        if (args.length >= 3) {
            try {
                seconds = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException bad) {
                sender.sendMessage(Text.chat("&cregen-seconds must be a number."));
                return;
            }
        }

        String base = "zones." + id;
        var cfg = plugin.getConfig();
        cfg.set(base + ".enabled", true);
        cfg.set(base + ".world", a.getWorld().getName());
        cfg.set(base + ".display-name", "&f" + id);
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

        sender.sendMessage(Text.chat("&6RoyalRegen &8» &aZone '&e" + id + "&a' created and live: &f"
                + blockKey + "&a on a " + seconds + "s timer."));
        sender.sendMessage(Text.chat("&7Add more blocks (and drops/felling options) under &fzones."
                + id + ".blocks&7 in config.yml, then &f/royalregen reload&7."));
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
