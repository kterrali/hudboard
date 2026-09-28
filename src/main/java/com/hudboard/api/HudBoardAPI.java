package com.hudboard.api;

import com.hudboard.HudBoardPlugin;
import com.hudboard.panel.InfoPanelInstance;
import com.hudboard.panel.InfoPanelManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
// BiConsumer was used for mode-change listeners (removed in v3.0).
// The import is no longer needed.
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Public API for other plugins to publish values into HudBoard panels and
 * inspect / control panels at runtime.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * HudBoardAPI api = HudBoardAPI.get(myPlugin);
 *
 * // Push a value to a placeholder (referenced in yml as %event.timer%)
 * api.set("event.timer", "12:34");
 *
 * // Push a dynamic value (called on every refresh)
 * api.set("server.bossbar", () -> "Boss HP: " + boss.getHp() + "%");
 *
 * // Push a player-scoped value
 * api.setPlayerScoped("player.rank", p -> ranks.getOrDefault(p, "Member"));
 *
 * // Force-refresh a panel (e.g. after updating its yml)
 * api.refreshPanel("info-hub");
 * }</pre>
 *
 * <h2>Placeholder namespace</h2>
 * Values set with {@code set()} are exposed inside the panel yml via the
 * {@code %key%} syntax. The key is case-insensitive, must match
 * {@code [a-zA-Z0-9_-]+}, and is automatically prefixed with nothing — you
 * author the full key. Common prefixes used by HudBoard itself: {@code server_},
 * {@code player_}, {@code vault_}, {@code top_} — to avoid clashes, prefer
 * a domain-specific prefix like {@code myplugin_*}.
 *
 * <h2>Threading</h2>
 * All write methods are safe to call from any thread. The values are read on
 * the main server thread during the panel tick, so suppliers must be
 * thread-safe.
 */
public final class HudBoardAPI {

    private static HudBoardAPI instance;

    /** Returns the API singleton. Returns null if HudBoard is not loaded. */
    public static @Nullable HudBoardAPI get() {
        Plugin p = Bukkit.getPluginManager().getPlugin("HudBoard");
        if (!(p instanceof HudBoardPlugin)) return null;
        if (instance == null) instance = new HudBoardAPI((HudBoardPlugin) p);
        return instance;
    }

    /** Returns the API singleton, registering {@code requester} as the caller
     *  for log/debug purposes. */
    public static @Nullable HudBoardAPI get(@NotNull Plugin requester) {
        HudBoardAPI api = get();
        if (api != null) api.lastCaller.set(requester.getName());
        return api;
    }

    // -------------------------------------------------------------------------
    // Store
    // -------------------------------------------------------------------------

    /** Static values: {@code key -> value}. */
    private final Map<String, String> staticStore = new ConcurrentHashMap<>();
    /** Dynamic values: {@code key -> supplier} (called on each render). */
    private final Map<String, Supplier<String>> dynamicStore = new ConcurrentHashMap<>();
    /** Per-player dynamic values: {@code key -> fn(player)}. */
    private final Map<String, Function<Player, String>> playerStore = new ConcurrentHashMap<>();
    /** Last API caller (for /hudboard debug). */
    private final ThreadLocal<String> lastCaller = new ThreadLocal<>();
    private final HudBoardPlugin plugin;

