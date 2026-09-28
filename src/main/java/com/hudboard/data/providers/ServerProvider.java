package com.hudboard.data.providers;

import org.bukkit.Bukkit;

import java.lang.management.ManagementFactory;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class ServerProvider {
    private ServerProvider() {}
    private static final DecimalFormat F2 = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final DecimalFormat F1 = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final long START_MS = System.currentTimeMillis();

    public static String tps() {
        try { return F1.format(Math.min(20.0, Bukkit.getServer().getTPS()[0])); }
        catch (Throwable t) { return "20.0"; }
    }
    public static String mspt() {
        try { return F1.format(Bukkit.getServer().getAverageTickTime()); }
        catch (Throwable t) { return "0.0"; }
    }
    public static String uptime() {
        long ms = System.currentTimeMillis() - START_MS;
        long days = TimeUnit.MILLISECONDS.toDays(ms);
        long hours = TimeUnit.MILLISECONDS.toHours(ms) % 24;
        long mins  = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        long secs  = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        if (days > 0) return days + "d " + hours + "h " + mins + "m";
        if (hours > 0) return hours + "h " + mins + "m " + secs + "s";
        if (mins > 0) return mins + "m " + secs + "s";
        return secs + "s";
    }
    public static String date() { return LocalDateTime.now().format(DATE_FMT); }
    public static String time() { return LocalDateTime.now().format(TIME_FMT); }
    public static String online() { return String.valueOf(Bukkit.getOnlinePlayers().size()); }
    public static String max()   { return String.valueOf(Bukkit.getMaxPlayers()); }
    public static String memUsed() {
        long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        return String.valueOf(used);
    }
    public static String memMax() {
        long max = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        return String.valueOf(max);
    }
    public static String memPct() {
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long max  = Runtime.getRuntime().maxMemory();
        if (max <= 0) return "0";
        return String.valueOf((int) Math.round(used * 100.0 / max));
    }
    public static String version() { return Bukkit.getBukkitVersion(); }
    public static String motd() {
        try { return Bukkit.getServer().getMotd(); }
        catch (Throwable t) { return "Minecraft Server"; }
    }
    public static String serverName() {
        try { return Bukkit.getServer().getName(); }
        catch (Throwable t) { return "server"; }
    }

    /** First loaded world's name. */
    public static String world() {
        try {
            return Bukkit.getWorlds().get(0).getName();
        } catch (Throwable t) { return "world"; }
    }

    /** In-game time of day as HH:MM (0-24000 ticks → 00:00-24:00). */
    public static String timeOfDay() {
        try {
            long t = Bukkit.getWorlds().get(0).getTime();
            int hh = (int) ((t / 1000 + 6) % 24);
            int mm = (int) ((t % 1000) * 60 / 1000);
            return String.format("%02d:%02d", hh, mm);
        } catch (Throwable t) { return "00:00"; }
    }

    /** Current weather in the first world: clear, rain, thunder. */
    public static String weather() {
        try {
            var w = Bukkit.getWorlds().get(0);
            if (w.isThundering()) return "thunder";
            if (w.hasStorm()) return "rain";
            return "clear";
        } catch (Throwable t) { return "clear"; }
    }
}
