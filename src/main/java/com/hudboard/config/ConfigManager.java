package com.hudboard.config;

import com.hudboard.HudBoardPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final HudBoardPlugin plugin;
    private FileConfiguration config;
    private final Map<String, String> runtime = new HashMap<>();

    public ConfigManager(HudBoardPlugin plugin) { this.plugin = plugin; }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    public FileConfiguration raw() { return config; }

    public int refreshInterval() { return config.getInt("refresh-interval", 1); }
    public int maxTilesPerSide() { return Math.max(1, Math.min(10, config.getInt("max-tiles-per-side", 10))); }
    /** v2.4.0: how many blocks in the player's look direction to search
     *  for a matching surface when the targeted block face doesn't fit
     *  the profile (auto-snap). 0 = disabled (use exact target). */
    public int autoSnapMaxBlocks() { return Math.max(0, Math.min(8, config.getInt("auto-snap-max-blocks", 3))); }
    public long placeCooldownMs() { return config.getLong("place-cooldown-ms", 3000L); }
    public boolean autoPlaceOnJoin() { return config.getBoolean("auto-place-on-join", false); }
    public String autoPlaceProfile() { return config.getString("auto-place-profile", "info-hub"); }
    public boolean panelFollow() { return config.getBoolean("panel.follow-viewer", false); }
    /** Distance (blocks) at which a panel is considered "visible" and ticks normally. */
    public int panelViewDistance() { return Math.max(8, config.getInt("panel.view-distance", 48)); }
    public List<String> disabledWorlds() { return config.getStringList("disabled-worlds"); }
    public List<String> disabledRegions() { return config.getStringList("disabled-regions"); }
    public String soundOnPlace() { return config.getString("sound-on-place", "BLOCK_BEACON_ACTIVATE"); }
    public String soundOnRemove() { return config.getString("sound-on-remove", "BLOCK_BEACON_DEACTIVATE"); }
    /** Sound played on every GUI button click. Empty / whitespace = off. */
    public String soundMenuClick() { return config.getString("sound-menu-click", "UI_BUTTON_CLICK"); }
    /** Sound played when a menu rejects an action (e.g. disabled slot, no
     *  data points). Falls back to ENCHANT_THORNS_HIT unless configured. */
    public String soundMenuDeny() { return config.getString("sound-menu-deny", "UI_BUTTON_CLICK"); }
    /** Sound played when a menu opens. Empty = off. */
    public String soundMenuOpen() { return config.getString("sound-menu-open", ""); }
    /** v2.4.0: how often (minutes) to snapshot groups.yml + manual-keys.yml
     *  + profiles/ into backups/. Minimum 5, default 30. Set to a huge
     *  number to effectively disable. */
    public long backupIntervalMinutes() { return config.getLong("backup-interval-minutes", 30L); }
    /** v2.6.0: GitHub URL printed in the startup banner. Empty = link hidden. */
    public String githubUrl() { return config.getString("github-url", ""); }

    /** Runtime config (set by other plugins / commands, not in yml). */
    public void setRuntime(String key, String value) { runtime.put(key, value); }
    public String getRuntime(String key, String def) { return runtime.getOrDefault(key, def); }

    public File placedFile() { return new File(plugin.getDataFolder(), "placed.yml"); }
    public YamlConfiguration loadPlaced() {
        if (!placedFile().exists()) return new YamlConfiguration();
        return YamlConfiguration.loadConfiguration(placedFile());
    }
    public void savePlaced(YamlConfiguration yml) {
        try { yml.save(placedFile()); } catch (Exception e) { /* ignore */ }
    }
}
