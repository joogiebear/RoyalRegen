package com.mystipixel.royalregen;

import com.mystipixel.royalregen.command.RoyalRegenCommand;
import com.mystipixel.royalregen.message.MessageManager;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Blocks in defined zones can be harvested and come back on a timer. Standalone: no dependency on
 * the rest of the suite.
 */
public final class RoyalRegenPlugin extends JavaPlugin {

    // identifies the plugin's bStats project, so it is not configurable
    private static final int BSTATS_PLUGIN_ID = 33889;

    private final List<Zone> zones = new ArrayList<>();
    private RegenService regen;
    private MessageManager messages;
    private DiscoveryService discovery;
    private boolean overrideProtection;

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
        getServer().getPluginManager().registerEvents(command, this);

        // once a second is plenty for regen delays of tens of seconds
        getServer().getScheduler().runTaskTimer(this, regen::tick, 20L, 20L);

        // crash safety: recover what an unclean shutdown left, then write behind on change
        java.io.File pendingFile = new java.io.File(getDataFolder(), "pending.yml");
        regen.loadPending(pendingFile);
        getServer().getScheduler().runTaskTimer(this, regen::savePendingIfDirty, 100L, 100L);
        getServer().getScheduler().runTaskTimer(this, discovery::save, 100L, 100L);

        setupMetrics();

        if (zones.isEmpty()) {
            // the shipped config's zones are disabled examples
            getLogger().info("RoyalRegen enabled, no zones active yet. Set the corners of a zone in "
                    + "config.yml, flip enabled: true, then /royalregen reload.");
        } else {
            getLogger().info("RoyalRegen enabled: " + zones.size() + " zone(s).");
        }
    }

    @Override
    public void onDisable() {
        if (discovery != null) {
            discovery.close();
        }
        if (regen != null) {
            int restored = regen.restoreAll();
            if (restored > 0) {
                getLogger().info("Restored " + restored + " harvested block(s) before shutdown.");
            }
            // only restores for unloaded worlds are left; this keeps them for the next start
            regen.closePending();
        }
    }

    // owners opt out globally in plugins/bStats/config.yml
    private void setupMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("zone_count", () -> String.valueOf(zones.size())));
        // tells installed-but-unconfigured apart from in use
        metrics.addCustomChart(new SimplePie("configured", () -> String.valueOf(!zones.isEmpty())));
        metrics.addCustomChart(new SimplePie("discovery_used",
                () -> String.valueOf(zones.stream().anyMatch(Zone::announce))));
    }

    /** Re-read the zones. Invalid entries are skipped with a reason rather than being fatal. */
    public void reloadZones() {
        reloadConfig();
        overrideProtection = getConfig().getBoolean("override-protection", false);
        zones.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("zones");
        if (section == null) {
            getLogger().warning("No 'zones:' section in config.yml, nothing will regenerate.");
            return;
        }
        Zone.Defaults defaults = new Zone.Defaults(messages.get("zone-defaults.display-name"),
                messages.get("zone-defaults.discovery-title"), messages.get("zone-defaults.discovery-subtitle"));
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null || !entry.getBoolean("enabled", true)) {
                continue;
            }
            Zone zone = Zone.load(id, entry, defaults, getLogger());
            if (zone != null) {
                zones.add(zone);
            }
        }
        String warning = overrideIgnoredWarning(overrideProtection, zones);
        if (warning != null) {
            getLogger().warning(warning);
        }
    }

    static String overrideIgnoredWarning(boolean overrideProtection, List<Zone> zones) {
        if (!overrideProtection) {
            return null;
        }
        List<String> ids = zones.stream().filter(Zone::wholeWorld).map(Zone::id).toList();
        if (ids.isEmpty()) {
            return null;
        }
        return "override-protection is true, but it is ignored for whole-world zone(s) " + String.join(", ", ids)
                + ". A zone without min and max covers islands, claims and minion fields, so breaks other"
                + " plugins cancel there stay cancelled. Give the zone corners, or open your protection"
                + " for it and set override-protection: false.";
    }

    /** The zone containing this block, or null. */
    public Zone zoneAt(Block block) {
        for (Zone zone : zones) {
            if (zone.contains(block)) {
                return zone;
            }
        }
        return null;
    }

    /** The zone containing this location, or null. Doesn't load the chunk to find out. */
    public Zone zoneAt(Location location) {
        for (Zone zone : zones) {
            if (zone.contains(location)) {
                return zone;
            }
        }
        return null;
    }

    public List<Zone> zones() {
        return zones;
    }

    /** Whether a harvest the world's protection cancelled is revived. See {@link RegenListener}. */
    public boolean overrideProtection() {
        return overrideProtection;
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
