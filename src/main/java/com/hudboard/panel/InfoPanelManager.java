package com.hudboard.panel;

import com.hudboard.HudBoardPlugin;
import com.hudboard.config.ConfigManager;
import com.hudboard.data.DataManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Player;
import com.hudboard.panel.PanelTileEntity;
import com.hudboard.panel.PanelTileItemFrame;
import com.hudboard.panel.PanelTileItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.logging.Logger;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads panel profiles from disk and tracks placed panel instances in the world.
 *
 * Each placed instance is identified by a UUID name (e.g. "a1b2c3d4") and stored
 * with its location and item-frame list for clean removal.
 */
public class InfoPanelManager {

    private final HudBoardPlugin plugin;
    /** id -> profile */
    private final Map<String, InfoPanel> profiles = new LinkedHashMap<>();
    /** unique name -> instance */
    private final Map<String, InfoPanelInstance> placed = new ConcurrentHashMap<>();
    /** last tick at which each placed panel was rendered (for refresh interval) */
    private final Map<String, Long> lastRefresh = new HashMap<>();
    /** last tick at which the panel rotated to face a nearby viewer (follow-viewer) */
    private final Map<String, Long> lastFollowRotate = new HashMap<>();
    /** profile id -> live MapViews (one per tile, shared across instances) */
    private final Map<String, MapView[]> viewsByProfile = new HashMap<>();

    public static final NamespacedKey MARKER = new NamespacedKey("hudboard", "panel");
    public static final NamespacedKey NAME_KEY = new NamespacedKey("hudboard", "name");
    public static final NamespacedKey PROFILE_KEY = new NamespacedKey("hudboard", "profile");
    /** Marks the ANCHOR item-frame (bottom-left for wall panels, top-left for
     *  flat panels). Used by the startup pre-repair to recover the real
     *  position when the yml coords are wrong (e.g. legacy z=0). */
    public static final NamespacedKey ANCHOR_KEY = new NamespacedKey("hudboard", "anchor");
    public static final NamespacedKey TX_KEY = new NamespacedKey("hudboard", "tx");
    public static final NamespacedKey TY_KEY = new NamespacedKey("hudboard", "ty");

