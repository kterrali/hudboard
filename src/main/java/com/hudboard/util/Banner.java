package com.hudboard.util;

import com.hudboard.HudBoardPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * v2.6.4: brand banner printed once at {@code onEnable()}.
 *
 * <p><b>Important rendering detail:</b> {@code plugin.getLogger().info()}
 * accepts a plain string. Paper's console interprets legacy {@code §x}
 * colour codes, but it does <i>not</i> parse MiniMessage tags. So we
 * compile each line via {@link MiniMessage} and then serialise the
 * resulting {@link Component} back to a legacy-§ string with
 * {@link LegacyComponentSerializer}.</p>
 *
 * <p><b>v2.6.4 lesson learned:</b> the legacy §x hex format (e.g.
 * {@code §x§f§f§d§7§0§0}) is rendered as gibberish in some Paper
 * consoles — the codes leak as literal text instead of producing a
 * colour. We therefore use <b>named colours only</b> ({@code §f}, {@code §a},
 * {@code §c}, …) which are supported everywhere since 1.7. The MiniMessage
 * {@code <gradient>} tag would expand to multiple §x codes, so it's
 * dropped too. Net result: clean, readable banner in any console.</p>
 *
 * <p>Design: dark-gray box frame, gold status icons, white labels,
 * green values, gray tags. Inner width = 68 chars. Hand-aligned so the
 * box stays perfectly straight regardless of locale.</p>
 */
public final class Banner {

    private Banner() {}

    /** Fixed inner width so the box lines up regardless of locale. */
    private static final int INNER = 68;

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** Legacy-§ serializer used to emit console-friendly strings. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** Parse MiniMessage → legacy-§ string. Strip italic because the
     *  console has no italic anyway and it looks weird with the §. */
    private static String legacy(String mini) {
        Component c = MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
        return LEGACY.serialize(c);
    }

    /** Send the banner to the server console (one multi-line info() emit). */
    public static void send(HudBoardPlugin plugin) {
        String ver = plugin.getPluginMeta().getVersion();
        boolean papi = plugin.getDataManager().hasPapi();
        int profiles = plugin.getPanelManager().getProfileIds().size();
        int placed = plugin.getPanelManager().allPlaced().size();
        int groups = plugin.getGroupManager() == null ? 0 : plugin.getGroupManager().all().size();
        int manualKeys = plugin.getManualKeys() == null
                ? 0
                : plugin.getManualKeys().all().values().stream().mapToInt(java.util.List::size).sum();
        String server = serverName();

        StringBuilder sb = new StringBuilder();
        sb.append(legacy(line(""))).append('\n');
        sb.append(legacy(centered(
                "<gold><bold>HudBoard v" + ver + "</bold></gold>",
                "<gray>Spigot/Paper HUD panels with PAPI support</gray>"))).append('\n');
        sb.append(legacy(centered("<dark_gray>" + server + "</dark_gray>"))).append('\n');
        sb.append(legacy(line(""))).append('\n');
        sb.append(legacy(statusLine("PAPI", papi ? "<green>enabled</green>" : "<red>DISABLED (required)</red>"))).append('\n');
        sb.append(legacy(statusLine("Profiles", "<green>" + profiles + "</green> <gray>(" + placed + " placed)</gray>"))).append('\n');
        sb.append(legacy(statusLine("Groups", "<green>" + groups + "</green>"))).append('\n');
        sb.append(legacy(statusLine("Manual keys", "<green>" + manualKeys + "</green>"))).append('\n');
        String gh = plugin.getConfigManager().githubUrl();
        if (gh != null && !gh.isBlank()) {
            sb.append(legacy(line(""))).append('\n');
            sb.append(legacy(centered("<dark_gray>" + gh + "</dark_gray>"))).append('\n');
        }
        sb.append(legacy(line("")));
        plugin.getLogger().info(sb.toString());
    }

    /** Plain box line: {@code ║<spaces>║}. */
    private static String line(String content) {
        return "<dark_gray>║" + pad(content, INNER) + "║</dark_gray>";
    }

    /** Centered line: content padded with spaces on both sides. */
    private static String centered(String... parts) {
        String all = String.join("", parts);
        int vis = visibleLength(all);
        int total = Math.max(0, INNER - vis);
        int left = total / 2;
        int right = total - left;
        StringBuilder sb = new StringBuilder();
        sb.append("<dark_gray>║</dark_gray>");
        repeat(sb, ' ', left);
        for (String p : parts) sb.append(p);
        repeat(sb, ' ', right);
        sb.append("<dark_gray>║</dark_gray>");
        return sb.toString();
    }

    /** Status line: {@code ▸  Label: value<padding>║}. */
    private static String statusLine(String label, String valueMini) {
        String inner = "  <gold>▸</gold> <white>" + label + ":</white> " + valueMini;
        StringBuilder sb = new StringBuilder();
        sb.append("<dark_gray>║</dark_gray>");
        sb.append(inner);
        repeat(sb, ' ', Math.max(0, INNER - visibleLength(inner)));
        sb.append("<dark_gray>║</dark_gray>");
        return sb.toString();
    }

    /** Visible length of a MiniMessage-tagged string (tags + § stripped). */
    private static int visibleLength(String s) {
        return s.replaceAll("<[^>]+>", "").replaceAll("§.", "").length();
    }

    private static void repeat(StringBuilder sb, char c, int n) {
        for (int i = 0; i < n; i++) sb.append(c);
    }

    private static String pad(String s, int width) {
        int n = Math.max(0, width - visibleLength(s));
        StringBuilder sb = new StringBuilder();
        repeat(sb, ' ', n);
        return s + sb.toString();
    }

    private static String serverName() {
        try { return org.bukkit.Bukkit.getName() + " " + org.bukkit.Bukkit.getBukkitVersion(); }
        catch (Throwable t) { return "Bukkit ?"; }
    }
}
