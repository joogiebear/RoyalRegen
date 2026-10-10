package com.mystipixel.royalregen;

import com.mystipixel.royalregen.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Announces a zone when a player walks into it, and remembers which ones they've found. The first
 * visit gets the discovery title; later visits get a shorter one.
 */
public final class DiscoveryService {

    private final RoyalRegenPlugin plugin;
    private final Map<UUID, String> currentZone = new HashMap<>();
    private final Map<UUID, Set<String>> discovered = new HashMap<>();
    private final File file;
    private boolean dirty;

    public DiscoveryService(RoyalRegenPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "discovered.yml");
        load();
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

    private void load() {
        if (!file.exists()) {
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String key : cfg.getKeys(false)) {
            try {
                discovered.put(UUID.fromString(key), new HashSet<>(cfg.getStringList(key)));
            } catch (IllegalArgumentException notAUuid) {
                plugin.getLogger().warning("discovered.yml has an entry that isn't a player id: " + key);
            }
        }
    }

    /** Called every few seconds and on shutdown; only writes when something new was found. */
    public void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration out = new YamlConfiguration();
        discovered.forEach((id, zones) -> {
            List<String> list = new ArrayList<>(zones);
            out.set(id.toString(), list);
        });
        try {
            out.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save discovered.yml", e);
        }
    }
}
