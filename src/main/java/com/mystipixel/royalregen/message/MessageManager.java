package com.mystipixel.royalregen.message;

import com.mystipixel.royalregen.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Player-facing text from {@code messages.yml}. The jar's copy backs every missing key; blank text
 * silences a message.
 */
public final class MessageManager {

    private final JavaPlugin plugin;
    private volatile YamlConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.messages = loaded;
    }

    /** The text for a key, with replacements given as token/value pairs ({@code "%zones%", "3"}). */
    public String get(String key, String... replacements) {
        return format(messages.getString(key), replacements);
    }

    /** A player message, after the configured prefix. */
    public void send(CommandSender target, String key, String... replacements) {
        String raw = get(key, replacements);
        if (!raw.isBlank()) {
            target.sendMessage(Text.chat(messages.getString("prefix", "") + raw));
        }
    }

    /** Admin command output, which carries its own heading instead of the prefix. */
    public void reply(CommandSender target, String key, String... replacements) {
        String raw = get(key, replacements);
        if (!raw.isBlank()) {
            target.sendMessage(Text.chat(raw));
        }
    }

    public static String format(String raw, String... replacements) {
        if (raw == null) {
            return "";
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        return raw;
    }
}
