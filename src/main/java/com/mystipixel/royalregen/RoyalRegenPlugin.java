package com.mystipixel.royalregen;

import com.mystipixel.royalregen.command.RoyalRegenCommand;
import com.mystipixel.royalregen.message.MessageManager;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Blocks in defined zones can be harvested and come back on a timer.
 *
 * <p>Built for hub farms — a map full of crops that players should be able to work without being able
 * to dismantle. Nothing here is specific to farming though: a zone is a cuboid and a list of blocks, so
 * the same thing serves a mine or a quarry.
 *
 * <p>Deliberately standalone: no profile data, no dependency on the rest of the suite.
 */
public final class RoyalRegenPlugin extends JavaPlugin {

    /** bStats project id. Identifies the plugin, not the server, so it is fixed rather than configurable. */
    private static final int BSTATS_PLUGIN_ID = 33889;

    private final List<Zone> zones = new ArrayList<>();
    private RegenService regen;
    private MessageManager messages;
    private DiscoveryService discovery;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messages = new MessageManager(this);
        this.regen = new RegenService(this);
        this.discovery = new DiscoveryService(this);
        reloadZones();

        getServer().getPluginManager().registerEvents(new RegenListener(this), this);

        RoyalRegenCommand command = new RoyalRegenCommand(this);
        if (getCommand("royalregen") != null) {
            getCommand("royalregen").setExecutor(command);
            getCommand("royalregen").setTabCompleter(command);
        }

        // One second is plenty: regen delays are measured in tens of seconds, and a full scan of the
        // pending map is cheap at the sizes a farm produces.
        getServer().getScheduler().runTaskTimer(this, regen::tick, 20L, 20L);

        // Crash safety: recover restores an unclean shutdown left behind, then keep the file in step
        // (write-behind, only when something changed). restoreAll() still covers a clean stop.
        java.io.File pendingFile = new java.io.File(getDataFolder(), "pending.yml");
        regen.loadPending(pendingFile);
        getServer().getScheduler().runTaskTimer(this, () -> regen.savePendingIfDirty(pendingFile), 100L, 100L);

        setupMetrics();

        if (zones.isEmpty()) {
            // The shipped config is two disabled examples, since coordinates only mean something on
            // the map they were measured on. Say so, rather than looking quietly broken.
            getLogger().info("RoyalRegen enabled — no zones active yet. Set the corners of a zone in "
                    + "config.yml, flip enabled: true, then /royalregen reload.");
        } else {
            getLogger().info("RoyalRegen enabled — " + zones.size() + " zone(s).");
        }
    }

    @Override
    public void onDisable() {
        if (discovery != null) {
            discovery.save();
        }
        if (regen != null) {
            int restored = regen.restoreAll();
            if (restored > 0) {
                getLogger().info("Restored " + restored + " harvested block(s) before shutdown.");
            }
            // The map is empty now; writing it out empties pending.yml so the next start recovers nothing.
            regen.savePendingIfDirty(new java.io.File(getDataFolder(), "pending.yml"));
        }
    }

    /**
     * Anonymous usage reporting via bStats.
     *
     * <p>Server owners who want no reporting disable it globally in plugins/bStats/config.yml, which
     * is the mechanism bStats provides; the id itself is fixed because it names this plugin's project.
     */
    private void setupMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("zone_count", () -> String.valueOf(zones.size())));
        // The shipped config is two disabled examples, so "0 zones" means installed-but-unconfigured
        // rather than in use — worth being able to tell those apart.
        metrics.addCustomChart(new SimplePie("configured", () -> String.valueOf(!zones.isEmpty())));
        metrics.addCustomChart(new SimplePie("discovery_used",
                () -> String.valueOf(zones.stream().anyMatch(Zone::announce))));
    }

    /** Re-read the zones. Invalid entries are skipped with a reason rather than being fatal. */
    public void reloadZones() {
        reloadConfig();
        zones.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("zones");
        if (section == null) {
            getLogger().warning("No 'zones:' section in config.yml — nothing will regenerate.");
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null || !entry.getBoolean("enabled", true)) {
                continue;
            }
            Zone zone = Zone.load(id, entry, getLogger());
            if (zone != null) {
                zones.add(zone);
            }
        }
    }

    /** The zone containing this block, or null. Zones are few, so a scan is cheaper than an index. */
    public Zone zoneAt(Block block) {
        for (Zone zone : zones) {
            if (zone.contains(block)) {
                return zone;
            }
        }
        return null;
    }

    public List<Zone> zones() {
        return zones;
    }

    public RegenService regen() {
        return regen;
    }

    public DiscoveryService discovery() {
        return discovery;
    }

    public MessageManager messages() {
        return messages;
    }
}
