package com.hudboard.data;

import com.hudboard.HudBoardPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Persistent storage for placeholder keys the admin typed manually via
 * the browser's slot 31 dialog.
 *
 * <p>Before v2.0.1 these keys lived in a {@code Map<String, List<String>>}
 * field on {@code PlaceholderBrowser}, which meant they vanished on every
 * server restart. On a populated server the admin had typed maybe 8-12
 * manual keys (mostly legacy PAPI hooks like {@code %jobs_top_lvl%},
 * {@code %vip_rank%}, {@code %faction_power%}) — losing them every
 * reboot was the last big papercut of the polish phase.</p>
 *
 * <h2>On-disk format</h2>
 * Persisted to {@code plugins/HudBoard/manual-keys.yml} as
 * {@code Map<String, List<String>>} keyed by source. Full snapshot on
 * every mutation (same pattern as {@code PlaceholderGroupManager}) so
 * the admin can edit the file by hand if they want.
 *
 * <pre>{@code
 * jobs:
 *   - jobs_top_lvl
 *   - jobs_top_jobs
 * vip:
 *   - vip_rank
 * }</pre>
 *
 * <p>Unit tests instantiate this with {@code plugin=null} so they don't
 * have to spin up a Bukkit plugin — the save() call becomes a graceful
 * no-op in that mode (mirrored from {@code PlaceholderGroupManager}).</p>
 */
public final class ManualKeysStorage {

    /** name ("jobs") → keys. LinkedHashMap for stable YML output. */
    private final Map<String, List<String>> bySource = new LinkedHashMap<>();
    private final HudBoardPlugin plugin;

    public ManualKeysStorage(HudBoardPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------

    public void load() {
        bySource.clear();
        if (plugin == null) {
            save(); // bootstrap empty file when called from unit tests
            return;
        }
        File f = file();
        if (!f.exists()) {
            save();
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        for (String source : yml.getKeys(false)) {
            List<String> raw = yml.getStringList(source);
            if (raw == null) continue;
            List<String> cleaned = new ArrayList<>(raw.size());
            for (String k : raw) {
                if (k == null) continue;
                String t = k.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("%") && t.endsWith("%") && t.length() >= 2) {
                    t = t.substring(1, t.length() - 1);
                }
                String lower = t.toLowerCase(java.util.Locale.ROOT);
                if (!cleaned.contains(lower)) cleaned.add(lower);
            }
            if (!cleaned.isEmpty()) bySource.put(source, cleaned);
        }
    }

    public void save() {
        if (plugin == null) return; // unit-test no-op
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, List<String>> e : bySource.entrySet()) {
            yml.set(e.getKey(), e.getValue());
        }
        try {
            yml.save(file());
        } catch (IOException ex) {
            plugin.getLogger().warning("[HudBoard] Could not save manual-keys.yml: " + ex.getMessage());
        }
    }

    private File file() {
        return new File(plugin.getDataFolder(), "manual-keys.yml");
    }

    // -----------------------------------------------------------------
    // CRUD (mirrors PlaceholderGroupManager's API surface)
    // -----------------------------------------------------------------

    /** All sources and their keys, in insertion order. Unmodifiable. */
    public Map<String, List<String>> all() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : bySource.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Keys for one source. Empty list if the source has no entries. */
    public List<String> keysFor(String source) {
        if (source == null) return List.of();
        List<String> list = bySource.get(source);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /**
     * Add a manual key under the supplied source. Returns {@code true}
     * if the key was actually added (i.e. wasn't already present).
     * Dedupes (case-insensitive) so the admin pasting the same line twice
     * doesn't bloat the file.
     */
    public boolean add(String source, String key) {
        if (source == null || key == null) return false;
        List<String> list = bySource.computeIfAbsent(normalise(source), k -> new ArrayList<>());
        String t = key.trim();
        if (t.startsWith("%") && t.endsWith("%") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty() || list.contains(lower)) return false;
        list.add(lower);
        save();
        return true;
    }

    /**
     * Remove a manual key. Returns {@code true} if it was present.
     * If removing the last key leaves the source empty, the source is
     * deleted too — keeps the file tidy.
     */
    public boolean remove(String source, String key) {
        if (source == null || key == null) return false;
        List<String> list = bySource.get(normalise(source));
        if (list == null) return false;
        String t = key.trim();
        if (t.startsWith("%") && t.endsWith("%") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        boolean removed = list.remove(lower);
        if (removed) {
            if (list.isEmpty()) bySource.remove(normalise(source));
            save();
        }
        return removed;
    }

    /** Drop everything. Used by the {@code /hudboard manual clear} command. */
    public void clear() {
        boolean wasEmpty = bySource.isEmpty();
        bySource.clear();
        if (!wasEmpty) save();
    }

    /** Drop one source entirely. */
    public boolean clearSource(String source) {
        if (source == null) return false;
        String norm = normalise(source);
        boolean removed = bySource.remove(norm) != null;
        if (removed) save();
        return removed;
    }

    private static String normalise(String source) {
        return source == null ? "" : source.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
