package com.mystipixel.royalregen.message;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Every key the code reads must ship in messages.yml, so a server file that predates a key still shows text.
class MessageDefaultsTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Pattern MESSAGE_CALL = Pattern.compile(
            "\\bmessages(?:\\(\\))?\\s*\\.\\s*(get|send|reply)\\s*\\(\\s*(?:[\\w.()]+\\s*,\\s*)?(\"[^\"]*\"|[^,)]+)");
    // RegenListener.tell(player, key) forwards its key to send
    private static final Pattern TELL_CALL = Pattern.compile("\\btell\\s*\\(\\s*\\w+\\s*,\\s*(\"[^\"]*\"|[^,)]+)");

    @Test
    void everyKeyReadInCodeShipsInMessagesYml() throws IOException {
        YamlConfiguration shipped = load();
        List<String> problems = new ArrayList<>();
        int found = 0;
        for (Path file : sources()) {
            String source = Files.readString(file);
            for (Pattern pattern : List.of(MESSAGE_CALL, TELL_CALL)) {
                Matcher m = pattern.matcher(source);
                while (m.find()) {
                    String where = file.getFileName() + ":" + lineOf(source, m.start());
                    String key = m.group(m.groupCount()).strip();
                    if (pattern == MESSAGE_CALL && key.equals("key") && source.contains("private void tell(")) {
                        continue;
                    }
                    found++;
                    if (!key.startsWith("\"")) {
                        problems.add(where + ": message key must be a string literal, not " + key);
                    } else if (!shipped.isString(key.substring(1, key.length() - 1))) {
                        problems.add(where + ": " + key + " is not in messages.yml");
                    }
                }
            }
        }
        assertTrue(found > 25, "found only " + found + " message reads; is the scan still matching?");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void messagesYmlHasNoDuplicateKeys() throws IOException {
        // YAML keeps only the last of two equal keys, silently dropping the first
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String line : resource().split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            path.add(trimmed.substring(0, colon));
            indents.add(indent);
            String key = String.join(".", path);
            if (!seen.add(key)) {
                duplicates.add(key);
            }
        }
        assertTrue(duplicates.isEmpty(), "duplicate keys in messages.yml: " + duplicates);
    }

    @Test
    void bundledDefaultsRenderTheTextTheCodeBuiltBefore() throws IOException {
        YamlConfiguration shipped = load();
        String id = "quarry";
        assertEquals("&f" + id, render(shipped, "zone-defaults.display-name", "%id%", id));
        String display = "&7Quarry";
        assertEquals("&6" + display, render(shipped, "zone-defaults.discovery-title", "%name%", display));
        assertEquals("&7Discovered", render(shipped, "zone-defaults.discovery-subtitle"));
        assertEquals("&6RoyalRegen &8» &f" + 2 + "&7 zone(s), &f" + 14 + "&7 block(s) waiting to return",
                render(shipped, "admin.status.header", "%zones%", "2", "%pending%", "14"));
        assertEquals("  &8· &e" + id + " &7in &f" + "world" + "&7: " + 3 + " block type(s), " + 45 + "s regen",
                render(shipped, "admin.status.zone", "%zone%", id, "%world%", "world", "%blocks%", "3",
                        "%seconds%", "45"));
        assertEquals("&6RoyalRegen &8» &7Corner &f" + '1' + "&7 set to &f" + 10 + ", " + 64 + ", " + -3
                        + "&7 in &f" + "world" + "&7.",
                render(shipped, "admin.corner.set", "%corner%", "1", "%x%", "10", "%y%", "64", "%z%", "-3",
                        "%world%", "world"));
        assertEquals("&cUsage: &e/" + "rr" + " reload|status|pos1|pos2|create <id> [regen-seconds]",
                render(shipped, "admin.usage", "%label%", "rr"));
        assertEquals("&6RoyalRegen &8» &aZone '&e" + id + "&a' created and live: &f" + "minecraft:stone"
                        + "&a on a " + 45 + "s timer.",
                render(shipped, "admin.create.done", "%zone%", id, "%block%", "minecraft:stone", "%seconds%", "45"));
        assertEquals("&7Add more blocks (and drops/felling options) under &fzones." + id
                        + ".blocks&7 in config.yml, then &f/royalregen reload&7.",
                render(shipped, "admin.create.hint", "%zone%", id));
    }

    private static String render(YamlConfiguration shipped, String key, String... replacements) {
        return MessageManager.format(shipped.getString(key), replacements);
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.endsWith("MessageManager.java"))
                    .toList();
        }
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String resource() throws IOException {
        try (InputStream in = MessageDefaultsTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(in, "messages.yml");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration load() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(resource());
        } catch (InvalidConfigurationException e) {
            throw new IOException(e);
        }
        return yaml;
    }
}
