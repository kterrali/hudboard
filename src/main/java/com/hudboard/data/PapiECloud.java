package com.hudboard.data;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Utility for fetching PlaceholderAPI eCloud placeholder listings.
 *
 * <p>For legacy-hook PAPI expansions (e.g. {@code Armor}) the
 * {@code getPlaceholders()} hook returns {@code []} because the expansion
 * resolves its placeholders dynamically. The PAPI eCloud
 * ({@code /papi ecloud placeholders <expansion>}) is the only place the
 * full template list is documented — and even then some expansions use
 * templated names like {@code %armor_amount_SLOT%} where {@code SLOT} is
 * replaced at runtime with helmet/chestplate/leggings/boots.</p>
 *
 * <p>This helper runs the command on a {@link CapturingCommandSender} and
 * parses the output. No ProtocolLib, no chat-packet interception — PAPI's
 * command handler always calls {@code sender.sendMessage(...)} which we
 * capture directly. Returns an immutable, alphabetically-sorted list of
 * placeholder keys (without surrounding {@code %}).</p>
 */
public final class PapiECloud {

    private PapiECloud() {}

    // ---------------------------------------------------------------------
    // Installed-expansion lookup
    //
    // PAPI's locally-registered identifiers (e.g. "armor" from
    // PlaceholderAPI.getRegisteredIdentifiers()) are LOWERCASE, while the
    // eCloud command requires the EXACT-CASE expansion name ("Armor").
    //
    // v1.3.10: the previous implementation parsed the chat output of
    // `/papi ecloud list installed <page>` — fragile because PAPI's chat
    // formatter applies color codes that interfered with the regex on some
    // servers (it failed on this user's setup, returning empty lists).
    //
    // We now use PAPI's INTERNAL API directly via reflection on
    // `PlaceholderAPIPlugin.getInstance().getLocalExpansionManager()
    // .getExpansions()` — returns a Map<String, PlaceholderExpansion> where
    // each value's `getName()` gives the proper TitleCase name. This
    // bypasses chat formatting entirely and matches what the eCloud command
    // would print without needing to re-parse it.
    //
    // Cache for {@link #CACHE_TTL_MS} since expansions only change on
    // install / remove, not on every render tick.
    // ---------------------------------------------------------------------

    private static volatile java.util.List<String> installedCache = null;
    private static volatile long installedCacheTime = 0;
    private static final long CACHE_TTL_MS = 5L * 60L * 1000L;  // 5 minutes

    /**
     * Return the exact-case {@code PlaceholderExpansion.getName()} of every
     * PAPI expansion currently loaded on the server. The result is cached
     * for {@link #CACHE_TTL_MS}. Call {@link #clearInstalledCache()} to force
     * a refresh (e.g. after a new expansion is downloaded).
     */
    public static List<String> getInstalledExpansionNames() {
        long now = System.currentTimeMillis();
        if (installedCache != null && (now - installedCacheTime) < CACHE_TTL_MS) {
            return installedCache;
        }
        List<String> fresh = fetchInstalledExpansionNamesViaPapiApi();
        installedCache = fresh;
        installedCacheTime = now;
        return fresh;
    }

    /** Invalidate the installed-expansion cache. The next call to
     *  {@link #getInstalledExpansionNames()} re-fetches from PAPI. */
    public static void clearInstalledCache() {
        installedCache = null;
        installedCacheTime = 0;
    }

