package com.hudboard.groups;

import com.hudboard.HudBoardPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Persistence + CRUD for user-defined placeholder groups (Phase 2.1).
 *
 * <p>Groups are stored in {@code plugins/HudBoard/groups.yml} as a flat
 * {@code Map<String, List<String>>} keyed by group name. The file is
 * loaded on plugin enable and rewritten atomically on every mutation
 * (we hold the whole list in memory so the on-disk format is always a
 * full snapshot — easier to reason about than incremental saves).</p>
 *
 * <h2>Reserved names</h2>
 * Built-in placeholder categories and PAPI identifiers are reserved so
 * we never end up with a group called {@code "server"} shadowing the
 * built-in. Use {@link #isReserved(String)} from the GUI / command layer
 * to validate before calling {@link #create(String, List)}.
 */
public final class PlaceholderGroupManager {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,32}");
    private static final String RESERVED_PREFIX = "group:";

    private final HudBoardPlugin plugin;
    /** name -> keys, in insertion order (LinkedHashMap for stable YML output) */
    private final Map<String, List<String>> groups = new LinkedHashMap<>();

    public PlaceholderGroupManager(HudBoardPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        groups.clear();
        File f = file();
        if (!f.exists()) {
            save(); // write the empty file so admins see it
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        // Top-level keys are group names; values are List<String> of placeholder keys.
        for (String name : yml.getKeys(false)) {
            List<String> raw = yml.getStringList(name);
            if (raw == null || raw.isEmpty()) continue;
            // Normalize: strip surrounding % if the admin typed them, lowercase.
            List<String> cleaned = new ArrayList<>(raw.size());
            for (String k : raw) {
                if (k == null) continue;
                String t = k.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("%") && t.endsWith("%") && t.length() >= 2) {
                    t = t.substring(1, t.length() - 1);
                }
                cleaned.add(t.toLowerCase(java.util.Locale.ROOT));
            }
            if (cleaned.isEmpty()) continue;
            groups.put(name, cleaned);
        }
    }

    public void save() {
        // Unit tests instantiate the manager with plugin=null to exercise
        // the in-memory map. Skip the disk write in that case — the
        // build-time validator doesn't need a persisted groups.yml.
        if (plugin == null) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            yml.set(e.getKey(), e.getValue());
        }
        try {
            yml.save(file());
        } catch (IOException ex) {
            plugin.getLogger().warning("[HudBoard] Could not save groups.yml: " + ex.getMessage());
        }
    }

    private File file() {
        return new File(plugin.getDataFolder(), "groups.yml");
    }

    // ---------------------------------------------------------------------
    // Public CRUD
    // ---------------------------------------------------------------------

    /** All groups, in insertion order. The returned map is unmodifiable. */
    public Map<String, List<String>> all() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    public boolean exists(String name) {
        return groups.containsKey(normalize(name));
    }

    /**
     * Create a new group. Returns the error message string if it can't
     * be created, or {@code null} on success.
     */
    public String create(String rawName, List<String> rawKeys) {
        String name = normalize(rawName);
        if (!NAME_PATTERN.matcher(name).matches()) {
            return "Group name must be 1-32 chars, only letters, digits, '_' and '-'.";
        }
        if (groups.containsKey(name)) {
            return "Group '" + name + "' already exists.";
        }
        if (isReservedIncludingPapi(name)) {
            return "'" + name + "' is reserved (built-in or PAPI identifier).";
        }
        // Empty key list is allowed — the admin can `create` then `add` keys
        // later. We still go through the normalisation pass so a partial
        // create with stray blanks doesn't poison the map.
        List<String> keys = new ArrayList<>();
        if (rawKeys != null) {
            for (String k : rawKeys) {
                if (k == null) continue;
                String t = k.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("%") && t.endsWith("%") && t.length() >= 2) {
                    t = t.substring(1, t.length() - 1);
                }
                String lower = t.toLowerCase(java.util.Locale.ROOT);
                if (!keys.contains(lower)) keys.add(lower);
            }
        }
        groups.put(name, keys);
        save();
        return null;
    }

    /**
     * Add (or replace) a single placeholder key in a group. Returns
     * {@code false} if the group doesn't exist. The key is deduped.
     */
    public boolean addKey(String name, String key) {
        name = normalize(name);
        List<String> current = groups.get(name);
        if (current == null) return false;
        String t = key == null ? "" : key.trim();
        if (t.startsWith("%") && t.endsWith("%") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty() || current.contains(lower)) return true; // no-op
        current.add(lower);
        save();
        return true;
    }

    /**
     * Remove a single placeholder key from a group. Returns {@code false}
     * if the group doesn't exist or the key isn't in it.
     */
    public boolean removeKey(String name, String key) {
        name = normalize(name);
        List<String> current = groups.get(name);
        if (current == null) return false;
        String t = key == null ? "" : key.trim();
        if (t.startsWith("%") && t.endsWith("%") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        boolean removed = current.remove(lower);
        if (removed) {
            if (current.isEmpty()) {
                groups.remove(name);
            }
            save();
        }
        return removed;
    }

    /**
     * Delete a whole group. Returns {@code false} if no such group.
     */
    public boolean delete(String name) {
        List<String> removed = groups.remove(normalize(name));
        if (removed == null) return false;
        save();
        return true;
    }

    /** {@code true} when {@code rawName} collides with a built-in or PAPI id. */
    public boolean isReserved(String rawName) {
        String name = normalize(rawName);
        if (name.isEmpty()) return true;
        // Reserved PREFIX covers anything the browser tries to render with
        // a "group:" label so we can't accidentally clash later.
        if (name.startsWith(RESERVED_PREFIX)) return true;
        // Check against the placeholder browser's built-in category list.
        for (String builtin : BUILTIN_SOURCES) {
            if (builtin.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Names of PAPI identifiers installed on the server, considered
     * reserved. Updated each time we rebuild the browser (so the
     * reservation list follows {@code /papi ecloud download}). Lazily
     * computed against the live PAPI plugin via reflection so we don't
     * crash at class-load time when PAPI is absent.
     */
    private final Set<String> dynamicReserved = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    /** Refresh the PAPI-identifier reservation list. Cheap; call from
     *  {@code /hudboard reload} and after Fetch from eCloud. */
    public void refreshDynamicReservations(java.util.Set<String> papiIdentifiers) {
        dynamicReserved.clear();
        if (papiIdentifiers != null) dynamicReserved.addAll(papiIdentifiers);
    }

    public boolean isReservedIncludingPapi(String rawName) {
        if (isReserved(rawName)) return true;
        String name = normalize(rawName);
        return dynamicReserved.contains(name);
    }

    /**
     * Names that the PlaceholderBrowser uses for its hardcoded category
     * list. Mirror of {@code PlaceholderBrowser.BUILTIN_SOURCES} so the
     * group manager refuses to clash with them.
     */
    public static final String[] BUILTIN_SOURCES = {
            "server", "player", "world", "economy", "top", "target",
            "runtime", "hudboard-api", "misc"
    };

    private static String normalize(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Suffix the browser uses to label group papers: {@code "group:Name"}. */
    public static String browserLabel(String name) {
        return RESERVED_PREFIX + name;
    }

    // ---------------------------------------------------------------------
    // v1.4.1 — YAML import / export
    //
    // The on-disk format is identical to the file the manager itself
    // writes (`Map<String, List<String>>`), so import/export is
    // essentially a copy/paste with validation. We keep it inside the
    // manager so the same normalisation rules apply (lowercase, strip
    // surrounding `%`, dedupe).
    // ---------------------------------------------------------------------

    /**
     * Render all groups to a YAML block suitable for chat (used by the
     * admin command's `export` action).
     */
    public String exportYaml() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            yml.set(e.getKey(), e.getValue());
        }
        return yml.saveToString();
    }

    /**
     * Merge the contents of one YAML block into the in-memory map.
     * Reserved names are skipped with a warning so a careless paste can't
     * create a group called {@code server} and silently shadow the
     * built-in. Existing groups with the same name get their keys
     * MERGED (not overwritten) — that's the only sane default for a
     * clipboard paste.
     *
     * @return the number of NEW keys added across all merged groups.
     */
    public int importYaml(String yamlContent, java.util.function.Consumer<String> warningSink) {
        if (yamlContent == null || yamlContent.isBlank()) return 0;
        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.loadFromString(yamlContent);
        } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            if (warningSink != null) warningSink.accept("YAML parse error: " + ex.getMessage());
            return 0;
        }
        int added = 0;
        int groupsTouched = 0;
        for (String name : yml.getKeys(false)) {
            String normalised = normalize(name);
            if (isReservedIncludingPapi(normalised)) {
                if (warningSink != null) {
                    warningSink.accept("Skipped reserved group '" + name + "'.");
                }
                continue;
            }
            if (!NAME_PATTERN.matcher(normalised).matches()) {
                if (warningSink != null) {
                    warningSink.accept("Skipped invalid group name '" + name + "'.");
                }
                continue;
            }
            List<String> incoming = new ArrayList<>();
            for (String k : yml.getStringList(name)) {
                if (k == null) continue;
                String t = k.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("%") && t.endsWith("%") && t.length() >= 2) {
                    t = t.substring(1, t.length() - 1);
                }
                String lower = t.toLowerCase(java.util.Locale.ROOT);
                if (!incoming.contains(lower)) incoming.add(lower);
            }
            if (incoming.isEmpty()) {
                if (warningSink != null) {
                    warningSink.accept("Skipped empty group '" + name + "'.");
                }
                continue;
            }
            List<String> existing = groups.get(normalised);
            if (existing == null) {
                groups.put(normalised, incoming);
                added += incoming.size();
                groupsTouched++;
            } else {
                int before = existing.size();
                for (String k : incoming) {
                    if (!existing.contains(k)) existing.add(k);
                }
                int delta = existing.size() - before;
                if (delta > 0) {
                    added += delta;
                    groupsTouched++;
                }
            }
        }
        if (added > 0) save();
        return added;
    }
}
