package com.hudboard.data;

import com.hudboard.HudBoardPlugin;
import com.hudboard.data.providers.EconomyProvider;
import com.hudboard.data.providers.PlayerProvider;
import com.hudboard.data.providers.ServerProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves %placeholders% inside template strings. Order:
 *   1. Public API store (values pushed by other plugins via HudBoardAPI)
 *   2. User placeholders (defined in the panel yml under "placeholders:")
 *   3. Built-in server placeholders (%server_*%)
 *   4. Built-in player placeholders (%player_*%)
 *   5. Built-in economy placeholders (%vault_*%)
 *   6. PAPI placeholders (any expansion registered with PlaceholderAPI)
 *
 * HudBoard is a CONSUMER of placeholders, not a producer: it does NOT
 * push its own custom placeholders into the system. To get a value into
 * a panel, install a PAPI expansion that provides the %placeholder% you
 * need (Vault, Jobs, PlayerPoints, custom plugin, etc.) and reference it
 * in your panel yml.
 */
public class DataManager {

    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-zA-Z0-9_]+)%");

    /**
     * Placeholders whose value changes very frequently (every tick). They cause
     * the panel to re-render continuously — a visible "pulsing" — because the
     * hash in {@code InfoPanelRenderer.hashResolvedPlaceholders} changes every
     * time. Throttling the resolution of templates that contain these keys to
     * {@link #TIME_BASED_TTL_MS} makes the panel stay still while still
     * re-rendering once per second.
     */
    private static final Set<String> TIME_BASED_KEYS = Set.of(
            "tps", "mspt",
            "uptime", "date", "time", "time_of_day",
            "session", "session_time", "playtime", "playtime_min"
    );

    /** How long a time-based placeholder resolution stays cached. */
    public static final long TIME_BASED_TTL_MS = 1_000L;

    private final HudBoardPlugin plugin;
    /** Cache of (player, template) → (resolved value, time). Only populated for
     *  templates that contain at least one {@link #TIME_BASED_KEYS} key. */
    private final Map<String, CachedResolution> throttledCache = new ConcurrentHashMap<>();
    /** Last time we swept the throttled cache. */
    private long lastThrottledSweepMs = 0L;
    private Object papi;

    /** Holder for a cached (template, player) → resolved-string result. */
    private record CachedResolution(String value, long timeMs) {}

    /**
     * Test-only override: directly install a PAPI class object without
     * going through {@link #hookPapi()}. Used by
     * {@code com.hudboard.data.DataManagerPapiMockTest} which constructs
     * a fake {@code PlaceholderAPI} class with a static
     * {@code getRegisteredIdentifiers()} method.
     */
    public void setPapiClassForTest(Class<?> fakePapiClass) { this.papi = fakePapiClass; }

    public DataManager(HudBoardPlugin plugin) { this.plugin = plugin; }

    public void hookPapi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try { papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI"); }
        catch (Throwable t) { papi = null; }
    }

    /**
     * Resolve the player's balance: try Vault reflection first, then fall back to
     * PlaceholderAPI if Vault returns 0 or throws (some economy plugins don't follow
     * Vault's standard getBalance signature, but they expose PAPI placeholders).
     */
    private String resolveBalance(Player p) {
        String v = EconomyProvider.balance(p);
        if (v != null && !v.equals("0") && !v.equals("0.00") && !v.equals("—")) return v;
        // Try PAPI placeholders that economy plugins commonly register
        for (String ph : new String[]{
                "vault_eco_balance",         // VireliaEconomy
                "vault_balance_fixed", "vault_balance_whole", "vault_balance",
                "virelia_balance", "virelia_balance_formatted", "economy_balance",
                "playerpoints", "tokens_balance", "balance"
        }) {
            String s = tryPapi(ph, p);
            if (s != null) return s;
        }
        return v == null ? "0" : v;
    }

    /**
     * Try to resolve a single placeholder via PlaceholderAPI. Returns the resolved
     * value (without surrounding %), or null if PAPI didn't handle it (returned the
     * literal input, threw, isn't installed, or returned an empty string).
     */
    private String tryPapi(String key, Player p) {
        if (papi == null) return null;
        try {
            Object result;
            if (p != null) {
                result = ((Class<?>) papi).getMethod("setPlaceholders", Player.class, String.class)
                        .invoke(null, p, "%" + key + "%");
            } else {
                // Static setPlaceholders(String) — applies server-side identifiers only
                result = ((Class<?>) papi).getMethod("setPlaceholders", String.class)
                        .invoke(null, "%" + key + "%");
            }
            if (result == null) return null;
            String s = result.toString();
            if (s == null || s.isEmpty()) return null;
            if (s.equals("%" + key + "%")) return null; // PAPI didn't handle it
            return s;
        } catch (Throwable t) { return null; }
    }

    public boolean hasPapi() { return papi != null; }

    /**
     * Discover every placeholder identifier registered with PlaceholderAPI,
     * regardless of which expansion provides it. Returns an empty set if
     * PAPI isn't installed, the call fails, or no expansion is registered
     * yet.
     *
     * <p>Combines BOTH registration paths PAPI exposes, because the two are
     * NOT redundant:</p>
     * <ul>
     *   <li>{@code PlaceholderAPI.getRegisteredIdentifiers()} (added 2.10+) —
     *       identifiers from expansions registered the modern way
     *       (via {@code PlaceholderExpansion.register()}).</li>
     *   <li>{@code PlaceholderAPI.getPlaceholders().keySet()} (legacy) —
     *       identifiers from hooks registered via the old
     *       {@code PlaceholderHook} API (used by most existing expansions:
     *       Vault, Essentials, Player, Jobs, …).</li>
     * </ul>
     *
     * <p>Either path can legitimately be empty on a vanilla PAPI install —
     * if the user only ran {@code /papi ecloud download} but not
     * {@code /papi register}, modern expansions exist in the cloud folder
     * but neither path is populated. In that case the browser shows
     * "(no PAPI expansion registered)" so the admin knows to run
     * {@code /papi register}.</p>
     *
     * <p>The returned set is alphabetically sorted and contains identifiers
     * WITHOUT the surrounding {@code %} characters. Defensive strip: some
     * hook plugins register with surrounding % (legacy behaviour), we
     * normalise either way.</p>
     */
    public java.util.Set<String> discoverPapiIdentifiers() {
        // v1.3.15: default to SILENT. The "Discovery: modern=9 [...]" log line
        // fired every time the placeholder browser was redrawn (i.e. every
        // click), which spammed the console in normal use. Verbose logging
        // is still available — {@link HudBoardCommand}'s `/hudboard debug
        // papi` calls {@link #discoverPapiIdentifiers(boolean)} with true.
        return discoverPapiIdentifiers(false);
    }

    /**
     * Same as {@link #discoverPapiIdentifiers()} but lets the caller
     * suppress the per-call console logging (used by
     * {@code /hudboard debug papi} which prints everything explicitly).
     */
    public java.util.Set<String> discoverPapiIdentifiers(boolean logDetails) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        if (papi == null) {
            if (logDetails && plugin != null) plugin.getLogger()
                    .warning("[HudBoard PAPI] PAPI class not loaded — check plugin.yml `depend:` and that PlaceholderAPI is on the server. (Use FINE logging to debug details.)");
            return out;
        }
        int modernCount = 0, legacyCount = 0;
        StringBuilder modernSamples = new StringBuilder();
        StringBuilder legacySamples = new StringBuilder();
        Throwable modernError = null, legacyError = null;
        try {
            // 1) Modern API
            try {
                Object res = ((Class<?>) papi).getMethod("getRegisteredIdentifiers")
                        .invoke(null);
                if (res instanceof java.util.Collection<?> col) {
                    for (Object o : col) {
                        if (o == null) continue;
                        String id = normalizeId(o.toString());
                        if (!id.isEmpty()) {
                            out.add(id);
                            modernCount++;
                            if (modernSamples.length() < 200) {
                                if (modernSamples.length() > 0) modernSamples.append(", ");
                                modernSamples.append(id);
                            }
                        }
                    }
                } else if (res != null) {
                    if (logDetails && plugin != null) plugin.getLogger()
                            .warning("[HudBoard PAPI] getRegisteredIdentifiers() returned " + res.getClass().getName() + ", not a Collection. Cannot enumerate.");
                }
            } catch (NoSuchMethodException e) {
                if (logDetails && plugin != null) plugin.getLogger()
                        .info("[HudBoard PAPI] getRegisteredIdentifiers() missing (PAPI < 2.10). Falling back to legacy only.");
            } catch (Throwable t) {
                modernError = t;
                if (logDetails && plugin != null) plugin.getLogger()
                        .warning("[HudBoard PAPI] getRegisteredIdentifiers() failed: " + t);
            }
            // 2) Legacy API (always run, never fall through)
            try {
                Object res = ((Class<?>) papi).getMethod("getPlaceholders").invoke(null);
                if (res instanceof java.util.Map<?, ?> m) {
                    for (Object k : m.keySet()) {
                        if (k == null) continue;
                        String id = normalizeId(k.toString());
                        if (!id.isEmpty()) {
                            out.add(id);
                            legacyCount++;
                            if (legacySamples.length() < 200) {
                                if (legacySamples.length() > 0) legacySamples.append(", ");
                                legacySamples.append(id);
                            }
                        }
                    }
                } else if (res != null) {
                    if (logDetails && plugin != null) plugin.getLogger()
                            .warning("[HudBoard PAPI] getPlaceholders() returned " + res.getClass().getName() + ", not a Map. Cannot enumerate.");
                }
            } catch (NoSuchMethodException e) {
                if (logDetails && plugin != null) plugin.getLogger()
                        .warning("[HudBoard PAPI] getPlaceholders() missing — very old PAPI? Modern path should still work.");
            } catch (Throwable t) {
                legacyError = t;
                if (logDetails && plugin != null) plugin.getLogger()
                        .warning("[HudBoard PAPI] getPlaceholders() failed: " + t);
            }

            if (logDetails && plugin != null) {
                plugin.getLogger().info("[HudBoard PAPI] Discovery: modern=" + modernCount
                        + (modernSamples.length() > 0 ? " [" + modernSamples + "]" : "")
                        + ", legacy=" + legacyCount
                        + (legacySamples.length() > 0 ? " [" + legacySamples + "]" : "")
                        + ", union=" + out.size());
                if (modernCount + legacyCount == 0) {
                    plugin.getLogger().info(
                            "[HudBoard PAPI] Nothing discovered. If expansions exist but stay invisible, "
                            + "run /papi register (after /papi ecloud download), then click Refresh in the browser. "
                            + "Run /hudboard debug papi for a live diagnostic.");
                }
                if (modernError != null || legacyError != null) {
                    plugin.getLogger().warning("[HudBoard PAPI] Errors during discovery — see above.");
                }
            }
        } catch (Throwable t) {
            if (logDetails && plugin != null) plugin.getLogger()
                    .warning("[HudBoard PAPI] Discovery failed entirely: " + t);
        }
        return out;
    }

    /**
     * For a single PAPI expansion identifier, fetch the FULL placeholder
     * keys it exposes (e.g. for the "vault" expansion returns
     * ["vault_balance", "vault_prefix", "vault_groups", …]).
     *
     * <p>This walks PAPI's internal manager:</p>
     * <ol>
     *   <li>{@code PlaceholderAPIPlugin.getInstance()} → plugin instance</li>
     *   <li>{@code .getLocalExpansionManager()} → manager</li>
     *   <li>{@code manager.getExpansion(identifier)} → PlaceholderExpansion</li>
     *   <li>{@code expansion.getPlaceholders()} → List&lt;String&gt; (suffixes)</li>
     * </ol>
     *
     * <p>The full keys are built as {@code identifier + "_" + suffix} per the
     * PAPI convention. Returns an empty list if the expansion isn't loaded
     * or PAPI isn't available.</p>
     */
    public java.util.List<String> placeholdersForExpansion(String identifier) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (papi == null || identifier == null || identifier.isEmpty()) return out;
        try {
            // 1) PlaceholderAPIPlugin.getInstance() — note the singleton lives
            //    on PlaceholderAPIPlugin, not on PlaceholderAPI
            Class<?> pluginCls = Class.forName("me.clip.placeholderapi.PlaceholderAPIPlugin");
            Object papiInstance = pluginCls.getMethod("getInstance").invoke(null);
            if (papiInstance == null) return out;
            // 2) PlaceholderAPIPlugin.getLocalExpansionManager()
            Object manager = papiInstance.getClass().getMethod("getLocalExpansionManager").invoke(papiInstance);
            if (manager == null) return out;
            // 3) LocalExpansionManager.getExpansion(String)
            Object expansion = manager.getClass().getMethod("getExpansion", String.class)
                    .invoke(manager, identifier);
            if (expansion == null) return out;
            // 4) PlaceholderExpansion.getPlaceholders() → List<String>
            Object placeholders = expansion.getClass().getMethod("getPlaceholders").invoke(expansion);
            if (placeholders instanceof java.util.Collection<?> col) {
                String lowerIdent = identifier.toLowerCase(java.util.Locale.ROOT);
                for (Object p : col) {
                    if (p == null) continue;
                    String raw = p.toString().trim();
                    if (raw.isEmpty()) continue;
                    String suffix = raw.toLowerCase(java.util.Locale.ROOT);
                    // Skip suffixes that already include the prefix (some
                    // expansions return full keys, others return suffixes).
                    String full = suffix.startsWith(lowerIdent + "_")
                            ? suffix
                            : (lowerIdent + "_" + suffix);
                    out.add(full);
                }
            }
        } catch (Throwable t) {
            if (plugin != null) plugin.getLogger()
                    .warning("[HudBoard PAPI] placeholdersForExpansion(" + identifier + ") failed: " + t);
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Strip surrounding % and lowercase, returns empty string on garbage. */
    private static String normalizeId(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("%") && s.endsWith("%") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * True if {@code key} (lowercase, no surrounding %) is a placeholder
     * that HudBoard can resolve itself, without going through PAPI. Used
     * by the panel loader to flag placeholders that depend on a PAPI
     * expansion so the admin can spot missing expansions at startup.
     */
    public static boolean isBuiltinPlaceholder(String key) {
        if (key == null) return false;
        key = key.toLowerCase(Locale.ROOT);
        if (key.startsWith("server_")) return true;
        if (key.startsWith("player_")) return true;
        if (key.startsWith("vault_")) return true;
        switch (key) {
            // Server-side aliases
            case "tps": case "mspt": case "uptime": case "date": case "time":
            case "online": case "max": case "version": case "name": case "world":
            // Player aliases (name, uuid, kills, deaths, kdr, playtime, session…)
            case "uuid": case "kills": case "deaths": case "kdr":
            case "playtime": case "session": case "session_time": case "playtime_min":
            // Vault aliases
            case "balance": case "currency":
            case "vault_top_player": case "vault_top_balance":
                return true;
            default:
                return false;
        }
    }

    public String resolve(String template, Player p) {
        return resolve(template, p, null);
    }

    /**
     * Resolve %placeholders% in the template.
     * @param panelUserPlaceholders  optional: user placeholders defined in the panel yml
     */
    public String resolve(String template, Player p, Map<String, String> panelUserPlaceholders) {
        if (template == null || template.isEmpty()) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            String val = resolveOne(key, p, panelUserPlaceholders);
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Same as {@link #resolve(String, Player, Map)} but caches the result for
     * {@link #TIME_BASED_TTL_MS} when the template contains at least one
     * "time-based" placeholder (e.g. %server_tps%, %server_time%,
     * %player_session%). Templates without time-based placeholders are
     * resolved normally without caching.
     *
     * <p>This is the function the renderer should call for both the
     * data-change hash AND the per-frame draw — otherwise the hash will stay
     * stable for a second (so the panel won't re-render), but the drawn text
     * would still change every frame (because drawFrame resolves directly).
     *
     * <p>The cache is bounded: every minute, entries older than 10× the TTL
     * are evicted. Total memory is at most {@code unique-templates × online
     * players × ~64 bytes}, which for a 4-default-panel + 20-custom-panels
     * × 50-players server is well under 1 MB.
     */
    public String resolveThrottled(String template, Player p, Map<String, String> panelUserPlaceholders) {
        if (template == null || template.isEmpty()) return "";
        if (!containsTimeBased(template)) return resolve(template, p, panelUserPlaceholders);
        long now = System.currentTimeMillis();
        String key = (p == null ? "__server__" : p.getUniqueId().toString()) + "|" + template;
        CachedResolution cached = throttledCache.get(key);
        if (cached != null && (now - cached.timeMs) < TIME_BASED_TTL_MS) {
            return cached.value;
        }
        String value = resolve(template, p, panelUserPlaceholders);
        throttledCache.put(key, new CachedResolution(value, now));
        // Periodic cleanup: at most once a minute, drop entries that are way
        // past their TTL (so the cache can't grow without bound for long-lived
        // servers with many one-off template strings).
        if (now - lastThrottledSweepMs > 60_000L) {
            lastThrottledSweepMs = now;
            long cutoff = now - (TIME_BASED_TTL_MS * 10L);
            throttledCache.entrySet().removeIf(e -> e.getValue().timeMs < cutoff);
        }
        return value;
    }

    /** True if {@code template} contains at least one %key% where key is in {@link #TIME_BASED_KEYS}. */
    private static boolean containsTimeBased(String template) {
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            if (TIME_BASED_KEYS.contains(m.group(1).toLowerCase())) return true;
        }
        return false;
    }

    private String resolveOne(String key, Player p, Map<String, String> userPh) {
        // 1) Public API store (other plugins publishing values via HudBoardAPI)
        com.hudboard.api.HudBoardAPI api = com.hudboard.api.HudBoardAPI.get();
        if (api != null) {
            String apiVal = api.resolveInternal(key, p);
            if (apiVal != null) return apiVal;
        }
        // 2) User placeholders (panel author defined)
        if (userPh != null && userPh.containsKey(key)) return userPh.get(key);
        // 3) Server / player / vault built-ins
        if (key.startsWith("server_")) {
            return switch (key) {
                case "server_tps"        -> ServerProvider.tps();
                case "tps"               -> ServerProvider.tps();
                case "server_mspt"       -> ServerProvider.mspt();
                case "mspt"              -> ServerProvider.mspt();
                case "server_uptime"     -> ServerProvider.uptime();
                case "uptime"            -> ServerProvider.uptime();
                case "server_date"       -> ServerProvider.date();
                case "date"              -> ServerProvider.date();
                case "server_time"       -> ServerProvider.time();
                case "time"              -> ServerProvider.time();
                case "server_online"     -> ServerProvider.online();
                case "online"            -> ServerProvider.online();
                case "server_max"        -> ServerProvider.max();
                case "server_mem_used"   -> ServerProvider.memUsed();
                case "server_mem_max"    -> ServerProvider.memMax();
                case "server_mem_pct"    -> ServerProvider.memPct();
                case "server_version"    -> ServerProvider.version();
                case "version"           -> ServerProvider.version();
                case "server_name"       -> ServerProvider.serverName();
                case "server_motd"       -> ServerProvider.motd();
                case "server_world"      -> ServerProvider.world();
                case "server_time_of_day"-> ServerProvider.timeOfDay();
                case "server_weather"    -> ServerProvider.weather();
                default                  -> "%" + key + "%";
            };
        }
        if (p != null && key.startsWith("player_")) {
            return switch (key) {
                case "player_health"       -> String.valueOf(PlayerProvider.health(p));
                case "player_max_health"   -> String.valueOf(PlayerProvider.maxHealth(p));
                case "player_food"         -> String.valueOf(PlayerProvider.food(p));
                case "player_level"        -> String.valueOf(PlayerProvider.level(p));
                case "player_ping"         -> String.valueOf(PlayerProvider.ping(p));
                case "player_world"        -> PlayerProvider.world(p);
                case "player_x"            -> PlayerProvider.x(p);
                case "player_y"            -> PlayerProvider.y(p);
                case "player_z"            -> PlayerProvider.z(p);
                case "player_name"         -> PlayerProvider.name(p);
                case "name"                -> PlayerProvider.name(p);
                case "uuid"                -> p.getUniqueId().toString();
                case "player_uuid"         -> p.getUniqueId().toString();
                case "player_biome"        -> PlayerProvider.biome(p);
                case "player_kills"        -> String.valueOf(PlayerProvider.kills(p));
                case "kills"               -> String.valueOf(PlayerProvider.kills(p));
                case "player_deaths"       -> String.valueOf(PlayerProvider.deaths(p));
                case "deaths"              -> String.valueOf(PlayerProvider.deaths(p));
                case "player_kdr"          -> PlayerProvider.kdr(p);
                case "kdr"                 -> PlayerProvider.kdr(p);
                case "player_playtime"     -> PlayerProvider.playtime(p);
                case "playtime"            -> PlayerProvider.playtime(p);
                case "player_playtime_min" -> PlayerProvider.playtimeMinutes(p);
                case "player_session"      -> PlayerProvider.sessionTime(p);
                case "session"             -> PlayerProvider.sessionTime(p);
                case "session_time"        -> PlayerProvider.sessionTime(p);
                case "player_first_join"   -> PlayerProvider.firstJoin(p);
                case "player_last_seen"    -> PlayerProvider.lastSeen(p);
                default                    -> "%" + key + "%";
            };
        }
        if (p != null && key.startsWith("vault_")) {
            return switch (key) {
                case "vault_balance"      -> resolveBalance(p);
                case "balance"            -> resolveBalance(p);
                case "vault_balance_raw" -> EconomyProvider.balance(p);
                case "vault_currency"    -> EconomyProvider.currencySymbol();
                case "currency"          -> EconomyProvider.currencySymbol();
                // Any other vault_* placeholder (e.g. vault_eco_balance from
                // VireliaEconomy) → try PAPI first, then return blank.
                default -> {
                    String viaPapi = tryPapi(key, p);
                    if (viaPapi != null) yield viaPapi;
                    yield "";
                }
            };
        }
        if (key.startsWith("vault_") && p == null) {
            return switch (key) {
                case "vault_top_player"  -> EconomyProvider.topPlayer();
                case "vault_top_balance" -> EconomyProvider.topBalance();
                default                  -> {
                    String viaPapi = tryPapi(key, null);
                    if (viaPapi != null) yield viaPapi;
                    yield "";
                }
            };
        }
        // 4) PAPI fallback
        if (papi != null && p != null) {
            try {
                Object result = ((Class<?>) papi).getMethod("setPlaceholders", Player.class, String.class)
                        .invoke(null, p, "%" + key + "%");
                if (result != null && !result.toString().equals("%" + key + "%")) return result.toString();
            } catch (Throwable t) { /* ignore */ }
        }
        return "";
    }
}
