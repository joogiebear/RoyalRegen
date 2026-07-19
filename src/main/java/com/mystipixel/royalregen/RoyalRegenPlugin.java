package com.mystipixel.royalregen;

import com.mystipixel.royalregen.command.RoyalRegenCommand;
import com.mystipixel.royalregen.message.MessageManager;
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

    private final List<Zone> zones = new ArrayList<>();
    private RegenService regen;
    private MessageManager messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messages = new MessageManager(this);
        this.regen = new RegenService(this);
        reloadZones();

        getServer().getPluginManager().registerEvents(new RegenListener(this), this);

        RoyalRegenCommand command = new RoyalRegenCommand(this);
        if (getCommand("royalregen") != null) {
            getCommand("royalregen").setExecutor(command);
            getCommand("royalregen").setTabCompleter(command);
        }

        // One second is plenty: regen delays are measured in tens of seconds, and this walks only the
        // front of the queue rather than every pending block.
        getServer().getScheduler().runTaskTimer(this, regen::tick, 20L, 20L);

        getLogger().info("RoyalRegen enabled — " + zones.size() + " zone(s).");
    }

    @Override
    public void onDisable() {
        if (regen != null) {
            int restored = regen.restoreAll();
            if (restored > 0) {
                getLogger().info("Restored " + restored + " harvested block(s) before shutdown.");
            }
        }
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

    public MessageManager messages() {
        return messages;
    }
}