    private HudBoardAPI(HudBoardPlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Set / unset
    // -------------------------------------------------------------------------

    /**
     * Set a static placeholder. The value is taken verbatim and used until
     * you call {@link #unset(String)} or set the same key to a new value.
     *
     * <p>Reference in yml as {@code %yourkey%}. Keys are case-insensitive.</p>
     *
     * @param key   the placeholder key, e.g. {@code "event.timer"}
     * @param value the string to display, or {@code null} to clear
     */
    public void set(@NotNull String key, @Nullable String value) {
        validateKey(key);
        String k = key.toLowerCase();
        if (value == null) {
            // null = clear from all stores
            staticStore.remove(k);
            dynamicStore.remove(k);
            playerStore.remove(k);
        } else {
            staticStore.put(k, value);
            dynamicStore.remove(k);
            playerStore.remove(k);
        }
        triggerRefreshListeners(key);
    }

    /**
     * Set a dynamic placeholder: the supplier is called on every panel render
     * (i.e. every 1-2s while a player is in range). Use this for values that
     * change frequently and would be wasteful to push every tick.
     */
    public void set(@NotNull String key, @NotNull Supplier<String> supplier) {
        validateKey(key);
        String k = key.toLowerCase();
        dynamicStore.put(k, supplier);
        staticStore.remove(k);
        playerStore.remove(k);
    }

    /**
     * Set a per-player dynamic placeholder: the function is called once per
     * player per render, with the viewer as the argument. Use this for
     * player-specific data (rank, prestige, quest progress, etc.).
     */
    public void setPlayerScoped(@NotNull String key, @NotNull Function<Player, String> fn) {
        validateKey(key);
        String k = key.toLowerCase();
        playerStore.put(k, fn);
        staticStore.remove(k);
        dynamicStore.remove(k);
    }

    /** Remove a key from all three stores. */
    public void unset(@NotNull String key) {
        String k = key.toLowerCase();
        staticStore.remove(k);
        dynamicStore.remove(k);
        playerStore.remove(k);
        triggerRefreshListeners(key);
    }

    /** Remove every key starting with {@code prefix} (case-insensitive). */
    public int unsetPrefix(@NotNull String prefix) {
        String p = prefix.toLowerCase();
        int n = 0;
        for (String k : staticStore.keySet())   if (k.startsWith(p)) { staticStore.remove(k); n++; }
        for (String k : dynamicStore.keySet())  if (k.startsWith(p)) { dynamicStore.remove(k); n++; }
        for (String k : playerStore.keySet())   if (k.startsWith(p)) { playerStore.remove(k); n++; }
        return n;
    }

    /** Returns the current set value, or null if not set. Does not invoke suppliers. */
    public @Nullable String getStatic(@NotNull String key) {
        return staticStore.get(key.toLowerCase());
    }

    /** Returns true if the key is set in any store. */
    public boolean has(@NotNull String key) {
        String k = key.toLowerCase();
        return staticStore.containsKey(k) || dynamicStore.containsKey(k) || playerStore.containsKey(k);
    }

    /** List all currently-registered keys (case-folded). */
    public @NotNull Set<String> keys() {
        java.util.HashSet<String> all = new java.util.HashSet<>();
        all.addAll(staticStore.keySet());
        all.addAll(dynamicStore.keySet());
        all.addAll(playerStore.keySet());
        return Collections.unmodifiableSet(all);
    }

    /**
     * Internal: resolve a key, falling back through static -> dynamic -> player.
     * Returns null if nothing is registered.
     * @param player the viewer, may be null for non-player-scoped lookup
     */
    public @Nullable String resolve(@NotNull String key, @Nullable Player player) {
        String k = key.toLowerCase();
        String s = staticStore.get(k);
        if (s != null) return s;
        Supplier<String> sup = dynamicStore.get(k);
        if (sup != null) {
            try { return sup.get(); } catch (Throwable t) { return null; }
        }
        Function<Player, String> fn = playerStore.get(k);
        if (fn != null && player != null) {
            try { return fn.apply(player); } catch (Throwable t) { return null; }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Panel control
    // -------------------------------------------------------------------------

    /** Returns the set of placed panel ids. */
    public @NotNull Set<String> listPanels() {
        InfoPanelManager mgr = plugin.getPanelManager();
        return mgr == null ? Collections.emptySet() : mgr.placedIds();
    }

    /** Returns true if a panel with this id is placed in the world. */
    public boolean panelExists(@NotNull String id) {
        return listPanels().contains(id);
    }

    /** Force-refresh a placed panel (re-crop + re-render for all viewers). */
    public void refreshPanel(@NotNull String id) {
        InfoPanelManager mgr = plugin.getPanelManager();
        if (mgr == null) return;
        InfoPanelInstance inst = mgr.getPlaced(id);
        if (inst != null) mgr.invalidate(inst);
    }

    /** Force-refresh all placed panels. */
    public void refreshAll() {
        InfoPanelManager mgr = plugin.getPanelManager();
        if (mgr == null) return;
        mgr.invalidateAll();
    }

    // -------------------------------------------------------------------------
    // Listeners
    // -------------------------------------------------------------------------

    private final java.util.List<Consumer<String>> refreshListeners =
            Collections.synchronizedList(new java.util.ArrayList<>());

    /** Subscribe to "a placeholder value was set or unset" events. */
    public void registerRefreshListener(@NotNull Consumer<String> listener) {
        refreshListeners.add(listener);
    }

    private void triggerRefreshListeners(String key) {
        for (Consumer<String> l : refreshListeners) {
            try { l.accept(key); } catch (Throwable t) { /* ignore */ }
        }
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    /** Name of the plugin that last called the API (for /hudboard debug). */
    public @Nullable String lastCaller() { return lastCaller.get(); }

    /** Snapshot of static store (for debugging only). */
    public @NotNull Map<String, String> snapshotStatic() {
        return Collections.unmodifiableMap(new java.util.HashMap<>(staticStore));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void validateKey(String key) {
        if (key == null || !key.matches("[a-zA-Z0-9_.\\-]+")) {
            throw new IllegalArgumentException("Invalid placeholder key: " + key
                    + " (allowed: a-z, 0-9, _, ., -)");
        }
    }

    // For DataManager:
    /** Resolves a key for internal use by DataManager. Same as {@link #resolve}. */
    public @Nullable String resolveInternal(@NotNull String key, @Nullable Player player) {
        return resolve(key, player);
    }
}