    public InfoPanelManager(HudBoardPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------ load / reload

    public void loadAll() {
        profiles.clear();
        viewsByProfile.clear();
        File dir = new File(plugin.getDataFolder(), "panels");
        if (!dir.exists()) {
            dir.mkdirs();
            // Default panel pack: 3 PNG profiles + 3 shared GIF assets, split
            // into per-format subfolders so a server with 20+ custom panels
            // stays scannable in the file browser.
            new File(dir, "png").mkdirs();
            new File(dir, "gif").mkdirs();
            new File(dir, "jpg").mkdirs();
            plugin.saveResource("panels/png/info-hub.png", false);
            plugin.saveResource("panels/png/info-hub.yml", false);
            plugin.saveResource("panels/png/info-economy.png", false);
            plugin.saveResource("panels/png/info-economy.yml", false);
            plugin.saveResource("panels/png/info-combat.png", false);
            plugin.saveResource("panels/png/info-combat.yml", false);
            plugin.saveResource("panels/png/info-mini.png", false);
            plugin.saveResource("panels/png/info-mini.yml", false);
            plugin.saveResource("panels/png/info-clock.png", false);
            plugin.saveResource("panels/png/info-clock.yml", false);
            plugin.saveResource("panels/png/info-player.png", false);
            plugin.saveResource("panels/png/info-player.yml", false);
            plugin.saveResource("panels/gif/aurora.gif", false);
            plugin.saveResource("panels/gif/pulse.gif", false);
            plugin.saveResource("panels/gif/particles.gif", false);
            plugin.saveResource("panels/gif/info-aurora.yml", false);
            plugin.saveResource("panels/gif/info-pulse.yml", false);
        }
        // Back-compat: any old flat-layout yml (panels/foo.yml without
        // subfolder) gets auto-migrated into panels/png/foo.yml on first
        // startup. We do this BEFORE scanning so the migration doesn't get
        // re-detected as "stray" on the next run.
        migrateFlatLayout(dir);
        // Clean up stray "*.<ext>.yml" files. These were created by an old
        // bug where loadOne() only stripped the .png extension, so for GIFs
        // the id became "petit.gif" and saves wrote to "petit.gif.yml" instead
        // of "petit.yml". For each stray file, if the official "<name>.yml"
        // exists and is NEWER than the stray, the stray is junk and can be
        // deleted. But if the stray is NEWER (i.e. the user has been editing
        // since the fix), we copy its contents over the official yml FIRST,
        // then delete the stray — otherwise the user would lose their last
        // edits. Either way the duplicate is gone after startup.
        int cleaned = 0;
        int rescued = 0;
        for (File sub : Objects.requireNonNullElse(dir.listFiles(), new File[0])) {
            if (!sub.isDirectory()) continue;
            for (File f : Objects.requireNonNullElse(sub.listFiles(), new File[0])) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                String ext = null;
                if (n.endsWith(".png.yml")) ext = ".png.yml";
                else if (n.endsWith(".gif.yml")) ext = ".gif.yml";
                else if (n.endsWith(".jpg.yml")) ext = ".jpg.yml";
                else if (n.endsWith(".jpeg.yml")) ext = ".jpeg.yml";
                if (ext == null) continue;
                String base = f.getName().substring(0, f.getName().length() - ext.length());
                File official = new File(sub, base + ".yml");
                if (official.exists()) {
                    if (f.lastModified() > official.lastModified()) {
                        try {
                            java.nio.file.Files.copy(f.toPath(), official.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            plugin.getLogger().info("[HudBoard] Rescued newer content from "
                                    + sub.getName() + "/" + f.getName() + " into " + official.getName());
                            rescued++;
                        } catch (java.io.IOException ex) {
                            plugin.getLogger().warning("[HudBoard] Could not rescue "
                                    + sub.getName() + "/" + f.getName() + ": " + ex.getMessage());
                        }
                    }
                    plugin.getLogger().warning("[HudBoard] Removing stray duplicate file: "
                            + sub.getName() + "/" + f.getName());
                    f.delete();
                    cleaned++;
                } else {
                    if (f.renameTo(official)) {
                        plugin.getLogger().info("[HudBoard] Renamed stray "
                                + sub.getName() + "/" + f.getName() + " to " + official.getName());
                        rescued++;
                    } else {
                        plugin.getLogger().warning("[HudBoard] Could not rename stray "
                                + sub.getName() + "/" + f.getName());
                    }
                }
            }
        }
        if (cleaned > 0) plugin.getLogger().info("[HudBoard] Cleaned " + cleaned + " stray file(s) from panels/.");
        if (rescued > 0) plugin.getLogger().info("[HudBoard] Rescued " + rescued + " stray file(s).");
        // Walk every per-format subdir and load each (image, yml) pair.
        for (File sub : Objects.requireNonNullElse(dir.listFiles(), new File[0])) {
            if (!sub.isDirectory()) continue;
            String format = sub.getName().toLowerCase(Locale.ROOT); // "png" / "gif" / "jpg"
            for (File f : Objects.requireNonNullElse(sub.listFiles(), new File[0])) {
                String name = f.getName();
                // Accept .png / .gif / .jpg / .jpeg (ImageIO handles them all)
                String ext = null;
                for (String e : new String[]{".png", ".gif", ".jpg", ".jpeg"}) {
                    if (name.toLowerCase(Locale.ROOT).endsWith(e)) { ext = e; break; }
                }
                if (ext == null) continue;
                File yml = new File(sub, name.substring(0, name.length() - ext.length()) + ".yml");
                if (!yml.exists()) {
                    plugin.getLogger().warning("[HudBoard] " + sub.getName() + "/" + name
                            + " has no sidecar yml, skipping.");
                    continue;
                }
                try {
                    InfoPanel p = loadOne(f, yml);
                    profiles.put(p.id, p);
                    plugin.getLogger().info("[HudBoard] profile '" + p.id + "' " + p.tilesW + "x" + p.tilesH
                            + " (" + p.dataPoints.size() + " data points, format=" + format + ")");
                } catch (Exception e) {
                    plugin.getLogger().warning("[HudBoard] Failed to load " + sub.getName() + "/"
                            + name + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Back-compat migration: pre-3.0 layouts stored everything flat in
     * {@code panels/} (panels/foo.png + panels/foo.yml + panels/foo.gif).
     * 3.0+ requires per-format subfolders (panels/png/foo.{png,yml} etc.).
     * On first startup, walk the root {@code panels/} for any image+yml
     * pair that doesn't already live in a subfolder, figure out the format
     * from the image extension, and move the pair into the right subdir.
     */
    private void migrateFlatLayout(File panelsDir) {
        for (File f : Objects.requireNonNullElse(panelsDir.listFiles(), new File[0])) {
            if (f.isDirectory()) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            String imgExt = null;
            for (String e : new String[]{".png", ".gif", ".jpg", ".jpeg"}) {
                if (n.endsWith(e)) { imgExt = e; break; }
            }
            String subdir = null;
            if (imgExt != null) {
                subdir = imgExt.substring(1).equals("jpeg") ? "jpg" : imgExt.substring(1);
            } else if (n.endsWith(".yml")) {
                // Standalone yml in root: try to find a matching image next to it
                String base = n.substring(0, n.length() - 4);
                for (String e : new String[]{".png", ".gif", ".jpg", ".jpeg"}) {
                    File probe = new File(panelsDir, base + e);
                    if (probe.isFile()) {
                        imgExt = e;
                        subdir = e.equals(".jpeg") ? "jpg" : e.substring(1);
                        break;
                    }
                }
            }
            if (subdir == null) continue;
            File targetDir = new File(panelsDir, subdir);
            if (!targetDir.isDirectory()) targetDir.mkdirs();
            File target = new File(targetDir, f.getName());
            if (target.exists()) {
                // Conflict (e.g. v3 default pack already saved): overwrite to
                // keep the user's manual edits safe.
                if (f.lastModified() > target.lastModified()) {
                    try {
                        java.nio.file.Files.move(f.toPath(), target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        plugin.getLogger().info("[HudBoard] Migrated flat file " + f.getName()
                                + " → " + subdir + "/" + f.getName() + " (overwriting older default)");
                    } catch (java.io.IOException ex) {
                        plugin.getLogger().warning("[HudBoard] Could not migrate " + f.getName()
                                + ": " + ex.getMessage());
                    }
                } else {
                    f.delete();
                    plugin.getLogger().info("[HudBoard] Removed flat file " + f.getName()
                            + " (newer default in " + subdir + "/)");
                }
            } else {
                try {
                    java.nio.file.Files.move(f.toPath(), target.toPath());
                    plugin.getLogger().info("[HudBoard] Migrated flat file " + f.getName()
                            + " → " + subdir + "/" + f.getName());
                } catch (java.io.IOException ex) {
                    plugin.getLogger().warning("[HudBoard] Could not migrate " + f.getName()
                            + ": " + ex.getMessage());
                }
            }
        }
    }

    /**
     * Create a blank profile: a new image (PNG / GIF / JPG) + sidecar yml.
     * The image is solid for JPG, transparent for PNG/GIF. The yml has a
     * skeleton with example data points whose text is blank for GIF (so the
     * GIF shows through) or has example placeholders for PNG/JPG.
     *
     * @return the loaded profile, or null if the name is already taken.
     */
    public InfoPanel createBlankProfile(String name, String format, int tilesW, int tilesH) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        if (profiles.containsKey(id)) return null;
        if (tilesW < 1 || tilesH < 1 || tilesW > 10 || tilesH > 10) return null;
        if (!format.equals("png") && !format.equals("gif") && !format.equals("jpg") && !format.equals("jpeg")) return null;

        File dir = new File(plugin.getDataFolder(), "panels");
        if (!dir.exists()) dir.mkdirs();
        // Per-format subfolder (panels/png/, panels/gif/, panels/jpg/) so a
        // server with 20+ custom panels stays browsable in a file manager.
        String formatSubdir = format.equals("jpeg") ? "jpg" : format;
        File subdir = new File(dir, formatSubdir);
        if (!subdir.exists()) subdir.mkdirs();
        File imgFile = new File(subdir, id + "." + format);
        File ymlFile = new File(subdir, id + ".yml");
        if (imgFile.exists() || ymlFile.exists()) return null;

        // Generate the blank image
        int pixelW = tilesW * 128, pixelH = tilesH * 128;
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(pixelW, pixelH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            if (format.startsWith("jpg")) {
                g.setColor(new java.awt.Color(0x1A1A2E));
                g.fillRect(0, 0, pixelW, pixelH);
            } else {
                // PNG/GIF: transparent (default)
                g.setColor(new java.awt.Color(0, 0, 0, 0));
                g.fillRect(0, 0, pixelW, pixelH);
            }
            g.dispose();
            javax.imageio.ImageIO.write(img, format.equals("jpeg") ? "jpg" : format, imgFile);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("[HudBoard] Could not create blank image: " + ex.getMessage());
            return null;
        }

        // Generate the yml
        org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
        cfg.set("name", prettify(id));
        cfg.set("description", "Custom panel " + id);
        cfg.set("permission", "hudboard.use");
        cfg.set("tiles-w", tilesW);
        cfg.set("tiles-h", tilesH);
        // For GIF: data points exist but have empty text → the GIF shows through
        // For PNG/JPG: data points have example placeholders the user can edit
        boolean gifMode = format.equals("gif");
        String exampleText = gifMode ? " " : "%server_name%";
        // Add 4 example data points spread across the panel
        int[][] corners = {
                {tilesW / 2, 0, 64, 24},       // top center
                {tilesW / 2, tilesH - 1, 64, 96}, // bottom center
                {0, tilesH / 2, 24, 64},        // mid left
                {tilesW - 1, tilesH / 2, 24, 64} // mid right
        };
        String[] keys = {"title", "footer", "left_info", "right_info"};
        for (int i = 0; i < 4; i++) {
            String k = keys[i];
            int tx = corners[i][0], ty = corners[i][1], x = corners[i][2], y = corners[i][3];
            // Make sure corners fit within the panel
            if (tx >= tilesW) tx = tilesW - 1;
            if (ty >= tilesH) ty = tilesH - 1;
            String dp = "data-points." + k;
            cfg.set(dp + ".tile-x", tx);
            cfg.set(dp + ".tile-y", ty);
            cfg.set(dp + ".x", x);
            cfg.set(dp + ".y", y);
            cfg.set(dp + ".text", exampleText);
            cfg.set(dp + ".size", 14);
            cfg.set(dp + ".color", gifMode ? 0xFFFFFF : 0xC8AA6E);
        }
        try {
            cfg.save(ymlFile);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("[HudBoard] Could not create yml: " + ex.getMessage());
            imgFile.delete();
            return null;
        }

        // Load the new profile
        try {
            InfoPanel p = loadOne(imgFile, ymlFile);
            profiles.put(p.id, p);
            plugin.getLogger().info("[HudBoard] Created profile '" + p.id + "' " + p.tilesW + "x" + p.tilesH + " (" + format + ")");
            return p;
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("[HudBoard] Could not load new profile: " + ex.getMessage());
            return null;
        }
    }

    private InfoPanel loadOne(File png, File yml) throws IOException {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(yml);
        // Lazily read the image dimensions without decoding the full pixel
        // buffer — this avoids loading 6+ MB of pixels for a 10x10 panel that
        // may never be placed. The image is decoded on first render via
        // InfoPanel#getImage().
        java.awt.image.BufferedImage dimProbe = ImageIO.read(png);
        int imgW = dimProbe != null ? dimProbe.getWidth() : 128;
        int imgH = dimProbe != null ? dimProbe.getHeight() : 128;
        dimProbe = null;  // let GC reclaim; the real image is loaded on demand
        InfoPanel p = new InfoPanel();
        // Strip ANY supported image extension. The old code only stripped
        // .png, so for a GIF file named "petit.gif" the id would be
        // "petit.gif" — and then every save would write the yml to
        // "petit.gif.yml" instead of "petit.yml", creating a duplicate file
        // alongside the real one.
        String baseName = png.getName().toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".png", ".gif", ".jpg", ".jpeg"}) {
            if (baseName.endsWith(ext)) {
                baseName = baseName.substring(0, baseName.length() - ext.length());
                break;
            }
        }
        p.id = baseName;
        p.name = cfg.getString("name", prettify(p.id));
        p.description = cfg.getString("description", "");
        p.permission = cfg.getString("permission", "hudboard.use");
        // v2.4.0: per-panel disabled-worlds override (null = follow global config)
        List<String> dw = cfg.getStringList("disabled-worlds");
        p.disabledWorlds = (dw == null || dw.isEmpty()) ? null : dw;
        p.file = png;
        // p.image stays null — lazy load
        p.tilesW = cfg.getInt("tiles-w", (imgW + 127) / 128);
        p.tilesH = cfg.getInt("tiles-h", (imgH + 127) / 128);

        ConfigurationSection dp = cfg.getConfigurationSection("data-points");
        if (dp != null) {
            for (String key : dp.getKeys(false)) {
                ConfigurationSection s = dp.getConfigurationSection(key);
                if (s == null) continue;
                InfoPanel.DataPoint d = new InfoPanel.DataPoint();
                d.key = key;
                d.tileX = s.getInt("tile-x", 0);
                d.tileY = s.getInt("tile-y", 0);
                d.x = s.getInt("x", 0);
                d.y = s.getInt("y", 0);
                d.text = s.getString("text", "");
                // v3.0 migration: if text still contains an inline
                // <gradient:...>...</gradient> (the old "double color entry"
                // format), extract it to base-color and clean the text. This
                // is what produced the `</gra` artifact on tile overflow: the
                // </gradient> closing tag got sliced by the segment path and
                // rendered as literal text on the next tile.
                migrateOldGradientFormat(d);
                d.size = s.getInt("size", 12);
                d.color = s.getInt("color", 0xFFFFFF);
                d.baseColor = s.getString("base-color", null);
                d.animation = s.getString("animation", null);
                d.animColor = s.getInt("anim-color", 0xFF8888);
                d.animMs = s.getLong("anim-ms", 1500L);
                // v2.3.0: new visual fields (all optional — older profiles
                // load with the defaults declared on the DataPoint class).
                d.bg = s.getString("bg", null);
                d.align = s.getString("align", "left");
                d.padding = s.getInt("padding", 0);
                // v2.4.1: alignMode for cross-tile alignment scope.
                d.alignMode = s.getString("align-mode", "tile");
                p.dataPoints.add(d);
            }
        }
        ConfigurationSection ups = cfg.getConfigurationSection("placeholders");
        if (ups != null) {
            for (String k : ups.getKeys(false)) {
                p.userPlaceholders.put(k.toLowerCase(Locale.ROOT), ups.getString(k, ""));
            }
        }
        // Sanity-check the placeholders used by this panel: anything that's
        // not a HudBoard built-in, not in userPlaceholders, and not in the
        // runtime store will be resolved through PlaceholderAPI. We log a
        // one-line hint per panel so the admin can spot a typo or a missing
        // PAPI expansion at startup (instead of at runtime, when the map
        // shows "%placeholder_name%" literally).
        checkPanelPlaceholders(p);
        return p;
    }

    /**
     * Walk every data point of a panel and list the placeholders that will
     * require a PAPI expansion to resolve. HudBoard built-ins and the panel's
     * own userPlaceholders are excluded. The hint is logged once per panel
     * (not per data point) to keep the console quiet.
     */
    private void checkPanelPlaceholders(InfoPanel p) {
        java.util.Set<String> papiNeeded = new java.util.TreeSet<>();
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile("%([a-zA-Z0-9_]+)%");
        for (InfoPanel.DataPoint dp : p.dataPoints) {
            if (dp.text == null) continue;
            java.util.regex.Matcher m = pat.matcher(dp.text);
            while (m.find()) {
                String key = m.group(1).toLowerCase(Locale.ROOT);
                if (com.hudboard.data.DataManager.isBuiltinPlaceholder(key)) continue;
                if (p.userPlaceholders.containsKey(key)) continue;
                papiNeeded.add(key);
            }
        }
        if (papiNeeded.isEmpty()) return;
        if (!plugin.getDataManager().hasPapi()) {
            // PAPI is now a hard dependency, so this branch is mostly a safety
            // net for tests / shadow-loaded plugin instances.
            plugin.getLogger().warning("[HudBoard] Panel '" + p.id + "' uses "
                    + papiNeeded.size() + " non-builtin placeholder(s) but PlaceholderAPI is unavailable: "
                    + papiNeeded);
        } else {
            plugin.getLogger().info("[HudBoard] Panel '" + p.id + "' relies on PAPI expansion(s) for: "
                    + papiNeeded + " — make sure the expansion is installed.");
        }
    }

    /**
     * v3.0 migration: extract inline {@code <gradient:...>...</gradient>}
     * from a data point's text into its {@code base-color} field. The old
     * "double color entry" format put the gradient tag in {@code text},
     * which the per-char path handled fine but the segment path used to
     * slice mid-tag on overflow (producing the visible {@code </gra} or
     * {@code </gre} artifact). Stripping it from {@code text} and putting
     * it in {@code base-color} is the new convention and avoids the bug
     * regardless of which path runs.
     *
     * <p>If {@code base-color} is already set, the inline gradient is just
     * removed (its color was being doubled anyway). If multiple gradients
     * are nested, only the outermost is migrated — the inner one is left
     * alone (rare in practice).</p>
     */
    private void migrateOldGradientFormat(InfoPanel.DataPoint d) {
        if (d == null || d.text == null) return;
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "(?is)<\\s*gradient\\s*:\\s*([^>]+?)\\s*>\\s*(.*?)\\s*<\\s*/\\s*gradient\\s*>");
        java.util.regex.Matcher m = pat.matcher(d.text);
        if (!m.find()) return;
        String gradOpen = "<gradient:" + m.group(1).trim() + ">";
        String inner = m.group(2);
        String before = d.text.substring(0, m.start());
        String after = d.text.substring(m.end());
        // Reconstruct: surrounding text + inner content (collapses surrounding whitespace)
        d.text = (before + inner + after).replaceAll("\\s+", " ").trim();
        if (d.baseColor == null || d.baseColor.isBlank()) {
            d.baseColor = gradOpen;
            plugin.getLogger().info("[HudBoard] Migrated data point '"
                    + d.key + "' to v3 format: gradient moved to base-color.");
        } else {
            plugin.getLogger().info("[HudBoard] Stripped inline gradient from data point '"
                    + d.key + "' (base-color was already set).");
        }
        // Recurse in case there are multiple gradient pairs
        migrateOldGradientFormat(d);
    }

    private static String prettify(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("[-_]")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    public Set<String> getProfileIds() { return Collections.unmodifiableSet(profiles.keySet()); }
    public InfoPanel get(String id) {
        if (id == null) return null;
        return profiles.get(id.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------ save (write yml back to disk)

    /**
     * Persist the given profile's data points back to its yml file on disk.
     * This is the "Save & Apply" action triggered by the panel editor GUI.
     * After saving, every placed instance of the profile is invalidated so
     * the new data shows on the next render.
     */
    public boolean saveProfileToDisk(InfoPanel profile) {
        if (profile == null || profile.file == null) return false;
        File yml = new File(profile.file.getParentFile(), profile.id + ".yml");
        org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
        cfg.set("name", profile.name);
        cfg.set("description", profile.description);
        cfg.set("permission", profile.permission);
        cfg.set("tiles-w", profile.tilesW);
        cfg.set("tiles-h", profile.tilesH);
        if (profile.disabledWorlds != null && !profile.disabledWorlds.isEmpty()) {
            cfg.set("disabled-worlds", profile.disabledWorlds);
        }
        cfg.set("user-placeholders", null);
        cfg.set("placeholders", null);
        for (var entry : profile.userPlaceholders.entrySet()) {
            cfg.set("placeholders." + entry.getKey(), entry.getValue());
        }
        cfg.set("data-points", null);
        for (InfoPanel.DataPoint dp : profile.dataPoints) {
            String base = "data-points." + dp.key;
            cfg.set(base + ".tile-x", dp.tileX);
            cfg.set(base + ".tile-y", dp.tileY);
            cfg.set(base + ".x", dp.x);
            cfg.set(base + ".y", dp.y);
            cfg.set(base + ".text", dp.text);
            cfg.set(base + ".size", dp.size);
            cfg.set(base + ".color", dp.color);
            if (dp.baseColor != null && !dp.baseColor.isBlank()) {
                cfg.set(base + ".base-color", dp.baseColor);
            }
            if (dp.animation != null) {
                cfg.set(base + ".animation", dp.animation);
                cfg.set(base + ".anim-color", dp.animColor);
                cfg.set(base + ".anim-ms", dp.animMs);
            }
            // v2.3.0: persist the new visual fields. We always write
            // them (even when at default) so the yml is the canonical
            // source of truth and admins can hand-edit if needed.
            if (dp.bg != null && !dp.bg.isBlank()) cfg.set(base + ".bg", dp.bg);
            if (!"left".equalsIgnoreCase(dp.align)) cfg.set(base + ".align", dp.align);
            if (dp.padding != 0) cfg.set(base + ".padding", dp.padding);
            if (!"tile".equalsIgnoreCase(dp.alignMode)) cfg.set(base + ".align-mode", dp.alignMode);
        }
        try {
            cfg.save(yml);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("[HudBoard] Could not save panel yml: " + ex.getMessage());
            return false;
        }
        // Invalidate every placed instance of this profile so the new data
        // shows on the next render. We also clear the per-renderer cache.
        for (InfoPanelInstance inst : placed.values()) {
            if (inst.profile == profile) {
                invalidate(inst);
                // Re-crop the static background if the image changed
                for (org.bukkit.map.MapView v : inst.views) {
                    if (v == null) continue;
                    for (var r : v.getRenderers()) {
                        if (r instanceof com.hudboard.panel.InfoPanelRenderer ipr) {
                            ipr.invalidateAllViewers();
                            ipr.onProfileReload();
                        }
                    }
                }
            }
        }
        plugin.getLogger().info("[HudBoard] Saved profile '" + profile.id + "' (" + profile.dataPoints.size() + " data points)");
        return true;
    }

    // ------------------------------------------------------ place / remove

    /**
     * Place a panel at the location the admin is looking at.
     * Returns the placed instance name, or null on failure.
     */
    public String placeAt(Player admin, String profileId, Block target, BlockFace face) {
        return placeAt(admin, profileId, target, face, null);
    }

    /**
     * Place a panel with a specific instance name. If name is null, auto-generates
     * a friendly one like "info-hub-1", "info-hub-2", ...
     */
    public String placeAt(Player admin, String profileId, Block target, BlockFace face, String name) {
        if (admin == null || !admin.hasPermission("hudboard.place")) return null;
        InfoPanel profile = get(profileId);
        if (profile == null) return null;
        if (!admin.hasPermission(profile.permission)) {
            admin.sendMessage(mm("<red>No permission for profile <gold>" + profile.id + "</gold>.</red>"));
            return null;
        }
        ConfigManager cfg = plugin.getConfigManager();
        if (cfg.disabledWorlds().contains(admin.getWorld().getName())) {
            admin.sendMessage(mm("<red>Panels are disabled in this world.</red>"));
            return null;
        }
        if (profile.tilesW > cfg.maxTilesPerSide() || profile.tilesH > cfg.maxTilesPerSide()) {
            admin.sendMessage(mm("<red>Profile is too large (max <gold>" + cfg.maxTilesPerSide() + "x" + cfg.maxTilesPerSide() + "</gold>).</red>"));
            return null;
        }
        // Cooldown
        long now = System.currentTimeMillis();
        long last = plugin.getCooldowns().getOrDefault(admin.getUniqueId(), 0L);
        if (now - last < cfg.placeCooldownMs()) {
            long left = (cfg.placeCooldownMs() - (now - last)) / 1000;
            admin.sendMessage(mm("<red>Wait <gold>" + left + "s</gold> before placing another panel.</red>"));
            return null;
        }
        plugin.getCooldowns().put(admin.getUniqueId(), now);

        if (name == null || name.isEmpty()) name = nextFriendlyName(profile.id);
        else if (placed.containsKey(name)) {
            admin.sendMessage(mm("<red>A panel named <gold>" + name + "</gold> already exists. Pick another name.</red>"));
            return null;
        }
        // v2.4.0: auto-snap. If the targeted face conflicts with the
        // profile's natural orientation (e.g. admin looks UP but the
        // profile is meant for a wall), walk up to N blocks in the
        // player's look direction to find a block whose face matches.
        // Falls back to the original target if nothing better is found.
        com.hudboard.util.PlacementSnap.SnappedPlace sp =
                com.hudboard.util.PlacementSnap.snap(target, face, profile, cfg.autoSnapMaxBlocks());
        target = sp.block;
        face = sp.face;
        // Create the instance first (empty views), mint the maps with the instance
        // so the renderer can read its current mode for mode-specific PNG lookup.
        InfoPanelInstance inst = new InfoPanelInstance(this, plugin, name, profile, new MapView[profile.tilesW * profile.tilesH]);
        // Pre-set the world so mintMaps creates the MapViews in the right
        // world (not Bukkit.getWorlds().get(0) by default).
        inst.world = target.getWorld().getName();
        MapView[] views = mintMaps(inst);
        viewsByProfile.put(profile.id, views);
        inst.setViews(views);
        int placed = inst.placeAt(target, face, admin);
        if (placed == -1) {
            admin.sendMessage(mm("<red>You are standing on that block. <dark_gray>Step back and look at a wall, or use <gold>/hudboard here</gold> for an in-air placement.</dark_gray></red>"));
            return null;
        }
        if (placed <= 0) {
            admin.sendMessage(mm("<red>Could not place the panel there. Check that the wall has enough room.</red>"));
            return null;
        }
        this.placed.put(name, inst);
        plugin.savePlaced();
        return name;
    }

    /** Friendly auto-name: "<profile>-N" where N is the lowest unused integer. */
    private String nextFriendlyName(String profileId) {
        int n = 1;
        while (placed.containsKey(profileId + "-" + n)) n++;
        return profileId + "-" + n;
    }

    /**
     * Place a panel flat (horizontally) on the floor (ceiling=false) or
     * ceiling (ceiling=true), directly under/over the admin.
     * Returns the instance name, or null on failure.
     */
    public String placeFlat(Player admin, String profileId, String name, boolean ceiling) {
        if (admin == null || !admin.hasPermission("hudboard.place")) return null;
        InfoPanel profile = get(profileId);
        if (profile == null) return null;
        if (!admin.hasPermission(profile.permission)) {
            admin.sendMessage(mm("<red>No permission for profile <gold>" + profile.id + "</gold>.</red>"));
            return null;
        }
        ConfigManager cfg = plugin.getConfigManager();
        if (cfg.disabledWorlds().contains(admin.getWorld().getName())) {
            admin.sendMessage(mm("<red>Panels are disabled in this world.</red>"));
            return null;
        }
        if (profile.tilesW > cfg.maxTilesPerSide() || profile.tilesH > cfg.maxTilesPerSide()) {
            admin.sendMessage(mm("<red>Profile is too large (max <gold>" + cfg.maxTilesPerSide() + "x" + cfg.maxTilesPerSide() + "</gold>).</red>"));
            return null;
        }
        long now = System.currentTimeMillis();
        long last = plugin.getCooldowns().getOrDefault(admin.getUniqueId(), 0L);
        if (now - last < cfg.placeCooldownMs()) {
            long left = (cfg.placeCooldownMs() - (now - last)) / 1000;
            admin.sendMessage(mm("<red>Wait <gold>" + left + "s</gold> before placing another panel.</red>"));
            return null;
        }
        plugin.getCooldowns().put(admin.getUniqueId(), now);

        if (name == null || name.isEmpty()) name = nextFriendlyName(profile.id);
        else if (placed.containsKey(name)) {
            admin.sendMessage(mm("<red>A panel named <gold>" + name + "</gold> already exists. Pick another name.</red>"));
            return null;
        }
        // For floor: target is the block UNDER the player's feet
        // For ceiling: target is the block ABOVE the player's head
        Location pLoc = admin.getLocation();
        Block target = ceiling
                ? pLoc.getBlock().getRelative(BlockFace.UP)
                : pLoc.getBlock().getRelative(BlockFace.DOWN);
        if (target == null || target.getType().isAir()) {
            // Need a solid anchor block. For floor, the block below feet must be solid.
            // For ceiling, the block above head must be solid.
            if (ceiling) {
                admin.sendMessage(mm("<red>Need a solid block above your head for a ceiling panel.</red>"));
            } else {
                admin.sendMessage(mm("<red>Need a solid block under your feet for a floor panel. Stand on the floor and try again.</red>"));
            }
            return null;
        }
        // Create the instance first, then mint the maps with the instance
        // so the renderer can read its current mode for mode-specific PNG lookup.
        InfoPanelInstance inst = new InfoPanelInstance(this, plugin, name, profile, new MapView[profile.tilesW * profile.tilesH]);
        // Pre-set the world so mintMaps creates the MapViews in the right world.
        inst.world = admin.getWorld().getName();
        MapView[] views = mintMaps(inst);
        viewsByProfile.put(profile.id, views);
        inst.setViews(views);
        int placed = inst.placeFlat(target, ceiling, admin);
        if (placed == -1) {
            admin.sendMessage(mm("<red>You are standing in the wrong place for a flat panel.</red>"));
            return null;
        }
        if (placed <= 0) {
            admin.sendMessage(mm("<red>Could not place the flat panel. Check that the area around you is clear.</red>"));
            return null;
        }
        this.placed.put(name, inst);
        plugin.savePlaced();
        return name;
    }

    /**
     * Compute the BlockFace the panel should face so the given player sees
     * the front of it. Returns null if the player is on the same block
     * (degenerate case) or the panel is in another world.
     */
    private BlockFace faceFromPlayer(InfoPanelInstance inst, Player p) {
        World w = Bukkit.getWorld(inst.world);
        if (w == null || !p.getWorld().getName().equals(inst.world)) return null;
        // Center of the panel grid
        int cx = inst.x;
        int cy = inst.y + (inst.profile.tilesH / 2);
        int cz = inst.z;
        int px = p.getLocation().getBlockX();
        int py = (int) Math.round(p.getLocation().getY());
        int pz = p.getLocation().getBlockZ();
        int dx = px - cx;
        int dz = pz - cz;
        if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) return null; // same block
        // The block-face the item-frame should have so it points toward the player
        // is the OPPOSITE of the direction from the panel to the player.
        // (Item-frame facing = direction its visible side looks toward.)
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? BlockFace.WEST : BlockFace.EAST;
        } else {
            return dz > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
        }
    }

    /** Rename a placed instance. The old name is removed from the map. */
    public boolean renameInstance(String oldName, String newName) {
        if (oldName == null || newName == null) return false;
        if (oldName.equals(newName)) return true;
        if (placed.containsKey(newName)) return false;
        InfoPanelInstance inst = placed.remove(oldName);
        if (inst == null) return false;
        inst.name = newName;
        // Re-tag every tile with the new name
        for (PanelTileEntity tile : inst.tiles) {
            if (tile != null && tile.isValid()) {
                tile.getPersistentDataContainer().set(NAME_KEY, PersistentDataType.STRING, newName);
            }
        }
        placed.put(newName, inst);
        plugin.savePlaced();
        return true;
    }

    public boolean remove(String name) {
        InfoPanelInstance inst = placed.remove(name);
        if (inst == null) {
            // Even if not tracked, scan the world for any orphan item-frames
            // that still claim to belong to this name. This handles the case
            // where the panel was wiped but the chunk data still has the frames.
            return nukeOrphansForName(name) > 0;
        }
        inst.remove();
        // Free the panel's image from RAM once no instances are using it
        long freed = freeImageIfUnused(inst.profile);
        nukeOrphansForName(name);
        plugin.savePlaced();
        if (freed > 0) plugin.getLogger().info("[HudBoard] Freed " + (freed / 1024) + " KB from " + inst.profile.id);
        return true;
    }

    public int removeAll() {
        int n = 0;
        long totalFreed = 0;
        for (InfoPanelInstance inst : placed.values()) { inst.remove(); n++; }
        placed.clear();
        // Belt-and-suspenders: kill any remaining tagged tile entities that
        // the tracked instances didn't own (orphans left over from prior
        // sessions or failed placements). ItemFrame = wall, ItemDisplay = flat.
        for (World w : Bukkit.getWorlds()) {
            for (var e : w.getEntities()) {
                if (!(e instanceof ItemFrame) && !(e instanceof ItemDisplay) && !(e instanceof Shulker)) continue;
                if (!e.getPersistentDataContainer().has(MARKER, PersistentDataType.STRING)) continue;
                e.remove();
                n++;
            }
        }
        // Free every profile's image from RAM
        for (InfoPanel p : profiles.values()) totalFreed += p.unloadImages();
        if (totalFreed > 0) {
            long mb = totalFreed / (1024 * 1024);
            plugin.getLogger().info("[HudBoard] Freed " + mb + " MB of panel image cache.");
        }
        plugin.savePlaced();
        return n;
    }

    /**
     * Try to recover the face direction of a placed panel by looking at any
     * live item-frames it owns. Returns the name of the BlockFace ("NORTH",
     * "SOUTH", etc.) or null if no live frame could be read. Used by
     * savePlaced as a fallback when {@code inst.face} is null/blank/invalid
     * (which used to be saved as "UNKNOWN" and crashed the next load with
     * BlockFace.valueOf("UNKNOWN") → IllegalArgumentException, making all
     * panels show as orphan on the next tick).
     */
    public String deriveFaceFromFrames(com.hudboard.panel.InfoPanelInstance inst) {
        if (inst == null || inst.tiles == null) return null;
        for (PanelTileEntity tile : inst.tiles) {
            if (tile == null || !tile.isValid()) continue;
            try {
                org.bukkit.block.BlockFace dir = tile.getFacing();
                if (dir == null) continue;
                String n = dir.name();
                if ("NORTH".equals(n) || "SOUTH".equals(n) || "EAST".equals(n)
                        || "WEST".equals(n) || "UP".equals(n) || "DOWN".equals(n)) {
                    return n;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Free the image of `profile` if no placed instance references it. */
    private long freeImageIfUnused(InfoPanel profile) {
        if (profile == null) return 0;
        for (InfoPanelInstance inst : placed.values()) {
            if (inst.profile == profile) return 0;
        }
        return profile.unloadImages();
    }

    /** Remove every tagged tile entity that has the given panel name, in every world. */
    private int nukeOrphansForName(String panelName) {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (var e : w.getEntities()) {
                if (!(e instanceof ItemFrame) && !(e instanceof ItemDisplay) && !(e instanceof Shulker)) continue;
                if (!e.getPersistentDataContainer().has(MARKER, PersistentDataType.STRING)) continue;
                String n2 = e.getPersistentDataContainer().get(NAME_KEY, PersistentDataType.STRING);
                if (panelName.equals(n2)) {
                    e.remove();
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Remove every panel (and its item-frames) within `radius` blocks of `origin`.
     * Returns the number of panels removed. Also drops the placed.yml entries.
     */
    public int removeWithin(Location origin, double radius) {
        if (origin == null || origin.getWorld() == null) return 0;
        var it = placed.entrySet().iterator();
        int n = 0;
        while (it.hasNext()) {
            var e = it.next();
            InfoPanelInstance inst = e.getValue();
            if (!inst.world.equals(origin.getWorld().getName())) continue;
            Location center = new Location(origin.getWorld(), inst.x + 0.5, inst.y + 0.5, inst.z + 0.5);
            if (center.distanceSquared(origin) <= radius * radius) {
                inst.remove();
                it.remove();
                n++;
            }
        }
        if (n > 0) plugin.savePlaced();
        return n;
    }

    /**
     * Nuke EVERY HudBoard item-frame (tagged or not, in case older builds left
     * un-tagged orphans) in every world, plus the placed.yml cache.
     * Returns the number of item-frames removed.
     */
    public int nuke(int maxRadius, int maxPerChunk) {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            // 1) Tracked + tagged tile entities (in case some are NOT in `placed`).
            //    ItemFrame = wall tiles, ItemDisplay = floor/ceiling tiles.
            for (var e : w.getEntities()) {
                if (!(e instanceof ItemFrame) && !(e instanceof ItemDisplay) && !(e instanceof Shulker)) continue;
                if (!e.getPersistentDataContainer().has(MARKER, PersistentDataType.STRING)) continue;
                if (maxRadius > 0) {
                    Location ol = null;
                    for (var entry : placed.entrySet()) {
                        var inst = entry.getValue();
                        if (inst.world == null || !inst.world.equals(w.getName())) continue;
                        ol = new Location(w, inst.x + 0.5, inst.y + 0.5, inst.z + 0.5);
                        if (ol.distanceSquared(e.getLocation()) <= maxRadius * maxRadius) break;
                        ol = null;
                    }
                    if (ol == null) continue;
                }
                e.remove();
                n++;
            }
            // 2) Untagged orphans in nearby chunks (older builds left these).
            //    Only ItemFrames can hold maps; ItemDisplays with maps are always
            //    HudBoard-tagged (since they don't exist in vanilla), so we only
            //    need to scan ItemFrames here for the legacy heuristic.
            if (maxRadius > 0) {
                for (var chunk : w.getLoadedChunks()) {
                    if (maxPerChunk > 0) {
                        int local = 0;
                        for (var e : chunk.getEntities()) {
                            if (!(e instanceof ItemFrame f)) continue;
                            if (f.getPersistentDataContainer().has(MARKER, PersistentDataType.STRING)) continue;
                            try {
                                var stack = f.getItem();
                                if (stack != null && stack.getType() == org.bukkit.Material.FILLED_MAP) {
                                    f.remove();
                                    n++;
                                    local++;
                                    if (maxPerChunk > 0 && local >= maxPerChunk) break;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }
        placed.clear();
        plugin.savePlaced();
        return n;
    }

    /**
     * Wipe EVERY item-frame on the server that has our marker, in every world.
     * Used on boot to make sure stale item-frames from a previous plugin version
     * don't conflict with newly-placed ones.
     */
    public int wipeAllOnServer() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (var e : w.getEntities()) {
                if (!(e instanceof ItemFrame) && !(e instanceof ItemDisplay) && !(e instanceof Shulker)) continue;
                if (e.getPersistentDataContainer().has(MARKER, PersistentDataType.STRING)) {
                    e.remove();
                    n++;
                }
            }
        }
        return n;
    }

    public void removeByWorld(World w) {
        var it = placed.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().world.equals(w)) { e.getValue().remove(); it.remove(); }
        }
        plugin.savePlaced();
    }

    public InfoPanelInstance get(String name, boolean placed) { return this.placed.get(name); }
    public Map<String, InfoPanelInstance> allPlaced() { return placed; }

    /** Public-API: get a placed panel by id (returns null if not placed). */
    public InfoPanelInstance getPlaced(String name) { return this.placed.get(name); }

    /** Public-API: list all placed panel ids. */
    public java.util.Set<String> placedIds() { return java.util.Collections.unmodifiableSet(this.placed.keySet()); }

    /** Public-API: invalidate a single instance (force re-render). */
    public void invalidate(InfoPanelInstance inst) {
        if (inst == null) return;
        for (MapView v : inst.views) {
            if (v == null) continue;
            for (var r : v.getRenderers()) {
                if (r instanceof InfoPanelRenderer ipr) ipr.invalidateAllViewers();
            }
        }
    }

    /** Public-API: invalidate every placed panel. */
    public void invalidateAll() {
        for (InfoPanelInstance inst : placed.values()) invalidate(inst);
    }

    public void setRefresh(String name, int seconds) {
        InfoPanelInstance inst = placed.get(name);
        if (inst == null) return;
        inst.refreshSec = Math.max(1, seconds);
        plugin.savePlaced();
    }

    /** Pending move: when the admin clicks the "Move" button in the panel GUI
     *  and then right-clicks a target block, we move the panel there. */
    private final java.util.Map<UUID, String> pendingMoves = new java.util.concurrent.ConcurrentHashMap<>();
    public void beginMoveMode(org.bukkit.entity.Player p, InfoPanelInstance inst) {
        pendingMoves.put(p.getUniqueId(), inst.name);
    }
    public String consumePendingMove(java.util.UUID playerId) {
        return pendingMoves.remove(playerId);
    }
    public boolean hasPendingMove(java.util.UUID playerId) {
        return pendingMoves.containsKey(playerId);
    }

    /**
     * Move a placed panel to a new wall position. Removes the old item-frames
     * and re-places them at the new spot. Auto-centers vertically based on the
     * target block (like {@link #placeAt}).
     * @return true if the move succeeded.
     */
    public boolean moveTo(InfoPanelInstance inst, org.bukkit.block.Block target, org.bukkit.block.BlockFace face) {
        if (inst == null || inst.profile == null) return false;
        // Auto-center vertically
        int shiftDown = inst.profile.tilesH / 2;
        org.bukkit.block.Block centered = target.getRelative(org.bukkit.block.BlockFace.DOWN, shiftDown);
        // Tear down current tile entities
        for (PanelTileEntity tile : inst.tiles) {
            if (tile != null && tile.isValid()) tile.remove();
        }
        return moveInternal(inst, centered, face);
    }

    /**
     * Shift a placed panel by N blocks in the given horizontal direction.
     * Used by the arrow buttons in the editor GUI. Removes and re-spawns
     * the item-frames at the new position.
     * @return true if the move succeeded.
     */
    public boolean shiftPanelBy(InfoPanelInstance inst, org.bukkit.block.BlockFace dir, int blocks) {
        if (inst == null || inst.profile == null) return false;
        if (dir == org.bukkit.block.BlockFace.UP || dir == org.bukkit.block.BlockFace.DOWN) return false;
        // Remove existing tile entities
        for (PanelTileEntity tile : inst.tiles) {
            if (tile != null && tile.isValid()) tile.remove();
        }
        // Compute the new anchor. Use the same wall face and shift the center.
        org.bukkit.block.BlockFace face = org.bukkit.block.BlockFace.valueOf(inst.face);
        if (face == org.bukkit.block.BlockFace.UP || face == org.bukkit.block.BlockFace.DOWN) return false;
        int tilesW = inst.profile.tilesW, tilesH = inst.profile.tilesH;
        // The current anchor block: we need to find it. We stored the anchor
        // as x, y, z (the support block coords). Move it `blocks` units along `dir`.
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(inst.world);
        if (w == null) return false;
        org.bukkit.block.Block oldAnchor = w.getBlockAt(inst.x, inst.y, inst.z);
        org.bukkit.block.Block newAnchor = oldAnchor.getRelative(dir, blocks);
        if (newAnchor.getType() == org.bukkit.Material.AIR) {
            // Look for a solid block in the new spot
            newAnchor = findWallBlock(newAnchor, face);
            if (newAnchor == null) {
                // No wall in that direction; restore original by re-spawning
                org.bukkit.block.Block fallback = findWallBlock(oldAnchor, face);
                if (fallback == null) return false;
                newAnchor = fallback;
            }
        }
        int shiftDown = tilesH / 2;
        org.bukkit.block.Block centered = newAnchor.getRelative(org.bukkit.block.BlockFace.DOWN, shiftDown);
        return moveInternal(inst, centered, face);
    }

    private static org.bukkit.block.Block findWallBlock(org.bukkit.block.Block origin, org.bukkit.block.BlockFace face) {
        for (int i = 0; i < 5; i++) {
            org.bukkit.block.Block b = origin.getRelative(face.getOppositeFace(), i);
            if (b.getType().isSolid() && !b.getType().isAir()) return b;
        }
        return null;
    }

    private boolean moveInternal(InfoPanelInstance inst, org.bukkit.block.Block target, org.bukkit.block.BlockFace face) {
        org.bukkit.World w = target.getWorld();
        org.bukkit.Location anchor = target.getLocation().add(face.getDirection().multiply(0.5));
        int tilesW = inst.profile.tilesW, tilesH = inst.profile.tilesH;
        // moveInternal is wall-only (caller checks). Wall tiles use ItemFrame.
        for (int ty = 0; ty < tilesH; ty++) {
            for (int tx = 0; tx < tilesW; tx++) {
                int idx = ty * tilesW + tx;
                org.bukkit.block.Block spot = target.getRelative(face.getOppositeFace(), tx).getRelative(org.bukkit.block.BlockFace.DOWN, ty);
                org.bukkit.Location frameLoc = spot.getLocation().add(face.getDirection().multiply(0.5));
                org.bukkit.entity.ItemFrame f = w.spawn(frameLoc, ItemFrame.class, (entity) -> {
                    org.bukkit.entity.ItemFrame ff = (org.bukkit.entity.ItemFrame) entity;
                    ff.setFacingDirection(face, true);
                    ff.setFixed(true);
                    ff.setInvulnerable(true);
                    ff.setVisible(false);
                    ff.setSilent(true);
                    var pdc = ff.getPersistentDataContainer();
                    pdc.set(InfoPanelManager.MARKER, PersistentDataType.STRING, "hudboard");
                    pdc.set(InfoPanelManager.NAME_KEY, PersistentDataType.STRING, inst.name);
                });
                org.bukkit.map.MapView view = org.bukkit.Bukkit.createMap(w);
                view.setScale(org.bukkit.map.MapView.Scale.CLOSEST);
                for (var r : view.getRenderers()) view.removeRenderer(r);
                view.addRenderer(new com.hudboard.panel.InfoPanelRenderer(plugin, inst, tx, ty));
                org.bukkit.inventory.ItemStack mapItem = new org.bukkit.inventory.ItemStack(org.bukkit.Material.FILLED_MAP);
                org.bukkit.inventory.meta.MapMeta mapMeta = (org.bukkit.inventory.meta.MapMeta) mapItem.getItemMeta();
                if (mapMeta != null) {
                    mapMeta.setMapView(view);
                    mapItem.setItemMeta(mapMeta);
                }
                f.setItem(mapItem);
                PanelTileItemFrame tile = new PanelTileItemFrame(f);
                tile.makeSecure();
                inst.tiles[idx] = tile;
                inst.views[idx] = view;
                try {
                    java.lang.reflect.Method m = view.getClass().getMethod("render");
                    m.invoke(view);
                } catch (Throwable ignored) {}
            }
        }
        inst.world = w.getName();
        inst.x = anchor.getBlockX();
        inst.y = anchor.getBlockY();
        inst.z = anchor.getBlockZ();
        inst.face = face.name();
        plugin.savePlaced();
        invalidate(inst);
        return true;
    }

    // ------------------------------------------------------ tick

    /**
     * 20Hz tick: force-update animated GIF frames to all viewers. Runs every
     * server tick (50ms) so GIFs play smoothly even though Bukkit's natural
     * map render cycle is 250-500ms. Only does work for panels that have an
     * active animated GIF and at least one recent viewer.
     */
    public void tickGifFrames() {
        if (!com.hudboard.nms.MapDirectSender.isAvailable()) return;
        if (placed.isEmpty()) return;
        // v2.0 (narrow): build a quick "worlds with at least one online
        // player" set once per tick, so we can short-circuit panels whose
        // world is currently empty (very common on multi-world servers
        // where most players are in the survival world but 3 panels sit
        // in the creative plot).
        java.util.Set<String> activeWorlds = null;
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline()) continue;
            if (activeWorlds == null) activeWorlds = new java.util.HashSet<>();
            activeWorlds.add(p.getWorld().getName());
        }
        if (activeWorlds == null) return; // no players online at all
        for (var e : placed.entrySet()) {
            InfoPanelInstance inst = e.getValue();
            if (inst == null || inst.views == null) continue;
            if (inst.world == null || !activeWorlds.contains(inst.world)) continue;
            // v2.4.0: per-panel disabled-worlds check. If the panel's
            // profile lists its current world as disabled, skip it
            // entirely. null/empty list = follow the global config.
            java.util.List<String> panelDisabled = inst.profile.disabledWorlds;
            if (panelDisabled != null && panelDisabled.contains(inst.world)) continue;
            // Force-send the current frame for every panel. forceFrameToViewers
            // is now a no-op for static panels (which would otherwise flicker
            // — see the docstring in InfoPanelRenderer). It still does the
            // fast NMS direct send for animated GIFs, which need it because
            // Bukkit's natural map render cycle is too slow.
            for (org.bukkit.map.MapView v : inst.views) {
                if (v == null) continue;
                for (var r : v.getRenderers()) {
                    if (r instanceof InfoPanelRenderer ipr) {
                        ipr.forceFrameToViewers();
                    }
                }
            }
        }
    }

    /**
     * Tick every panel once per second. Heavy work is skipped automatically
     * when no player is within {@link ConfigManager#panelViewDistance} blocks
     * of the panel — this avoids wasted CPU on idle panels in distant areas
     * of the map (e.g. 100+ placed panels in a hub with 5 active viewers).
     */
    public void tick() {
        if (placed.isEmpty()) return;
        long now = System.currentTimeMillis();
        ConfigManager cfg = plugin.getConfigManager();
        int viewDist = cfg.panelViewDistance();
        int viewDistSq = viewDist * viewDist;
        // Snapshot online players once per tick (used by visibility culling)
        Player[] online = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        if (online.length == 0) {
            // Nobody online: still evict stale per-player caches every 30s
            if (now - lastGlobalEvictMs >= 30_000L) {
                lastGlobalEvictMs = now;
                forEachRenderer(r -> r.evictOlderThan(60_000L));
            }
            return;
        }
        for (var e : placed.entrySet()) {
            String name = e.getKey();
            InfoPanelInstance inst = e.getValue();
            if (inst == null) continue;
            // Visibility culling: if no player is within range, skip everything
            // (still tracked, just no work). This is the single biggest
            // perf win for servers with many placed panels.
            if (!anyPlayerInRange(inst, online, viewDistSq)) continue;
            // follow viewer: every few ticks, rotate the panel to face the
            // nearest player within 12 blocks. Skip for flat panels (UP/DOWN).
            if (cfg.panelFollow() && !inst.face.equals("UP") && !inst.face.equals("DOWN")) {
                long lastFollow = lastFollowRotate.getOrDefault(name, 0L);
                if (now - lastFollow >= 500L) { // 2 Hz follow
                    lastFollowRotate.put(name, now);
                    Player nearest = inst.nearestPlayer(12, online);
                    if (nearest != null) {
                        BlockFace newFace = faceFromPlayer(inst, nearest);
                        if (newFace != null && !newFace.name().equals(inst.face)) {
                            inst.face = newFace.name();
                            // Re-rotate every wall tile (ItemFrame) to the new face.
                            // ItemDisplay tiles don't have a single cardinal facing
                            // and aren't used for follow-viewer rotation anyway.
                            for (PanelTileEntity tile : inst.tiles) {
                                if (tile != null && tile.isValid()
                                        && tile.getEntity() instanceof org.bukkit.entity.ItemFrame f) {
                                    f.setFacingDirection(newFace);
                                }
                            }
                        }
                    }
                }
            }
            // refresh
            long lastRef = lastRefresh.getOrDefault(name, 0L);
            if (now - lastRef < inst.refreshSec * 1000L) continue;
            lastRefresh.put(name, now);
            // Force each renderer to invalidate the per-player cache
            for (MapView v : inst.views) {
                if (v == null) continue;
                for (var r : v.getRenderers()) {
                    if (r instanceof InfoPanelRenderer ipr) ipr.invalidateAll();
                }
            }
        }
        // Global per-player cache eviction: walk all renderers every 5 min
        if (now - lastGlobalEvictMs >= 5 * 60_000L) {
            lastGlobalEvictMs = now;
            int maxCache = cfg.panelViewDistance() > 0 ? 32 : 16;  // cap per-renderer cache
            forEachRenderer(r -> { r.evictOlderThan(60_000L); r.capCacheSize(maxCache); });
            // Stale image eviction: drop panel images that haven't been
            // accessed in over 5 minutes. Frees significant RAM for large
            // panels that are placed but not actively viewed.
            long freed = 0;
            for (InfoPanel p : profiles.values()) {
                if (p.isStale(5 * 60_000L)) freed += p.unloadImages();
            }
            if (freed > 0) {
                long mb = freed / (1024 * 1024);
                plugin.getLogger().info("[HudBoard] Periodic eviction freed " + mb + " MB of panel images.");
            }
        }
    }

    private long lastGlobalEvictMs = 0L;

    /** True if at least one player is within viewDist blocks of the panel center. */
    private static boolean anyPlayerInRange(InfoPanelInstance inst, Player[] online, int viewDistSq) {
        World w = Bukkit.getWorld(inst.world);
        if (w == null) return false;
        int cx = inst.x + (inst.profile.tilesW / 2);
        int cy = inst.y + (inst.profile.tilesH / 2);
        int cz = inst.z;
        for (Player p : online) {
            if (!p.getWorld().equals(w)) continue;
            int dx = p.getLocation().getBlockX() - cx;
            int dy = p.getLocation().getBlockY() - cy;
            int dz = p.getLocation().getBlockZ() - cz;
            if (dx * dx + dy * dy + dz * dz <= viewDistSq) return true;
        }
        return false;
    }

    private void forEachRenderer(java.util.function.Consumer<InfoPanelRenderer> action) {
        for (InfoPanelInstance inst : placed.values()) {
            if (inst == null) continue;
            for (MapView v : inst.views) {
                if (v == null) continue;
                for (var r : v.getRenderers()) {
                    if (r instanceof InfoPanelRenderer ipr) action.accept(ipr);
                }
            }
        }
    }

    // ------------------------------------------------------ internal

    private String newName() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Create the per-tile MapViews for an instance. The maps are created
     * in the panel's world, NOT in Bukkit.getWorlds().get(0) — using the
     * wrong world leaves the maps empty (no chunk, no renderer) which is
     * invisible to the player.
     */
    private MapView[] mintMaps(InfoPanelInstance inst) {
        InfoPanel profile = inst.profile;
        MapView[] out = new MapView[profile.tilesW * profile.tilesH];
        // Pick the right world. If the instance has a stored world name and
        // that world is loaded, use it. Otherwise fall back to the first
        // loaded world.
        World w = null;
        if (inst.world != null) w = Bukkit.getWorld(inst.world);
        if (w == null) w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (w == null) return out;  // no worlds — empty arrays
        for (int ty = 0; ty < profile.tilesH; ty++) {
            for (int tx = 0; tx < profile.tilesW; tx++) {
                int idx = ty * profile.tilesW + tx;
                MapView view = Bukkit.createMap(w);
                view.setScale(MapView.Scale.FARTHEST);
                view.setTrackingPosition(false);
                view.setUnlimitedTracking(false);
                view.getRenderers().forEach(view::removeRenderer);
                view.addRenderer(new InfoPanelRenderer(plugin, inst, tx, ty));
                out[idx] = view;
            }
        }
        return out;
    }

    private net.kyori.adventure.text.Component mm(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(s);
    }

    // ------------------------------------------------------ persistence

    public void registerPlacedFromDisk(Map<String, PlacedData> data) {
        // Note: we no longer wipe ALL item-frames here. Item-frames persist in
        // chunk data across server restarts, so wiping them just to re-place
        // causes a window where the panel is invisible (the wipe happens, the
        // re-place might fail if the chunk isn't loaded yet, leaving nothing).
        // Instead: try to re-attach to existing item-frames first, only spawn
        // new ones when the originals are missing.
        for (var e : data.entrySet()) {
            String name = e.getKey();
            PlacedData d = e.getValue();
            if (d.profileId == null || d.profileId.isBlank()) {
                // Already cleaned in HudBoardPlugin.loadPlacedFromDisk — this is a
                // safety net for any other caller. The yml is the source of truth.
                plugin.getLogger().warning("[HudBoard] Placed entry '" + name + "' has no profile id; skipping (entry is corrupted or refers to a deleted profile).");
                continue;
            }
            InfoPanel profile = get(d.profileId);
            if (profile == null) {
                plugin.getLogger().warning("[HudBoard] Profile not found for placed panel: " + d.profileId + " (panel '" + name + "')");
                continue;
            }
            World w = Bukkit.getWorld(d.world);
            if (w == null) {
                plugin.getLogger().warning("[HudBoard] World not found for placed panel: " + d.world + " (panel '" + name + "')");
                continue;
            }

            // Pre-repair: scan the world for any tagged item-frame belonging to
            // this panel. If the yml's coords don't match (e.g. legacy bug
            // where z was never saved, leaving z=0), use the real frame's
            // position as the truth. This MUST happen BEFORE we spawn fresh
            // frames, otherwise we'd read the freshly-spawned (wrong) frames
            // and "repair" to the wrong location.
            int[] real = findAnchorFramePos(w, name, d.face);
            if (real != null && (real[0] != d.x || real[1] != d.y || real[2] != d.z)) {
                plugin.getLogger().info("[HudBoard] Pre-repair '" + name
                        + "': was (" + d.x + "," + d.y + "," + d.z
                        + "), now (" + real[0] + "," + real[1] + "," + real[2] + ")");
                d.x = real[0]; d.y = real[1]; d.z = real[2];
                plugin.savePlaced();
            }

            Block b = w.getBlockAt(d.x, d.y, d.z);
            // Validate face BEFORE calling BlockFace.valueOf (see retryDeferredPlacements
            // for the same defensive check — a previous build wrote "UNKNOWN" as a
            // sentinel and crashed the load with IllegalArgumentException).
            if (!"NORTH".equals(d.face) && !"SOUTH".equals(d.face) && !"EAST".equals(d.face)
                    && !"WEST".equals(d.face) && !"UP".equals(d.face) && !"DOWN".equals(d.face)) {
                plugin.getLogger().warning("[HudBoard] Panel '" + name + "' has invalid face '"
                        + d.face + "' in placed.yml; defaulting to NORTH. Edit the file to fix.");
                d.face = "NORTH";
            }
            boolean flat = "UP".equals(d.face) || "DOWN".equals(d.face);
            // v2.4.0: multi-tile chunk check. A tilesW × tilesH panel
            // touches up to tilesW × tilesH chunks (one per block column).
            // We need ALL chunks on the panel's footprint to be loaded
            // before we can safely re-attach or spawn. The corners are
            // enough as a proxy — if all 4 corners (or 2 for wall) are
            // loaded, the centre chunks are too.
            int tilesW = profile != null ? profile.tilesW : 1;
            int tilesH = profile != null ? profile.tilesH : 1;
            int[] cornerX = {d.x, d.x + (flat ? tilesW - 1 : 0), d.x + (flat ? 0 : tilesW - 1)};
            int[] cornerZ = {d.z, d.z + (flat ? 0 : tilesH - 1), d.z + (flat ? tilesH - 1 : 0)};
            boolean anyChunkMissing = false;
            for (int cx : cornerX) {
                for (int cz : cornerZ) {
                    if (!w.isChunkLoaded(cx >> 4, cz >> 4)) { anyChunkMissing = true; break; }
                }
                if (anyChunkMissing) break;
            }
            if (anyChunkMissing) {
                pendingPlacements.put(name, d);
                Long lastWarn = chunkNotLoadedLogAt.get(name);
                long nowMs = System.currentTimeMillis();
                if (lastWarn == null || nowMs - lastWarn > 10_000L) {
                    plugin.getLogger().info("[HudBoard] Panel '" + name + "' chunk not loaded yet (re-load); deferring.");
                    chunkNotLoadedLogAt.put(name, nowMs);
                }
                continue;
            }
            // Try to find an existing tagged item-frame at this position (and
            // its neighbours, since the panel covers tilesW × tilesH blocks).
            InfoPanelInstance inst = new InfoPanelInstance(this, plugin, name, profile, new MapView[profile.tilesW * profile.tilesH]);
            inst.world = d.world;  // pre-set so mintMaps uses the right world
            // CRITICAL: copy the yml-saved x/y/z/face onto the instance. The
            // InfoPanelInstance ctor leaves these at their defaults (0, 0, 0,
            // null) and only `placeOn()` ever sets them — but on reload we
            // reattach to existing item-frames WITHOUT going through placeOn,
            // so without this copy the editor would show "z=0 Face=null"
            // for every panel, even though the in-world position is correct.
            inst.x = d.x;
            inst.y = d.y;
            inst.z = d.z;
            inst.face = d.face;
            MapView[] views = mintMaps(inst);
            viewsByProfile.put(profile.id, views);
            inst.setViews(views);
            inst.refreshSec = d.refreshSec;
            // First, look for any existing item-frames in our panel grid
            int reattached = inst.reattachFromWorld(w, b, flat);
            if (reattached == 0) {
                // No survivors — spawn fresh item-frames
                int ok = flat
                        ? inst.placeFlat(b, "DOWN".equals(d.face), null)
                        : inst.placeAt(b, BlockFace.valueOf(d.face), null);
                if (ok == 0) {
                    // Chunk not loaded (no air blocks visible). Defer.
                    plugin.getLogger().info("[HudBoard] Panel '" + name + "' spawn failed; deferring placement.");
                    pendingPlacements.put(name, d);
                }
            } else {
                // Reattached from existing frames — the yml's face value is
                // probably right, but if it was "UNKNOWN" or wrong (legacy
                // corruption), pull the real direction from the live frame
                // and persist it so the next savePlaced won't revert.
                String realFace = deriveFaceFromFrames(inst);
                if (realFace != null && !realFace.equals(inst.face)) {
                    plugin.getLogger().info("[HudBoard] Panel '" + name + "': face was '"
                            + inst.face + "', live frame says '" + realFace + "'. Adopting.");
                    inst.face = realFace;
                    d.face = realFace;
                }
            }
            placed.put(name, inst);
        }

        // NOTE: we do NOT sweep orphan item-frames here. The reason: we just
        // deferred several entries (their chunks aren't loaded yet). The
        // item-frames for those deferred entries would be wrongly classified
        // as orphans and removed before the deferred retry runs. The sweep
        // is now scheduled as a separate task that runs AFTER the deferred
        // placements have had a chance to resolve (see HudBoardPlugin's
        // onEnable).
    }

    /**
     * Find the real position of the anchor item-frame for a given panel name
     * by scanning the world. Returns [x, y, z] of the bottom-left (or
     * top-left for flat) tile, or null if no tagged frame is found.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Prefer the frame marked with {@link #ANCHOR_KEY} (set at
     *       placement time by {@code InfoPanelInstance.placeAt / placeFlat}).</li>
     *   <li>Otherwise, among ALL frames tagged with this panel name, pick
     *       the one with the smallest Y (wall panels) or smallest Z
     *       (flat panels) — the anchor is always at the lowest vertical
     *       extent of the panel.</li>
     * </ol>
     */
    private int[] findAnchorFramePos(World w, String panelName, String face) {
        if (w == null) return null;
        boolean flat = "UP".equals(face) || "DOWN".equals(face);
        org.bukkit.entity.Entity best = null;
        int bestY = Integer.MAX_VALUE;
        int bestZ = Integer.MAX_VALUE;
        for (org.bukkit.entity.Entity e : w.getEntitiesByClass(org.bukkit.entity.ItemFrame.class)) {
            var pdc = e.getPersistentDataContainer();
            if (!"hudboard".equals(pdc.get(MARKER, PersistentDataType.STRING))) continue;
            String n = pdc.get(NAME_KEY, PersistentDataType.STRING);
            if (n == null || !n.equals(panelName)) continue;
            String anchor = pdc.get(ANCHOR_KEY, PersistentDataType.STRING);
            if ("1".equals(anchor)) {
                Location loc = e.getLocation();
                return new int[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()};
            }
            int y = e.getLocation().getBlockY();
            int z = e.getLocation().getBlockZ();
            if (flat) {
                if (z < bestZ || (z == bestZ && y < bestY)) { best = e; bestY = y; bestZ = z; }
            } else {
                if (y < bestY || (y == bestY && z < bestZ)) { best = e; bestY = y; bestZ = z; }
            }
        }
        // Also scan ItemDisplay entities (floor/ceiling tiles)
        for (org.bukkit.entity.Entity e : w.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class)) {
            var pdc = e.getPersistentDataContainer();
            if (!"hudboard".equals(pdc.get(MARKER, PersistentDataType.STRING))) continue;
            String n = pdc.get(NAME_KEY, PersistentDataType.STRING);
            if (n == null || !n.equals(panelName)) continue;
            String anchor = pdc.get(ANCHOR_KEY, PersistentDataType.STRING);
            if ("1".equals(anchor)) {
                Location loc = e.getLocation();
                return new int[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()};
            }
            int y = e.getLocation().getBlockY();
            int z = e.getLocation().getBlockZ();
            if (flat) {
                if (z < bestZ || (z == bestZ && y < bestY)) { best = e; bestY = y; bestZ = z; }
            } else {
                if (y < bestY || (y == bestY && z < bestZ)) { best = e; bestY = y; bestZ = z; }
            }
        }
        if (best == null) return null;
        Location loc = best.getLocation();
        return new int[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()};
    }

    /**
     * Repair the stored (x, y, z) of every placed panel by reading the real
     * position of its tagged item-frame in the world. Fixes panels whose
     * stored position drifted (e.g. legacy yml that only had x/y but no z, so
     * the panel was re-spawned at z=0 on every restart). Called once after
     * startup, after the deferred placements have had a chance to resolve.
     */
    /**
     * v2.5.0: on-demand panel repair. Used by {@code /hudboard panel
     * repair <name>|all}. Re-attaches / re-spawns every tile of the
     * named panel, fixing positions from the live world state. Returns
     * true if any work was done (frames spawned or positions updated).
     */
    public boolean repairPlaced(String name) {
        InfoPanelInstance inst = placed.get(name);
        if (inst == null) return false;
        boolean changed = false;
        // Step 1: sync positions from any live tile (in case the
        // yml is out of date — same logic as repairPlacedCoordinates).
        PanelTileEntity anchor = null;
        if (inst.tiles != null) {
            for (PanelTileEntity tile : inst.tiles) {
                if (tile != null && tile.isValid()) { anchor = tile; break; }
            }
        }
        if (anchor != null) {
            org.bukkit.Location loc = anchor.getLocation();
            int rx = loc.getBlockX(), ry = loc.getBlockY(), rz = loc.getBlockZ();
            if (rx != inst.x || ry != inst.y || rz != inst.z) {
                plugin.getLogger().info("[HudBoard] Repair '" + name
                        + "': was (" + inst.x + "," + inst.y + "," + inst.z
                        + "), now (" + rx + "," + ry + "," + rz + ")");
                inst.x = rx; inst.y = ry; inst.z = rz;
                changed = true;
            }
        }
        // Step 2: invalidate the renderer cache so the new geometry
        // re-renders immediately on the next tick. We don't call
        // reattachFromWorld directly because that requires World/Block
        // args; the natural render tick (or retryDeferredPlacements for
        // missing chunks) will re-spawn any frames that are gone.
        invalidate(inst);
        // Schedule a deferred re-attach: any pending placements will
        // pick up the panel on their next sweep.
        if (inst.world != null) {
            org.bukkit.World w = Bukkit.getWorld(inst.world);
            if (w != null && w.isChunkLoaded(inst.x >> 4, inst.z >> 4)) {
                org.bukkit.block.Block origin = w.getBlockAt(inst.x, inst.y, inst.z);
                boolean flat = "UP".equals(inst.face) || "DOWN".equals(inst.face);
                int reattached = inst.reattachFromWorld(w, origin, flat);
                if (reattached > 0) {
                    changed = true;
                    plugin.getLogger().info("[HudBoard] Repair '" + name + "': re-spawned " + reattached + " frame(s).");
                }
            }
        }
        if (changed) plugin.savePlaced();
        return changed;
    }

    public void repairPlacedCoordinates() {
        if (placed.isEmpty()) return;
        int repaired = 0;
        for (var e : new java.util.ArrayList<>(placed.entrySet())) {
            String name = e.getKey();
            InfoPanelInstance inst = e.getValue();
            if (inst == null || inst.tiles == null) continue;
            PanelTileEntity anchorTile = null;
            for (PanelTileEntity tile : inst.tiles) {
                if (tile != null && tile.isValid()) { anchorTile = tile; break; }
            }
            if (anchorTile == null) continue;
            org.bukkit.Location loc = anchorTile.getLocation();
            int realX = loc.getBlockX();
            int realY = loc.getBlockY();
            int realZ = loc.getBlockZ();
            if (realX == inst.x && realY == inst.y && realZ == inst.z) continue;
            plugin.getLogger().info("[HudBoard] Repaired position of panel '" + name
                    + "': was (" + inst.x + "," + inst.y + "," + inst.z
                    + "), now (" + realX + "," + realY + "," + realZ + ")");
            inst.x = realX;
            inst.y = realY;
            inst.z = realZ;
            repaired++;
        }
        if (repaired > 0) {
            plugin.savePlaced();
            plugin.getLogger().info("[HudBoard] Repaired " + repaired + " panel position(s).");
        }
    }

    /**
     * Reposition an existing panel in-place (keeps its name) to a new
     * target block + face. Removes the current item-frames, spawns new
     * ones at the new position, and saves the new coords to placed.yml.
     * Used by {@code /hudboard setpos <name>}.
     */
    public boolean repositionTo(InfoPanelInstance inst, Block target, BlockFace face) {
        if (inst == null || inst.profile == null || target == null || face == null) return false;
        if (face == BlockFace.UP || face == BlockFace.DOWN) return false;
        // Remove the current tile entities at the wrong position
        if (inst.tiles != null) {
            for (PanelTileEntity tile : inst.tiles) {
                if (tile != null && tile.isValid()) tile.remove();
            }
            inst.clearTiles();
        }
        // Force-load the chunk at the new position
        try { target.getWorld().getChunkAt(target.getLocation()).load(true); } catch (Throwable ignored) {}
        // Spawn fresh frames at the new position
        int ok = inst.placeAt(target, face, null);
        if (ok <= 0) return false;
        // The instance is now at the new position; save it
        plugin.savePlaced();
        plugin.getLogger().info("[HudBoard] Repositioned panel '" + inst.name
                + "' to (" + inst.x + "," + inst.y + "," + inst.z + ") in " + inst.world);
        return true;
    }

    /**
     * Force-repair every placed panel by scanning the world for tagged
     * item-frames and re-attaching to the real ones (not the wrong ones
     * that the startup pre-repair might have spawned at z=0).
     *
     * <p>For each panel: remove any existing instance frames, re-scan the
     * world for the real anchor frame, and re-attach to it. If no real
     * frame is found, spawn fresh ones at the new position.
     *
     * <p>Best run AFTER the admin has walked around the world to load
     * the chunks where the real item-frames live. Triggered by
     * {@code /hudboard repair}.
     *
     * @return {@code int[2]} = {panels checked, panels repositioned}
     */
    public int[] forceRepairAll() {
        if (placed.isEmpty()) return new int[]{0, 0};
        int checked = 0;
        int repositioned = 0;

        // ----- PASS 1: pre-load a 3×3 chunk grid around every placed panel's
        // STORED (yml) position. This way, when we then call
        // findAnchorFramePos (which scans every loaded chunk for tagged item-
        // frames), the chunks where the real frames are most likely to live
        // are already loaded. The 3×3 grid is a 48×48-block area centred on
        // the yml position — enough to cover any 1-10 tile panel and a small
        // drift around the yml position. Panels that drifted further than
        // 16 blocks are still recoverable by walking near them and re-running.
        int totalLoaded = 0;
        for (var entry : new java.util.ArrayList<>(placed.entrySet())) {
            InfoPanelInstance inst = entry.getValue();
            if (inst == null || inst.world == null) continue;
            World w = Bukkit.getWorld(inst.world);
            if (w == null) continue;
            int cx = inst.x >> 4, cz = inst.z >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (w.getChunkAt(cx + dx, cz + dz).load(true)) totalLoaded++;
                }
            }
        }
        if (totalLoaded > 0) {
            plugin.getLogger().info("[HudBoard] Force-repair: pre-loaded " + totalLoaded + " chunk(s) for scan.");
        }

        // ----- PASS 2: now that the relevant chunks are loaded, run the
        // existing per-panel repair logic. After finding the real position
        // (if any), we re-load a chunk grid sized for the panel itself, so
        // the reattach can find every frame of a multi-tile panel.
        for (var entry : new java.util.ArrayList<>(placed.entrySet())) {
            String name = entry.getKey();
            InfoPanelInstance inst = entry.getValue();
            if (inst == null || inst.world == null) continue;
            World w = Bukkit.getWorld(inst.world);
            if (w == null) continue;
            checked++;

            int[] real = findAnchorFramePos(w, name, inst.face);
            if (real == null) continue;  // no real frames found anywhere
            if (real[0] == inst.x && real[1] == inst.y && real[2] == inst.z) continue;

            plugin.getLogger().info("[HudBoard] Repair '" + name
                    + "': was (" + inst.x + "," + inst.y + "," + inst.z
                    + "), now (" + real[0] + "," + real[1] + "," + real[2] + ")");

            // 1. Remove the current (wrong-position) tile entities
            if (inst.tiles != null) {
                for (PanelTileEntity tile : inst.tiles) {
                    if (tile != null && tile.isValid()) tile.remove();
                }
                inst.clearTiles();
            }
            // 2. Update the position
            inst.x = real[0];
            inst.y = real[1];
            inst.z = real[2];
            // 3. Re-attach to the real frames (or spawn fresh if none found).
            // First, force-load every chunk that the panel's tile grid might
            // span. A tilesW × tilesH panel can cover up to 4 chunks (when
            // the grid straddles a chunk boundary). Loading them all makes
            // sure reattachFromWorld can see every item-frame, not just the
            // ones in the anchor's chunk.
            Block b = w.getBlockAt(real[0], real[1], real[2]);
            boolean flat = "UP".equals(inst.face) || "DOWN".equals(inst.face);
            int tilesW = inst.profile.tilesW;
            int tilesH = inst.profile.tilesH;
            int minChunkX = (real[0]) >> 4;
            int maxChunkX = (real[0] + (flat ? tilesW - 1 : tilesW - 1)) >> 4;
            int minChunkZ = (real[2]) >> 4;
            int maxChunkZ = (real[2] + (flat ? tilesH - 1 : 0)) >> 4;
            // wall panels can also extend along Y (height), but Y is in the
            // same column — only X/Z change the chunk. For wall panels we
            // still want to load the chunk for any horizontal neighbours.
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    w.getChunkAt(cx, cz).load(true);
                }
            }
            int reattached = inst.reattachFromWorld(w, b, flat);
            if (reattached == 0) {
                // No real frames to attach to — spawn fresh at the new position
                try {
                    int ok = flat
                            ? inst.placeFlat(b, "DOWN".equals(inst.face), null)
                            : inst.placeAt(b, BlockFace.valueOf(inst.face), null);
                    if (ok > 0) {
                        plugin.getLogger().info("[HudBoard] Re-spawned '" + name + "' at the new position.");
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("[HudBoard] Re-spawn of '" + name + "' failed: " + t.getMessage());
                }
            }
            repositioned++;
        }
        if (repositioned > 0) {
            plugin.savePlaced();
            plugin.getLogger().info("[HudBoard] Force-repair: " + repositioned + " panel(s) repositioned.");
        }
        return new int[]{checked, repositioned};
    }

    /** Panels that failed to place because their target chunks weren't loaded yet. */
    private final Map<String, PlacedData> pendingPlacements = new java.util.HashMap<>();
    /** v1.4.1 — throttle the "chunk not loaded" log per panel so the
     *  natural render tick (every ~50ms) doesn't spam the console. */
    private final Map<String, Long> chunkNotLoadedLogAt = new java.util.HashMap<>();

    /** Number of panels currently waiting for their target chunk to load. */
    public int pendingPlacementsCount() { return pendingPlacements.size(); }

    /**
     * One-shot sweep: look for any item-frame with a HudBoard marker whose
     * name is not in the placed map. If we find an orphan:
     *   - if placed.yml has an entry for that name with matching coords, reattach
     *   - otherwise, drop it (it survived a previous session but is genuinely
     *     abandoned)
     * The callback runs after the sweep with the number of orphans found.
     */
    public int sweepOrphanFrames() { return sweepOrphanFrames(null); }

    /**
     * Purge HudBoard-tagged item-frames that have no matching entry in the
     * placed map and no recoverable profile. Optionally limit to a sphere
     * around {@code origin} of {@code radius} blocks. If {@code origin}
     * is null or {@code radius} is 0, scans every loaded chunk globally.
     */
    public int purgeOrphans(Location origin, int radius) {
        int n = 0;
        for (var w : Bukkit.getWorlds()) {
            for (var e : w.getEntities()) {
                if (!(e instanceof ItemFrame) && !(e instanceof ItemDisplay) && !(e instanceof Shulker)) continue;
                if (!e.getPersistentDataContainer().has(MARKER, PersistentDataType.STRING)) continue;
                if (placed.containsKey(
                        e.getPersistentDataContainer().get(NAME_KEY, PersistentDataType.STRING))) continue;
                if (radius > 0 && origin != null
                        && origin.getWorld() == w
                        && e.getLocation().distanceSquared(origin) > radius * (double) radius) continue;
                e.remove();
                n++;
            }
        }
        return n;
    }
    public int sweepOrphanFrames(java.util.function.IntConsumer onDone) {
        int orphans = 0;
        int recovered = 0;
        int kept = 0;
        java.util.Map<String, java.util.List<org.bukkit.entity.Entity>> byName = new java.util.HashMap<>();
        // First, scan all loaded chunks for tile entities (ItemFrame for walls,
        // ItemDisplay for floor/ceiling) with the HudBoard marker.
        for (var w : Bukkit.getWorlds()) {
            for (var e : w.getEntities()) {
                if (!(e instanceof ItemFrame) && !(e instanceof ItemDisplay) && !(e instanceof Shulker)) continue;
                if (!e.getPersistentDataContainer().has(MARKER, org.bukkit.persistence.PersistentDataType.STRING)) continue;
                String n = e.getPersistentDataContainer().get(NAME_KEY, org.bukkit.persistence.PersistentDataType.STRING);
                if (n == null) continue;
                if (placed.containsKey(n)) continue;
                byName.computeIfAbsent(n, k -> new java.util.ArrayList<>()).add(e);
            }
        }
        // Force-load chunks for every orphan group so we can reattach
        for (var entry : byName.entrySet()) {
            org.bukkit.entity.Entity first = entry.getValue().get(0);
            int cx = first.getLocation().getBlockX() >> 4;
            int cz = first.getLocation().getBlockZ() >> 4;
            first.getWorld().getChunkAt(cx, cz).load(true);
        }
        for (var entry : byName.entrySet()) {
            String name = entry.getKey();
            java.util.List<org.bukkit.entity.Entity> entities = entry.getValue();
            if (pendingPlacements.containsKey(name)) {
                kept += entities.size();
                continue;
            }
            InfoPanelInstance existing = placed.get(name);
            if (existing != null) {
                int ex = existing.x, ey = existing.y, ez = existing.z;
                for (org.bukkit.entity.Entity e : entities) {
                    int fx = e.getLocation().getBlockX();
                    int fy = e.getLocation().getBlockY();
                    int fz = e.getLocation().getBlockZ();
                    if (fx != ex || fy != ey || fz != ez) {
                        kept++;
                    }
                }
                continue;
            }
            String profileId = null;
            for (org.bukkit.entity.Entity e : entities) {
                String pid = e.getPersistentDataContainer().get(PROFILE_KEY, org.bukkit.persistence.PersistentDataType.STRING);
                if (pid != null) { profileId = pid; break; }
            }
            if (profileId != null) {
                InfoPanel profile = get(profileId);
                if (profile != null) {
                    org.bukkit.entity.Entity first = entities.get(0);
                    String world = first.getWorld().getName();
                    int x = first.getLocation().getBlockX();
                    int y = first.getLocation().getBlockY();
                    int z = first.getLocation().getBlockZ();
                    Block origin = first.getWorld().getBlockAt(x, y, z);
                    InfoPanelInstance inst = new InfoPanelInstance(this, plugin, name, profile,
                            new org.bukkit.map.MapView[profile.tilesW * profile.tilesH]);
                    inst.world = first.getWorld().getName();
                    MapView[] views = mintMaps(inst);
                    viewsByProfile.put(profile.id, views);
                    inst.setViews(views);
                    // Recovered panels always use the wall path for now; if the
                    // chunk has only ItemDisplay survivors the recovery will fail
                    // and the orphan will be cleaned up on the next sweep.
                    int reattached = inst.reattachFromWorld(first.getWorld(), origin, false);
                    if (reattached > 0) {
                        inst.world = world;
                        placed.put(name, inst);
                        plugin.savePlaced();
                        plugin.getLogger().info("[HudBoard] Recovered orphan panel '" + name + "' (" + reattached + " tile(s) reattached).");
                        recovered++;
                        continue;
                    }
                }
            }
            plugin.getLogger().warning("[HudBoard] Sweep removing " + entities.size()
                    + " orphan tile entity(s) for panel '" + name + "' (no matching placed/pending entry, no recoverable profile).");
            for (org.bukkit.entity.Entity e : entities) e.remove();
            orphans += entities.size();
        }
        if (recovered > 0) {
            plugin.getLogger().info("[HudBoard] Sweep recovered " + recovered + " panel(s) from world tile entities.");
        }
        if (kept > 0) {
            plugin.getLogger().info("[HudBoard] Sweep kept " + kept + " tile(s) — they belong to panels in deferreds or panels with stale positions. Use /hudboard repair if needed.");
        }
        if (orphans > 0) {
            plugin.getLogger().info("[HudBoard] Sweep removed " + orphans + " unrecoverable orphan tile entity(s).");
        }
        if (onDone != null) onDone.accept(orphans);
        return orphans;
    }

    /** Panels recovered from item-frames in the world when placed.yml is gone.
     *  Unused since sweepOrphanFrames now does the full recovery inline. */
    @SuppressWarnings("unused")
    private final java.util.Map<String, PlacedData> recoveredFromWorld = new java.util.HashMap<>();

    /**
     * Re-attempt placement of any panels whose target chunks weren't loaded
     * at boot time. Creates the InfoPanelInstance if needed, then tries to
     * re-attach to existing item-frames or spawn fresh ones. Force-loads
     * each chunk so we don't depend on a player visiting the area. Returns
     * the number of panels successfully placed.
     */
    public int retryDeferredPlacements() {
        int ok = 0;
        var it = pendingPlacements.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            String name = e.getKey();
            PlacedData d = e.getValue();
            World w = Bukkit.getWorld(d.world);
            if (w == null) continue;
            // Force-load the chunk. This is synchronous and ensures the
            // chunk is in memory so getNearbyEntities can find existing
            // item-frames. load() returns false if the chunk was already
            // loaded (not an error).
            int chunkX = d.x >> 4, chunkZ = d.z >> 4;
            w.getChunkAt(chunkX, chunkZ).load(true);  // addToTCS = true
            // If this is a panel on the floor/ceiling, the chunk below/above
            // might also need loading. For wall-mounted panels, the single
            // chunk is enough.
            Block b = w.getBlockAt(d.x, d.y, d.z);
            // Build the instance if it doesn't exist yet
            InfoPanelInstance inst = placed.get(name);
            if (inst == null) {
                InfoPanel profile = get(d.profileId);
                if (profile == null) continue;
                inst = new InfoPanelInstance(this, plugin, name, profile,
                        new org.bukkit.map.MapView[profile.tilesW * profile.tilesH]);
                inst.world = d.world;  // pre-set so mintMaps uses the right world
                // CRITICAL: copy x/y/z/face from the deferred entry. Without
                // this, the instance stays at (0,0,0,null) and the editor
                // would show "z=0 Face=null" for every deferred panel.
                inst.x = d.x;
                inst.y = d.y;
                inst.z = d.z;
                inst.face = d.face;
                MapView[] views = mintMaps(inst);
                viewsByProfile.put(profile.id, views);
                inst.setViews(views);
                inst.refreshSec = d.refreshSec;
            }
            boolean flat = "UP".equals(d.face) || "DOWN".equals(d.face);
            // Validate face before calling BlockFace.valueOf — a stale or
            // hand-edited placed.yml may have a bogus value (e.g. "UNKNOWN"
            // from a previous build) and valueOf() throws. Fall back to NORTH.
            String face = d.face;
            if (!"NORTH".equals(face) && !"SOUTH".equals(face) && !"EAST".equals(face)
                    && !"WEST".equals(face) && !"UP".equals(face) && !"DOWN".equals(face)) {
                plugin.getLogger().warning("[HudBoard] Panel '" + name + "' has invalid face '"
                        + face + "' in placed.yml; defaulting to NORTH. Edit the file to fix.");
                face = "NORTH";
                d.face = "NORTH";
                flat = false;
            }
            int reattached = inst.reattachFromWorld(w, b, flat);
            int placedCount = reattached;
            if (reattached == 0) {
                placedCount = flat
                        ? inst.placeFlat(b, "DOWN".equals(face), null)
                        : inst.placeAt(b, BlockFace.valueOf(face), null);
            }
            if (placedCount > 0) {
                placed.put(name, inst);
                it.remove();
                plugin.savePlaced();
                ok++;
            }
        }
        return ok;
    }

    public static class PlacedData {
        public String profileId;
        public String world;
        public int x, y, z;
        public String face;
        public int refreshSec = 1;
    }
}