    /**
     * Discover installed expansions by walking PAPI's
     * {@code LocalExpansionManager} via reflection. Each value is a
     * {@code PlaceholderExpansion}; we call {@code getName()} on it to get
     * the proper-case name PAPI's eCloud uses ("Armor", "Player", …).
     *
     * <p>This bypasses chat parsing entirely — no regex over color codes,
     * no fragility when PAPI adds new fields or changes message format.
     * If anything fails (PAPI not installed, API changed, no expansions)
     * we LOG the failure so the admin can see why in the console, then
     * return an empty list. The caller ({@link #fetchPlaceholders}) then
     * falls back to a 3-case try so the feature still works on PAPI
     * builds whose internal API differs.</p>
     */
    private static List<String> fetchInstalledExpansionNamesViaPapiApi() {
        try {
            Class<?> papiCls = Class.forName("me.clip.placeholderapi.PlaceholderAPIPlugin");
            Object pluginInstance = papiCls.getMethod("getInstance").invoke(null);
            if (pluginInstance == null) {
                java.util.logging.Logger.getLogger("HudBoard")
                        .warning("[HudBoard PAPI] PlaceholderAPIPlugin.getInstance() returned null");
                return List.of();
            }
            Object localManager;
            try {
                localManager = pluginInstance.getClass()
                        .getMethod("getLocalExpansionManager").invoke(pluginInstance);
            } catch (NoSuchMethodException nsme) {
                // PAPI versions before ~2.8 used different manager names; try
                // alternative names before giving up.
                java.util.logging.Logger.getLogger("HudBoard").warning(
                        "[HudBoard PAPI] getLocalExpansionManager not found on this PAPI build");
                return List.of();
            }
            if (localManager == null) return List.of();
            Object expansionsObj = localManager.getClass()
                    .getMethod("getExpansions").invoke(localManager);
            if (!(expansionsObj instanceof java.util.Map<?, ?> map)) return List.of();
            List<String> names = new ArrayList<>();
            for (Object exp : map.values()) {
                if (exp == null) continue;
                try {
                    String name = (String) exp.getClass().getMethod("getName").invoke(exp);
                    if (name != null && !name.isEmpty()) names.add(name);
                } catch (Throwable ignored) {
                    // one bad expansion shouldn't kill the whole list
                }
            }
            return names;
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger("HudBoard").warning(
                    "[HudBoard PAPI] Could not enumerate installed expansions: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return List.of();
        }
    }

    /**
     * Fetch the official placeholder list for an expansion as documented on
     * the PAPI eCloud. Returns an empty list if PAPI is not installed, the
     * expansion is not in the eCloud, or PAPI's internal API isn't
     * accessible.
     *
     * <p>v1.3.11: switched to walking PAPI's CloudExpansionManager directly
     * via reflection — the same data source the {@code /papi ecloud
     * placeholders <name>} command uses internally. v1.3.10's
     * chat-capture approach proved broken: {@code Bukkit.dispatchCommand}
     * with a custom sender does NOT reliably trigger PAPI's ecloud
     * command path, and the user observed "returned empty for all 3
     * candidate casings of 'armor'" even though {@code /papi ecloud
     * placeholders Armor} works fine when typed in chat.</p>
     *
     * <p>Returns the FIRST non-empty result across these candidate casings
     * (this matches how the actual {@code /papi ecloud placeholders}
     * command works internally):</p>
     * <ol>
     *   <li>The proper-case name resolved from the installed-expansion list
     *       via {@code PlaceholderExpansion.getName()}.</li>
     *   <li>The raw input as-is.</li>
     *   <li>TitleCase ({@code Armor}).</li>
     *   <li>UPPERCASE ({@code ARMOR}).</li>
     * </ol>
     */
    public static List<String> fetchPlaceholders(String expansionId) {
        if (expansionId == null || expansionId.isEmpty()) return List.of();
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return List.of();

        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        String resolved = resolveExactExpansionName(expansionId);
        if (resolved != null) candidates.add(resolved);
        candidates.add(expansionId);
        if (expansionId.length() > 1) {
            candidates.add(Character.toUpperCase(expansionId.charAt(0))
                    + expansionId.substring(1));
        }
        candidates.add(expansionId.toUpperCase(java.util.Locale.ROOT));

        for (String candidate : candidates) {
            List<String> result = fetchPlaceholdersViaCloudExpansionApi(candidate);
            if (!result.isEmpty()) {
                // v1.3.15: don't spam the console on every successful fetch
                // with "Resolved armor → Armor". Warnings on failure stay so
                // admins can still diagnose eCloud issues.
                return result;
            }
        }
        java.util.logging.Logger.getLogger("HudBoard").warning(
                "[HudBoard PAPI] ecloud placeholders returned empty for all "
                        + candidates.size() + " candidate casings of '" + expansionId
                        + "' \u2014 expansion not in PAPI eCloud?");
        return List.of();
    }

    /**
     * Call PAPI's {@code CloudExpansionManager.findCloudExpansionByName}
     * directly via reflection and return its placeholder templates
     * (stripped of surrounding {@code %}). This is the same data the
     * {@code /papi ecloud placeholders <name>} command prints.
     *
     * <p>v1.3.12: fixed — {@code findCloudExpansionByName} returns
     * {@code Optional<CloudExpansion>} (not the expansion directly), so
     * we unwrap via {@code .get()} before reading {@code getPlaceholders()}.
     *
     * <p>The lookup is <strong>case-insensitive</strong> on PAPI's side
     * ({@code exp.getName().equalsIgnoreCase(name)}), so we only need to
     * try the raw id once — no 3-case loop needed for the API path. The
     * candidate loop in {@link #fetchPlaceholders} stays as a safety net
     * for any future PAPI build that tightens the matching.</p>
     *
     * <p>Returns an empty list if PAPI isn't installed, the expansion
     * isn't in the local eCloud cache, the API has changed, or anything
     * throws.</p>
     */
    private static List<String> fetchPlaceholdersViaCloudExpansionApi(String exactName) {
        try {
            Class<?> papiCls = Class.forName("me.clip.placeholderapi.PlaceholderAPIPlugin");
            Object pluginInstance = papiCls.getMethod("getInstance").invoke(null);
            if (pluginInstance == null) return List.of();
            Object cloudManager = pluginInstance.getClass()
                    .getMethod("getCloudExpansionManager").invoke(pluginInstance);
            if (cloudManager == null) return List.of();

            // findCloudExpansionByName returns Optional<CloudExpansion>.
            Object optionalExpansion;
            try {
                optionalExpansion = cloudManager.getClass()
                        .getMethod("findCloudExpansionByName", String.class)
                        .invoke(cloudManager, exactName);
            } catch (NoSuchMethodException nsme) {
                // Older PAPI builds: try the non-Optional variant before giving up.
                try {
                    optionalExpansion = cloudManager.getClass()
                            .getMethod("getCloudExpansionByName", String.class)
                            .invoke(cloudManager, exactName);
                } catch (NoSuchMethodException nsme2) {
                    return List.of();
                }
            }
            if (optionalExpansion == null) return List.of();

            // If it's an Optional, unwrap; otherwise treat as direct expansion.
            Object expansion = optionalExpansion;
            if (optionalExpansion.getClass().getName().equals("java.util.Optional")) {
                boolean present = (boolean) optionalExpansion.getClass()
                        .getMethod("isPresent").invoke(optionalExpansion);
                if (!present) return List.of();
                expansion = optionalExpansion.getClass().getMethod("get")
                        .invoke(optionalExpansion);
                if (expansion == null) return List.of();
            }

            Object placeholders = expansion.getClass()
                    .getMethod("getPlaceholders").invoke(expansion);
            if (!(placeholders instanceof java.util.List<?> list)) return List.of();
            List<String> result = new ArrayList<>();
            for (Object ph : list) {
                if (!(ph instanceof String s)) continue;
                String trimmed = s.trim();
                // PAPI returns templates WITH surrounding % (e.g. "%armor_amount_SLOT%").
                // The user-facing browser expects keys WITHOUT %.
                if (trimmed.startsWith("%") && trimmed.endsWith("%") && trimmed.length() >= 2) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1);
                }
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
            return result;
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger("HudBoard").warning(
                    "[HudBoard PAPI] CloudExpansion API call failed for '"
                            + exactName + "': "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return List.of();
        }
    }

    /**
     * Look up the exact-case name of an installed expansion by case-insensitive
     * matching against the cached installed list. Returns {@code null} if PAPI
     * isn't installed, the list is empty, or no expansion matches.
     */
    static String resolveExactExpansionName(String expansionId) {
        if (expansionId == null || expansionId.isEmpty()) return null;
        try {
            List<String> installed = getInstalledExpansionNames();
            for (String name : installed) {
                if (name.equalsIgnoreCase(expansionId)) return name;
            }
        } catch (Throwable t) {
            // ignore — fall through to raw id
        }
        return null;
    }

    /**
     * Parse the captured command output. PAPI's format is:
     * <pre>
     * 6 placeholders:
     * %armor_amount_SLOT%, %armor_color_(red/green/blue/hex)_SLOT%,
     * %armor_durability_(left/max)_SLOT%, %armor_has_SLOT%,
     * %armor_material_SLOT%, %armor_maxamount_SLOT%
     * </pre>
     * The count line and the comma-separated placeholder list can span any
     * number of {@code sender.sendMessage} calls — we just concatenate all
     * captured strings and extract everything that looks like a placeholder.
     */
    static List<String> parseCaptured(List<String> captured) {
        if (captured == null || captured.isEmpty()) return List.of();
        StringBuilder all = new StringBuilder();
        for (String line : captured) {
            if (line == null) continue;
            all.append(line).append('\n');
        }
        String full = all.toString();
        // Strip Minecraft color codes so patterns like "§a%armor_amount_SLOT%§r"
        // still match.
        String stripped = full.replaceAll("§.", "");
        Set<String> found = new LinkedHashSet<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("%([A-Za-z0-9_\\-()/]+)%");
        java.util.regex.Matcher m = p.matcher(stripped);
        while (m.find()) {
            String key = m.group(1);
            // Some expansions wrap placeholders in nested parens like
            // "%armor_color_(red/green/blue/hex)_SLOT%". Keep it as-is so the
            // user sees the template structure.
            if (key != null && !key.isEmpty()) found.add(key);
        }
        List<String> out = new ArrayList<>(found);
        Collections.sort(out);
        return out;
    }

    /**
     * A minimal {@link CommandSender} that captures every message sent to it
     * into an in-memory list. Used to run {@code /papi ecloud placeholders}
     * without spamming the real player with an undocumented template list
     * (the user can still run the command themselves if they want to read
     * the templates directly).
     *
     * <p>We accept every permission check by default so PAPI doesn't refuse
     * the command on a permissions check.</p>
     */
    static final class CapturingCommandSender implements CommandSender {
        final List<String> captured = new ArrayList<>();

        @Override public void sendMessage(String msg) { if (msg != null) captured.add(msg); }
        @Override public void sendMessage(String[] msgs) {
            if (msgs != null) for (String m : msgs) if (m != null) captured.add(m);
        }
        @Override public void sendMessage(UUID sender, String msg) { sendMessage(msg); }
        @Override public void sendMessage(UUID sender, String[] msgs) { sendMessage(msgs); }
        @Override public void sendMessage(net.kyori.adventure.text.Component msg) {
            if (msg != null) captured.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(msg));
        }
        @Override public boolean isPermissionSet(String name) { return true; }
        @Override public boolean isPermissionSet(Permission perm) { return true; }
        @Override public boolean hasPermission(String name) { return true; }
        @Override public boolean hasPermission(Permission perm) { return true; }
        @Override public PermissionAttachment addAttachment(Plugin plugin) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin plugin, int ticks) { return null; }
        @Override public void removeAttachment(PermissionAttachment attachment) {}
        @Override public void recalculatePermissions() {}
        @Override public java.util.Set<PermissionAttachmentInfo> getEffectivePermissions() { return java.util.Set.of(); }
        @Override public boolean isOp() { return true; }
        @Override public void setOp(boolean value) {}
        @Override public String getName() { return "HudBoardECloudCapture"; }
        @Override public net.kyori.adventure.text.Component name() {
            return net.kyori.adventure.text.Component.text(getName());
        }
        @Override public Spigot spigot() { return new Spigot(); }
        @Override public org.bukkit.Server getServer() { return Bukkit.getServer(); }
    }
}
