package com.hudboard;

import com.hudboard.command.HudBoardCommand;
import com.hudboard.command.HudBoardTabCompleter;
import com.hudboard.config.ConfigManager;
import com.hudboard.data.DataManager;
import com.hudboard.data.providers.PlayerProvider;
import com.hudboard.lang.Lang;
import com.hudboard.listener.MenuListener;
import com.hudboard.listener.PanelListener;
import com.hudboard.menu.DialogInputBridge;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import com.hudboard.listener.PlayerListener;
import com.hudboard.panel.InfoPanelManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class HudBoardPlugin extends JavaPlugin {

    static {
        // Force headless AWT mode before ANY AWT class is touched. This avoids
        // D3DGraphicsDevice.initD3D() hanging on a headless Windows server
        // when we call Graphics2D.getFontMetrics() to measure text.
        System.setProperty("java.awt.headless", "true");
    }

    private static HudBoardPlugin instance;
    private Logger log = getLogger();
    private ConfigManager configManager;
    private Lang lang;
    private DataManager dataManager;
    /** v2.0.1: persistent storage for the admin's manually-typed placeholder keys. */
    private com.hudboard.data.ManualKeysStorage manualKeys;
    private InfoPanelManager panelManager;
    private com.hudboard.groups.PlaceholderGroupManager groupManager;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private HudBoardCommand command;
    private DialogInputBridge dialogBridge;
    /** Single-thread executor for async disk I/O (loading GIFs, etc.). */
    private static java.util.concurrent.ExecutorService asyncExecutor;

    public static HudBoardPlugin get() { return instance; }

    /** Get the shared async executor (lazily created). Used by GifSequence etc. */
    public static java.util.concurrent.Executor getAsyncExecutor() {
        if (asyncExecutor == null || asyncExecutor.isShutdown()) {
            asyncExecutor = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "HudBoard-Async");
                t.setDaemon(true);
                return t;
            });
        }
        return asyncExecutor;
    }

    /** Shutdown the async executor (called from onDisable). */
    public static void shutdownAsyncExecutor() {
        if (asyncExecutor != null) {
            asyncExecutor.shutdownNow();
            asyncExecutor = null;
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        long t0 = System.currentTimeMillis();

        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.configManager.load();
        this.lang = new Lang(this);
        this.lang.load();
        this.dataManager = new DataManager(this);
        this.dataManager.hookPapi();
        this.panelManager = new InfoPanelManager(this);
        this.panelManager.loadAll();
        // Restore per-player total playtime from playtime.yml
        loadPlaytimeFromDisk();
        // Custom placeholder groups (Phase 2.1) — must be loaded AFTER
        // DataManager so refreshDynamicReservations can pull the PAPI id list.
        this.groupManager = new com.hudboard.groups.PlaceholderGroupManager(this);
        this.groupManager.load();
        refreshGroupReservations();

        // v2.0.1: manual placeholder keys the admin typed via the browser
        // dialog. Persisted to manual-keys.yml so they survive restarts.
        this.manualKeys = new com.hudboard.data.ManualKeysStorage(this);
        this.manualKeys.load();

        // v2.6.0: brand banner — printed once at the END of onEnable so
        // the status lines reflect the actual loaded state (PAPI detected,
        // profile count, manual keys, etc.). One multi-line emit; no spam.
        com.hudboard.util.Banner.send(this);

        // Commands
        PluginCommand cmd = getCommand("hudboard");
        if (cmd != null) {
            this.command = new HudBoardCommand(this);
            cmd.setExecutor(this.command);
            cmd.setTabCompleter(new HudBoardTabCompleter(this));
        }

        // Listeners
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PanelListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MenuListener(this), this);
        dialogBridge = new DialogInputBridge(this);

        // When a player joins, force-send the current frame of every placed
        // panel in their world. The Bukkit render tick doesn't auto-fire for
        // newly-created MapViews (the kind we make on server restart), so we
        // have to push the data explicitly via NMS direct.
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @EventHandler
            public void onJoinPush(org.bukkit.event.player.PlayerJoinEvent e) {
                Player p = e.getPlayer();
                Bukkit.getScheduler().runTaskLater(HudBoardPlugin.this, () -> {
                    if (!p.isOnline()) return;
                    for (var inst : panelManager.allPlaced().values()) {
                        if (inst == null || inst.views == null) continue;
                        if (!p.getWorld().getName().equals(inst.world)) continue;
                        for (org.bukkit.map.MapView v : inst.views) {
                            if (v == null) continue;
                            for (var r : v.getRenderers()) {
                                if (r instanceof com.hudboard.panel.InfoPanelRenderer ipr) {
                                    ipr.forceFrameToViewers();
                                }
                            }
                        }
                    }
                }, 5L);
            }
        }, this);

        // Tick: refresh
        Bukkit.getScheduler().runTaskTimer(this, () -> panelManager.tick(), 20L, 20L);

        // 20Hz dedicated GIF animation tick. Bukkit's natural map render cycle
        // is 250-500ms, which is choppy for animated GIFs. This task forces a
        // frame update every 50ms for any panel that has an active GIF viewer.
        if (com.hudboard.nms.MapDirectSender.isAvailable()) {
            Bukkit.getScheduler().runTaskTimer(this, () -> panelManager.tickGifFrames(), 1L, 1L);  // every tick (50ms)
        }

        // Text-animation tick: invalidate the renderer cache every 100ms for
        // any panel that uses a pulse/breathe/blink/rainbow animation. The
        // renderer's phase depends on System.currentTimeMillis(), so without
        // a periodic cache clear the animation appears frozen between map
        // ticks (which happen every 250-500ms).
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (var inst : panelManager.allPlaced().values()) {
                if (inst == null || inst.profile == null) continue;
                if (com.hudboard.panel.InfoPanelRenderer.hasAnyAnimation(inst.profile)) {
                    for (var v : inst.views) {
                        if (v == null) continue;
                        for (var r : v.getRenderers()) {
                            if (r instanceof com.hudboard.panel.InfoPanelRenderer ipr) {
                                ipr.invalidateAll();
                            }
                        }
                    }
                }
            }
        }, 2L, 2L);  // every 100ms (2 ticks)

        // Cache eviction every 5 min
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            // We don't keep per-player state in the renderer anymore, but if we add
            // animated states per-player we'll evict them here.
        }, 20L * 60 * 5, 20L * 60 * 5);

        // v2.4.0: periodic backup of all the YAML files the plugin owns.
        // Copies profiles/, groups.yml, manual-keys.yml into
        // plugins/HudBoard/backups/ with a timestamp suffix. Cheap
        // (single file copy per run) but invaluable when an admin
        // accidentally trashes a profile via /hudboard panel undo / mass
        // edit / corrupted yml.
        // Schedule: every 30 minutes (configurable via backup-interval-minutes)
        long backupMinutes = Math.max(5, getConfigManager().backupIntervalMinutes());
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                java.io.File folder = new java.io.File(getDataFolder(), "backups");
                folder.mkdirs();
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                copyFile(new java.io.File(getDataFolder(), "groups.yml"), new java.io.File(folder, "groups-" + stamp + ".yml"));
                copyFile(new java.io.File(getDataFolder(), "manual-keys.yml"), new java.io.File(folder, "manual-keys-" + stamp + ".yml"));
                // Profile yml files live in panels/<format>/<id>.yml
                java.io.File panels = new java.io.File(getDataFolder(), "panels");
                if (panels.isDirectory()) {
                    for (java.io.File sub : panels.listFiles()) {
                        if (sub.isDirectory()) {
                            for (java.io.File yml : sub.listFiles((d, n) -> n.endsWith(".yml"))) {
                                copyFile(yml, new java.io.File(folder,
                                        sub.getName() + "-" + yml.getName().replace(".yml", "") + "-" + stamp + ".yml"));
                            }
                        }
                    }
                }
                // Retention: keep only the last 10 backups of each pattern to
                // avoid the backup folder growing unbounded.
                cleanupOldBackups(folder, 10);
            } catch (Throwable t) {
                getLogger().warning("[HudBoard] Backup task failed: " + t.getMessage());
            }
        }, 20L * 60 * backupMinutes, 20L * 60 * backupMinutes);

        // Restore placed panels from disk. Worlds and chunks are not necessarily
        // loaded yet during onEnable (especially on first boot), so we defer to
        // a 1-tick-later task. We also schedule periodic retries for any
        // panel that fails to place because the chunk isn't loaded — this
        // runs every 2s for up to 2 minutes, then stops.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (Bukkit.getWorlds().isEmpty()) {
                log.warning("[HudBoard] No worlds loaded yet; placed panels will be restored on the first world load.");
                return;
            }
            loadPlacedFromDisk();
            log.info("[HudBoard] placed panels restored: " + panelManager.allPlaced().size());
            // Retry any panels whose target chunks weren't loaded yet
            int retried = panelManager.retryDeferredPlacements();
            if (retried > 0) log.info("[HudBoard] deferred placements resolved: " + retried);
        }, 1L);

        // Periodic deferred retry: catches panels that were placed in chunks
        // not loaded at startup. Runs every 2s INDEFINITELY (no timeout) —
        // the retry force-loads the target chunk so the panel reattaches
        // even if no player ever visits the area. Also runs the orphan
        // sweep whenever there are no pending placements left.
        int[] retryTask = new int[1];
        retryTask[0] = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            int retried = panelManager.retryDeferredPlacements();
            if (retried > 0) {
                log.info("[HudBoard] deferred retry: " + retried + " panel(s) resolved.");
            }
            // Stop retrying only when there are no more pending placements
            boolean done = panelManager.pendingPlacementsCount() == 0;
            if (done) {
                Bukkit.getScheduler().cancelTask(retryTask[0]);
                int orphans = panelManager.sweepOrphanFrames();
                if (orphans > 0) {
                    log.info("[HudBoard] post-retry orphan sweep: removed " + orphans + " item-frame(s).");
                }
            }
        }, 40L, 40L);  // every 2s starting after 2s

        long ms = System.currentTimeMillis() - t0;
        log.info("[HudBoard] v" + getPluginMeta().getVersion() + " enabled in " + ms + "ms.");
        log.info("[HudBoard] profiles=" + panelManager.getProfileIds().size() + " (placed panels restored on first tick)");
        if (dataManager.hasPapi()) log.info("[HudBoard] PlaceholderAPI hooked.");
    }

    @Override
    public void onDisable() {
        // For each online player, finalize their current session
        for (var p : Bukkit.getOnlinePlayers()) PlayerProvider.onQuit(p);
        savePlaced();
        savePlaytimeToDisk();
        shutdownAsyncExecutor();
        log.info("[HudBoard] Disabled.");
    }

    public void reloadAll() {
        long t0 = System.currentTimeMillis();
        savePlaced();
        configManager.load();
        lang.load();
        panelManager.loadAll();
        // Defer to next tick (item-frames persist in chunk data; we just re-attach
        // to existing ones or spawn fresh if missing).
        Bukkit.getScheduler().runTask(this, () -> {
            loadPlacedFromDisk();
            int retried = panelManager.retryDeferredPlacements();
            long ms = System.currentTimeMillis() - t0;
            log.info("[HudBoard] Reloaded in " + ms + "ms; placed=" + panelManager.allPlaced().size()
                    + (retried > 0 ? " (deferred resolved: " + retried + ")" : ""));
        });
    }

    // ---------- placed persistence ----------

    public void savePlaced() {
        if (configManager == null) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (var e : panelManager.allPlaced().entrySet()) {
            var inst = e.getValue();
            // Safety net: never write a panel whose profile is missing.
            // Otherwise the next startup would warn + skip + leave a dangling entry.
            if (inst == null || inst.profile == null || inst.profile.id == null || inst.profile.id.isBlank()) {
                log.warning("[HudBoard] savePlaced: skipping entry '" + e.getKey() + "' (no profile or profile has no id).");
                continue;
            }
            String path = "panels." + e.getKey();
            yml.set(path + ".profile", inst.profile.id);
            yml.set(path + ".world", inst.world);
            yml.set(path + ".x", inst.x);
            yml.set(path + ".y", inst.y);
            yml.set(path + ".z", inst.z);
            // Always serialize a VALID face. If the instance has a null/blank
            // face (which shouldn't happen but did in a previous build), try
            // to derive it from the live item-frames; if there are none (e.g.
            // chunks unloaded), default to NORTH. Never write "UNKNOWN" or any
            // other invalid value — BlockFace.valueOf() throws on the next load.
            String face = inst.face;
            if (face == null || face.isBlank() || !isValidBlockFace(face)) {
                String derived = panelManager.deriveFaceFromFrames(inst);
                if (derived != null) {
                    log.warning("[HudBoard] Panel '" + e.getKey() + "' had face='"
                            + inst.face + "', derived '" + derived + "' from live item-frames.");
                    face = derived;
                } else {
                    log.warning("[HudBoard] Panel '" + e.getKey() + "' had no live item-frames; defaulting face to NORTH.");
                    face = "NORTH";
                }
            }
            yml.set(path + ".face", face);
            yml.set(path + ".refresh", inst.refreshSec);
        }
        configManager.savePlaced(yml);
    }

    /** Validates a string is one of the 6 legal BlockFace enum values. */
    private static boolean isValidBlockFace(String s) {
        return "NORTH".equals(s) || "SOUTH".equals(s) || "EAST".equals(s)
                || "WEST".equals(s) || "UP".equals(s) || "DOWN".equals(s);
    }

    public void loadPlacedFromDisk() {
        if (configManager == null) return;
        YamlConfiguration yml = configManager.loadPlaced();
        if (!yml.contains("panels")) return;
        Map<String, InfoPanelManager.PlacedData> data = new HashMap<>();
        boolean cleanedAny = false;
        boolean faceRepaired = false;
        for (String name : yml.getConfigurationSection("panels").getKeys(false)) {
            String path = "panels." + name;
            InfoPanelManager.PlacedData d = new InfoPanelManager.PlacedData();
            d.profileId = yml.getString(path + ".profile");
            if (d.profileId == null || d.profileId.isBlank()) {
                // Orphan entry — clean it up so the warning never recurs.
                log.warning("[HudBoard] Placed entry '" + name + "' is missing profile id; removing (corrupt yml entry).");
                yml.set("panels." + name, null);
                cleanedAny = true;
                continue;
            }
            d.world = yml.getString(path + ".world");
            d.x = yml.getInt(path + ".x");
            d.y = yml.getInt(path + ".y");
            d.z = yml.getInt(path + ".z");
            d.face = yml.getString(path + ".face", "NORTH");
            // Repair: a previous build wrote "UNKNOWN" as a sentinel for a
            // missing face, which crashes BlockFace.valueOf() downstream.
            // Try to recover the real face from the live item-frames now
            // (chunks may or may not be loaded, that's fine — if we can't
            // find the frame, the downstream code defaults to NORTH).
            if (!"NORTH".equals(d.face) && !"SOUTH".equals(d.face) && !"EAST".equals(d.face)
                    && !"WEST".equals(d.face) && !"UP".equals(d.face) && !"DOWN".equals(d.face)) {
                log.warning("[HudBoard] Placed entry '" + name + "' has invalid face '"
                        + d.face + "'; will try to recover from live item-frames.");
                d.face = "NORTH";  // registerPlacedFromDisk will try to upgrade from frames
                faceRepaired = true;
            }
            d.refreshSec = yml.getInt(path + ".refresh", 1);
            data.put(name, d);
        }
        // Persist the cleanup so the warning never appears again for the same entry.
        if (cleanedAny || faceRepaired) {
            try { configManager.savePlaced(yml); } catch (Throwable t) {
                log.warning("[HudBoard] Could not save cleaned placed.yml: " + t.getMessage());
            }
        }
        panelManager.registerPlacedFromDisk(data);
    }

    // ---------- playtime persistence ----------

    private File playtimeFile() { return new File(getDataFolder(), "playtime.yml"); }

    private void loadPlaytimeFromDisk() {
        File f = playtimeFile();
        if (!f.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        Map<String, Long> data = new HashMap<>();
        for (String key : yml.getKeys(false)) {
            data.put(key, yml.getLong(key, 0L));
        }
        PlayerProvider.loadPlaytime(data);
        log.info("[HudBoard] Restored playtime for " + data.size() + " players.");
    }

    private void savePlaytimeToDisk() {
        Map<String, Long> snap = PlayerProvider.snapshotPlaytime();
        if (snap.isEmpty()) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (var e : snap.entrySet()) yml.set(e.getKey(), e.getValue());
        try {
            yml.save(playtimeFile());
        } catch (Exception ex) {
            log.warning("[HudBoard] Could not save playtime.yml: " + ex.getMessage());
        }
    }

    // ---------- getters ----------

    public ConfigManager getConfigManager() { return configManager; }
    public Lang getLang() { return lang; }
    public DataManager getDataManager() { return dataManager; }
    /** v2.0.1: accessor for the persistent manual-keys storage. */
    public com.hudboard.data.ManualKeysStorage getManualKeys() { return manualKeys; }
    public InfoPanelManager getPanelManager() { return panelManager; }
    public com.hudboard.groups.PlaceholderGroupManager getGroupManager() { return groupManager; }

    /** Refresh the list of PAPI identifiers considered reserved by the
     *  group manager. Cheap; called from {@code /hudboard reload} and after
     *  the PlaceholderBrowser fetches from PAPI. */
    public void refreshGroupReservations() {
        if (groupManager != null && dataManager != null) {
            groupManager.refreshDynamicReservations(dataManager.discoverPapiIdentifiers());
        }
    }
    public DialogInputBridge getDialogBridge() { return dialogBridge; }
    public Map<UUID, Long> getCooldowns() { return cooldowns; }

    /** Public-API: get the HudBoardAPI singleton. */
    public com.hudboard.api.HudBoardAPI getAPI() {
        return com.hudboard.api.HudBoardAPI.get(this);
    }

    /** v2.4.0: backup helpers. Kept as static so they don't need a plugin
     *  instance — useful for unit tests too. */
    private static void copyFile(java.io.File src, java.io.File dst) {
        if (src == null || !src.exists()) return;
        try {
            java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException ignored) { /* skip individual failures */ }
    }

    private static void cleanupOldBackups(java.io.File folder, int keep) {
        java.util.Map<String, java.util.List<java.io.File>> byPrefix = new java.util.HashMap<>();
        for (java.io.File f : folder.listFiles()) {
            if (!f.isFile() || !f.getName().endsWith(".yml")) continue;
            // Prefix is everything before the last dash before the timestamp.
            String n = f.getName();
            int dash = n.lastIndexOf('-');
            if (dash < 0) continue;
            // Strip the timestamp (yyyyMMdd-HHmmss) → group by name minus stamp.
            String prefix = n.substring(0, dash - 8);  // strip 8-char date
            byPrefix.computeIfAbsent(prefix, k -> new java.util.ArrayList<>()).add(f);
        }
        for (var entry : byPrefix.entrySet()) {
            if (entry.getValue().size() <= keep) continue;
            entry.getValue().sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = keep; i < entry.getValue().size(); i++) {
                entry.getValue().get(i).delete();
            }
        }
    }
}
