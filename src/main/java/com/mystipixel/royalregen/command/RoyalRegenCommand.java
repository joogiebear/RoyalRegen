package com.mystipixel.royalregen.command;

import com.mystipixel.royalregen.RoyalRegenPlugin;
import com.mystipixel.royalregen.Zone;
import com.mystipixel.royalregen.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** {@code /royalregen reload|status} — admin tools. */
public final class RoyalRegenCommand implements CommandExecutor, TabCompleter {

    private final RoyalRegenPlugin plugin;

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
                            + "&7 — " + zone.blockCount() + " block type(s), "
                            + (zone.regenMillis() / 1000) + "s regen"));
                }
            }
            default -> sender.sendMessage(Text.chat("&cUsage: &e/" + label + " reload|status"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("royalregen.admin")) {
            return List.of("reload", "status").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
