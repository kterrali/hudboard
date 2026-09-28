package com.hudboard.data.providers;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerProvider {
    private PlayerProvider() {}
    private static final Map<UUID, Integer> KILLS = new HashMap<>();
    private static final Map<UUID, Integer> DEATHS = new HashMap<>();
    /** When each player joined (ms). Used for current-session playtime. */
    private static final Map<UUID, Long> JOIN_TIMES = new HashMap<>();
    /** Total accumulated playtime in seconds (sum of all prior sessions, persisted). */
    private static final Map<UUID, Long> TOTAL_PLAYTIME_SEC = new HashMap<>();

    /** Null-safe: returns 0 if the player has no recorded kills. */
    public static int kills(Player p) { return p == null ? 0 : KILLS.getOrDefault(p.getUniqueId(), 0); }
    public static int deaths(Player p) { return p == null ? 0 : DEATHS.getOrDefault(p.getUniqueId(), 0); }
    /** UUID-based lookup. Used by leaderboards that include offline players. */
    public static int kills(UUID id) { return id == null ? 0 : KILLS.getOrDefault(id, 0); }
    public static int deaths(UUID id) { return id == null ? 0 : DEATHS.getOrDefault(id, 0); }
    public static String kdr(Player p) {
        if (p == null) return "0";
        int k = kills(p), d = deaths(p);
        if (d == 0) return String.valueOf(k);
        return String.format("%.2f", (double) k / d);
    }
    public static void addKill(Player p)   { if (p != null) KILLS.merge(p.getUniqueId(), 1, Integer::sum); }
    public static void addDeath(Player p) { if (p != null) DEATHS.merge(p.getUniqueId(), 1, Integer::sum); }
    public static void reset(Player p) { if (p != null) { KILLS.remove(p.getUniqueId()); DEATHS.remove(p.getUniqueId()); } }
    public static void unload(UUID id) { if (id != null) { KILLS.remove(id); DEATHS.remove(id); JOIN_TIMES.remove(id); } }

    /** Player joined: remember their join time. */
    public static void onJoin(Player p) {
        if (p == null) return;
        JOIN_TIMES.put(p.getUniqueId(), System.currentTimeMillis());
    }

    /** Player quit: add the current session to their total playtime. */
    public static void onQuit(Player p) {
        if (p == null) return;
        UUID id = p.getUniqueId();
        Long joined = JOIN_TIMES.remove(id);
        if (joined != null) {
            long seconds = Math.max(0, (System.currentTimeMillis() - joined) / 1000L);
            TOTAL_PLAYTIME_SEC.merge(id, seconds, Long::sum);
        }
    }

    /** Restore total playtime from a YAML file (called on plugin enable). */
    public static void loadPlaytime(Map<String, Long> data) {
        TOTAL_PLAYTIME_SEC.clear();
        if (data == null) return;
        for (var e : data.entrySet()) {
            try {
                TOTAL_PLAYTIME_SEC.put(UUID.fromString(e.getKey()), e.getValue());
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /** Snapshot total playtime for saving to YAML (called on plugin disable). */
    public static Map<String, Long> snapshotPlaytime() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (var e : TOTAL_PLAYTIME_SEC.entrySet()) {
            out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    /** Total playtime in seconds, including the current session if online. */
    public static long playtimeSeconds(Player p) {
        if (p == null) return 0;
        UUID id = p.getUniqueId();
        long saved = TOTAL_PLAYTIME_SEC.getOrDefault(id, 0L);
        Long joined = JOIN_TIMES.get(id);
        if (joined != null) {
            saved += Math.max(0, (System.currentTimeMillis() - joined) / 1000L);
        }
        return saved;
    }

    /** Current session playtime in seconds (0 if offline). */
    public static long sessionSeconds(Player p) {
        if (p == null) return 0;
        Long joined = JOIN_TIMES.get(p.getUniqueId());
        if (joined == null) return 0;
        return Math.max(0, (System.currentTimeMillis() - joined) / 1000L);
    }

    /** Format a duration in seconds as "Xd Yh Zm" (compact) or "Xh Ym" / "Ym Ys". */
    public static String formatDuration(long seconds) {
        if (seconds < 0) seconds = 0;
        long d = seconds / 86400L;
        long h = (seconds % 86400L) / 3600L;
        long m = (seconds % 3600L) / 60L;
        long s = seconds % 60L;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    public static int health(Player p) { return (int) Math.round(p.getHealth()); }
    public static int maxHealth(Player p) {
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return 20;
        return (int) Math.round(attr.getValue());
    }
    public static int food(Player p) { return p.getFoodLevel(); }
    public static int level(Player p) { return p.getLevel(); }
    public static int ping(Player p) {
        try { return p.getPing(); } catch (Throwable t) { return 0; }
    }
    public static String world(Player p) { return p.getWorld().getName(); }
    public static String x(Player p) { return String.valueOf(p.getLocation().getBlockX()); }
    public static String y(Player p) { return String.valueOf(p.getLocation().getBlockY()); }
    public static String z(Player p) { return String.valueOf(p.getLocation().getBlockZ()); }
    public static String name(Player p) { return p.getName(); }

    /** Current biome at the player's feet. */
    public static String biome(Player p) {
        if (p == null) return "—";
        try { return p.getLocation().getBlock().getBiome().name().toLowerCase().replace('_', ' '); }
        catch (Throwable t) { return "—"; }
    }

    /** Total playtime formatted as "Xd Yh Zm". */
    public static String playtime(Player p) {
        return formatDuration(playtimeSeconds(p));
    }
    /** Total playtime in minutes. */
    public static String playtimeMinutes(Player p) {
        return String.valueOf(playtimeSeconds(p) / 60L);
    }
    /** Current session playtime formatted. */
    public static String sessionTime(Player p) {
        return formatDuration(sessionSeconds(p));
    }
    /** First join date as YYYY-MM-DD. */
    public static String firstJoin(Player p) {
        if (p == null) return "—";
        try {
            long ts = p.getFirstPlayed();
            if (ts <= 0) return "—";
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (Throwable t) { return "—"; }
    }
    /** Last seen (last logout) as YYYY-MM-DD HH:mm. */
    public static String lastSeen(Player p) {
        if (p == null) return "—";
        try {
            long ts = p.getLastPlayed();
            if (ts <= 0) return "—";
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Throwable t) { return "—"; }
    }
}
