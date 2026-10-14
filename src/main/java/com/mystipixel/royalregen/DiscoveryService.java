package com.mystipixel.royalregen;

import com.mystipixel.royalregen.util.DataFiles;
import com.mystipixel.royalregen.util.SnapshotWriter;
import com.mystipixel.royalregen.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Announces a zone when a player walks into it, and remembers which ones they've found. The first
 * visit gets the discovery title; later visits get a shorter one.
 */
public final class DiscoveryService {

    private final RoyalRegenPlugin plugin;
    private final Map<UUID, String> currentZone = new HashMap<>();
    private final Map<UUID, Set<String>> discovered = new HashMap<>();
    // each player's zones as saved, re-sorted only when that player finds a new one
    private final Map<UUID, List<String>> sorted = new HashMap<>();
    private final SnapshotWriter<Map<String, List<String>>> writer;
    private boolean dirty;

    public DiscoveryService(RoyalRegenPlugin plugin) {
        this.plugin = plugin;
        File file = new File(plugin.getDataFolder(), "discovered.yml");
        discovered.putAll(readEntries(file, plugin.getLogger()));
        this.writer = new SnapshotWriter<>(file, plugin.getLogger(), DiscoveryService::serialise);
    }

    /** Called as a player changes block, joins or teleports. Cheap when the zone hasn't changed. */
    public void update(Player player, Location at) {
        Zone zone = plugin.zoneAt(at);
        String now = zone == null ? null : zone.id();
        String before = currentZone.get(player.getUniqueId());
        if (java.util.Objects.equals(now, before)) {
            return;
        }
        if (now == null) {
            currentZone.remove(player.getUniqueId());
            return;
        }
        currentZone.put(player.getUniqueId(), now);
        announce(player, zone);
    }

    private void announce(Player player, Zone zone) {
        if (!zone.announce()) {
            return;
        }
        Set<String> found = discovered.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>());
        boolean first = found.add(zone.id());
        if (first) {
            sorted.remove(player.getUniqueId());
            dirty = true;
        }
        String heading = first ? zone.discoveryTitle() : zone.displayName();
        String sub = first ? zone.discoverySubtitle() : "";
        player.showTitle(Title.title(
                Text.chat(heading),
                Text.chat(sub),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(first ? 2500 : 1200),
                        Duration.ofMillis(500))));
    }

    /** How many zones this player has found, for a future progress display. */
    public int discoveredCount(UUID player) {
        Set<String> found = discovered.get(player);
        return found == null ? 0 : found.size();
    }

    public void forget(Player player) {
        currentZone.remove(player.getUniqueId());       // discoveries are kept
    }

    // an unreadable file is copied aside before the next save replaces it, and nothing from it is loaded
    static Map<UUID, Set<String>> readEntries(File file, Logger logger) {
        if (!file.exists()) {
            return Map.of();
        }
        String problem;
        try {
            // the old in-place save left an empty file when the server died mid-write; a real save never does
            if (file.length() == 0) {
                problem = "it is empty";
            } else {
                YamlConfiguration yaml = DataFiles.load(file);
                Map<UUID, Set<String>> entries = new HashMap<>();
                for (String key : yaml.getKeys(false)) {
                    try {
                        entries.put(UUID.fromString(key), new HashSet<>(yaml.getStringList(key)));
                    } catch (IllegalArgumentException notAUuid) {
                        logger.warning(file.getName() + " has an entry that isn't a player id: " + key);
                    }
                }
                return entries;
            }
        } catch (IOException | InvalidConfigurationException e) {
            problem = "it could not be read (" + e.getMessage() + ")";
        }
        try {
            File copy = DataFiles.keepCorruptCopy(file);
            logger.severe(file.getName() + " is damaged: " + problem + ". No discoveries were loaded, so players"
                    + " will see the first-visit title again. The damaged file was kept as " + copy.getName() + ".");
        } catch (IOException copyFailed) {
            logger.log(Level.SEVERE, file.getName() + " is damaged: " + problem + ". No discoveries were loaded,"
                    + " and a copy could not be kept, so the next save will overwrite it. Back it up now if you"
                    + " want to recover it by hand.", copyFailed);
        }
        return Map.of();
    }

    // sorted lists, so an unchanged map compares equal and the writer skips it
    private Map<String, List<String>> snapshot() {
        Map<String, List<String>> copy = new HashMap<>(discovered.size() * 2);
        discovered.forEach((id, zones) -> copy.put(id.toString(),
                sorted.computeIfAbsent(id, unused -> zones.stream().sorted().toList())));
        return Map.copyOf(copy);
    }

    static String serialise(Map<String, List<String>> snapshot) {
        if (snapshot.isEmpty()) {
            return "{}\n";                     // an empty file reads as damaged on the next start
        }
        YamlConfiguration out = new YamlConfiguration();
        new TreeMap<>(snapshot).forEach(out::set);
        return out.saveToString();
    }

    /** Called every few seconds; hands a snapshot to the background writer when something new was found. */
    public void save() {
        if (!dirty && !writer.lastWriteFailed()) {
            return;
        }
        writer.submit(snapshot());
        dirty = false;
    }

    /** Write discovered.yml synchronously and stop the writer. Called on shutdown. */
    public void close() {
        writer.close(snapshot());
    }
}
