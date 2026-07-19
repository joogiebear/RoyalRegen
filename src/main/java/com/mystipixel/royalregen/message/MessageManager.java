package com.mystipixel.royalregen.message;

import com.mystipixel.royalregen.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

/**
 * Player-facing text from {@code messages.yml}. Every key has an inline fallback, so the file is
 * optional and a missing key never sends an empty message; blank text silences one entirely.
 */
public final class MessageManager {

    private static final Map<String, String> DEFAULTS = Map.of(
            "prefix", "",
            "not-grown", "&7That isn't ready to harvest yet.",
            "reloaded", "&aRoyalRegen reloaded &f%zones%&a zone(s).");

    private final JavaPlugin plugin;
    private FileConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        if (!new File(plugin.getDataFolder(), "messages.yml").exists()) {
            plugin.saveResource("messages.yml", false);
        }
        reload();
    }

    public void reload() {
        this.messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
    }

    public void send(CommandSender target, String key, String... replacements) {
        String raw = messages.getString(key, DEFAULTS.getOrDefault(key, ""));
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        target.sendMessage(Text.chat(messages.getString("prefix", "") + raw));
    }
}
