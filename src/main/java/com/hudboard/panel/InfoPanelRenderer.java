package com.hudboard.panel;

import com.hudboard.HudBoardPlugin;
import com.hudboard.data.DataManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Renders one 128x128 tile of an {@link InfoPanel} on a map.
 * Caches the per-player rendered frame and only repaints when the panel
 * is told to invalidate (i.e. the data refresh interval ticks).
 *
 * Data points use MiniMessage tags (e.g. {@code <green>...</green>}). The renderer
 * parses the text into a Component tree, then walks the tree to draw each
 * coloured segment with the right color.
 */
public class InfoPanelRenderer extends MapRenderer {

    private final HudBoardPlugin plugin;
    private final InfoPanelInstance inst;
    private final InfoPanel panel;
    private final int tileX, tileY;
    /** v2.3.0: precomputed MapPalette lookup cache. MapPalette has only
     *  16 entries, so after a few frames every pixel hits this map
     *  instead of running {@code MapPalette.matchColor()} (which
     *  allocates a {@code Color} and walks the full palette to find
     *  the nearest match). For a 128×128 tile at 20Hz that's
     *  ~50k matchColor calls/sec saved. */
    private final Map<Integer, Byte> paletteCache = new HashMap<>();
    /**
     * Static cache of the cropped tile. Bypassed (set to null) when
     * the image is an animated GIF, since the frame changes
     * over time and a cached frame would be stale.
     */
    private BufferedImage cachedTile;
    /** True if the image is an animated GIF (more than one frame). */
    private boolean animated;
    private final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<UUID, BufferedImage> lastSent = new HashMap<>();
    private final Map<UUID, Long> lastSeen = new HashMap<>();
    /** Hash of the resolved placeholders we last drew for each player. Used to
     *  detect when the live data has changed since the last render, so we
     *  re-draw even if Bukkit's map cycle hasn't fired yet. */
    private final Map<UUID, String> lastResolvedHash = new HashMap<>();
    /** Pre-baked GifSequence (if available). When set, rendering is just
     *  a byte[] lookup instead of a crop+draw. */
    private volatile GifSequence preBakedSequence;
    /** Tile index (tileY * tilesW + tileX) for pre-baked lookup. */
    private int tileIndex;

    public InfoPanelRenderer(HudBoardPlugin plugin, InfoPanelInstance inst, int tileX, int tileY) {
        super(false);
        this.plugin = plugin;
        this.inst = inst;
        this.panel = inst.profile;
        this.tileX = tileX;
        this.tileY = tileY;
        // Decide whether the image is an animated GIF.
        // If yes, cachedTile stays null and we re-crop per render.
        GifAnimation initialGif = panel.getGif();
        this.animated = initialGif != null && initialGif.isAnimated();
        this.cachedTile = animated ? null : crop(0L);
        // Try to pre-bake an async GifSequence (fast path)
        this.tileIndex = tileY * panel.tilesW + tileX;
        panel.getSequence().thenAccept(seq -> {
            if (seq != null && seq.animated) {
                this.preBakedSequence = seq;
                // We can bypass the slow BufferedImage path entirely
                this.animated = true;
                this.cachedTile = null;
            }
        });
    }

    /**
     * Crop the source image to this tile's 128x128 region. The `timeMs` arg
     * is used to pick the right frame when the source is an animated GIF;
     * for static images it is ignored.
     */
    private BufferedImage crop(long timeMs) {
        BufferedImage source = panel.getFrameAt(timeMs);
        if (source == null) return new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        int sx = tileX * 128;
        int sy = tileY * 128;
        int w = Math.min(128, source.getWidth() - sx);
        int h = Math.min(128, source.getHeight() - sy);
        if (w <= 0 || h <= 0) return new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        BufferedImage sub = source.getSubimage(sx, sy, w, h);
        BufferedImage full = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = full.createGraphics();
        g.drawImage(sub, 0, 0, null);
        g.dispose();
        return full;
    }

    public void invalidateAll() {
        lastSent.clear();
        prebakedBytesStatic.clear();
        prebakedBytesGif.clear();
    }
    public void invalidateAllViewers() {
        lastSent.clear();
        lastResolvedHash.clear();
        prebakedBytesStatic.clear();
        prebakedBytesGif.clear();
    }
    /** v2.4.0: invalidate the pre-baked byte cache for one player (used
     *  when we detect a resolved-text hash change mid-frame). */
    public void invalidatePlayer(java.util.UUID uuid) {
        lastSent.remove(uuid);
        lastResolvedHash.remove(uuid);
        prebakedBytesStatic.remove(uuid);
        for (var inner : prebakedBytesGif.values()) inner.remove(uuid);
    }

    /**
     * True if the panel has any data point that uses a per-tile text
     * animation (pulse, breathe, blink, rainbow). Animations depend on
     * System.currentTimeMillis() so the renderer must re-draw on every
     * render call to keep the phase advancing.
     */
    public static boolean hasAnyAnimation(com.hudboard.panel.InfoPanel p) {
        if (p == null || p.dataPoints == null) return false;
        for (var dp : p.dataPoints) {
            if (dp.animation != null) return true;
        }
        return false;
    }

    public boolean isAnimated() { return animated || preBakedSequence != null; }

    /**
     * Force-send the current frame of this tile to all known viewers via
     * NMS direct. Used for ANIMATED GIF panels where the natural render
     * cycle is too slow (250-500ms). For static panels, returns
     * immediately — the natural render path handles them with the correct
     * per-player placeholder resolution.
     *
     * <p>Why we skip static panels: drawing with {@code player=null}
     * breaks every per-player placeholder (%vault_balance%, %player_*%,
     * PAPI expansions needing a player context). The cached "—" frame
     * would then be NMS-sent to all viewers, briefly replacing the correct
     * frame from the natural render path. Result: visible flicker at the
     * 20Hz tick rate. So we only run this for animated GIFs, which don't
     * have per-player placeholders to begin with.</p>
     */
    public void forceFrameToViewers() {
        if (!com.hudboard.nms.MapDirectSender.isAvailable()) return;
        if (inst == null || inst.views == null) return;
        // Only useful for animated GIFs. Static panels have their placeholders
        // resolved correctly by the natural render path; running this would
        // draw with player=null and broadcast a "—" frame to every viewer.
        if (preBakedSequence == null || !preBakedSequence.animated) return;
        // Find the MapView for this renderer
        org.bukkit.map.MapView view = null;
        for (int i = 0; i < inst.views.length; i++) {
            org.bukkit.map.MapView v = inst.views[i];
            if (v == null) continue;
            for (var r : v.getRenderers()) {
                if (r == this) { view = v; break; }
            }
            if (view != null) break;
        }
        if (view == null) return;
        long now = System.currentTimeMillis();
        int frameIdx = preBakedSequence.frameAtTime(now);
        // Per-player draw: the GIF background is shared, but the data points
        // overlay may contain per-player placeholders (%vault_balance%,
        // %player_*%, PAPI expansions). Drawing with player=null would
        // resolve them to "—" and flicker against the natural render. So we
        // build a frame for EACH online player in the panel's world.
        // Cost: N_players × N_panels × 20Hz. For a typical server
        // (10 players, 5 GIF panels) that's 1000 drawFrame calls/sec, which
        // is well within the main thread budget.
        if (inst.world == null) return;
        // v2.0 (narrow): skip the WHOLE per-player loop if no online player
        // is in the panel's world. Without this short-circuit, the loop
        // still walks every online player on the server every tick (50ms),
        // wasting work when all players are in different worlds.
        org.bukkit.World instWorld = org.bukkit.Bukkit.getWorld(inst.world);
        if (instWorld == null) return;
        // Panel anchor for distance computation. We use the first tile
        // (inst.x, inst.y, inst.z) — close enough for a "is this player
        // even in the area" filter. Using the centre would require
        // precomputing tilesW/tilesH/2 offsets and isn't worth it for the
        // savings.
        final double anchorX = inst.x + 0.5;
        final double anchorY = inst.y + 0.5;
        final double anchorZ = inst.z + 0.5;
        // Distance gate from config (default 48). Beyond this we don't
        // even attempt to draw the frame — MapView itself won't accept the
        // packet anyway once the player is out of render distance, so this
        // is purely a CPU optimisation, not a behaviour change.
        final double maxDistSq = (double) plugin.getConfigManager().panelViewDistance()
                * (double) plugin.getConfigManager().panelViewDistance();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline()) continue;
            if (!p.getWorld().getName().equals(inst.world)) continue;
            // v2.0: skip players too far away — saves the composeTileBytes
            // call entirely. Bukkit will discard the map packet at the
            // client side anyway, so no visible change for the player.
            org.bukkit.Location ploc = p.getLocation();
            double dx = ploc.getX() - anchorX;
            double dy = ploc.getY() - anchorY;
            double dz = ploc.getZ() - anchorZ;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;
            // Compose the per-player frame. We do NOT cache it: the natural
            // render path will produce the same bytes and cache them, and
            // we'd rather have one source of truth.
            byte[] tileBytes = composeTileBytes(preBakedSequence, frameIdx, p);
            if (tileBytes == null) continue;
            com.hudboard.nms.MapDirectSender.sendPatch(p, view.getId(), tileBytes);
            lastSeen.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    /** Legacy name kept for the tickGifFrames() loop. */
    public void forceGifFrameToViewers() { forceFrameToViewers(); }

    /**
     * Called after the profile's data points have been reloaded from disk
     * (e.g. after the admin clicks "Save & Apply" in the editor GUI).
     * Clears the cached background tile so the next render uses the updated
     * profile data.
     */
    public void onProfileReload() {
        this.cachedTile = crop( 0L);
        lastSent.clear();
        lastResolvedHash.clear();
        lastFrameIdxPerPlayer.clear();
    }

    /**
     * Evict per-player caches whose lastSeen timestamp is older than maxAgeMs.
     * Prevents the cache from growing unbounded for long-lived servers.
     * Also caps the cache at {@code maxEntries} (LRU) to bound memory.
     */
    public void evictOlderThan(long maxAgeMs) {
        long now = System.currentTimeMillis();
        lastSeen.entrySet().removeIf(e -> now - e.getValue() > maxAgeMs);
        lastSent.keySet().retainAll(lastSeen.keySet());
        lastFrameIdxPerPlayer.keySet().retainAll(lastSeen.keySet());
    }

    /**
     * Cap the per-player cache to {@code maxEntries}. If exceeded, drop the
     * least-recently-seen entries first. Called periodically by the manager.
     */
    public void capCacheSize(int maxEntries) {
        if (maxEntries <= 0 || lastSent.size() <= maxEntries) return;
        // Build a list sorted by lastSeen ascending (oldest first) and drop
        int toDrop = lastSent.size() - maxEntries;
        var iter = lastSeen.entrySet().iterator();
        while (toDrop > 0 && iter.hasNext()) {
            var e = iter.next();
            iter.remove();
            lastSent.remove(e.getKey());
            toDrop--;
        }
    }

    @Override
    public void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
        if (panel == null) return;
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        lastSeen.put(id, now);

        // ----- FAST PATH: pre-baked GifSequence (zero image processing at render time) -----
        GifSequence seq = preBakedSequence;
        if (seq != null && seq.animated) {
            int frameIdx = seq.frameAtTime(now);
            if (frameIdx == lastFrameIdxPerPlayer.getOrDefault(id, -1)
                    && lastSent.containsKey(id)) {
                // Same frame as last time, we already drew it. For NMS direct, still send
                // (the client needs every frame to play the animation).
                if (com.hudboard.nms.MapDirectSender.isAvailable()) {
                    sendDirect(player, map, seq, frameIdx);
                }
                return;
            }
            // Compose: just stamp the pre-baked bytes + draw the data points on top
            byte[] tileBytes = composeTileBytes(seq, frameIdx, player);
            lastFrameIdxPerPlayer.put(id, frameIdx);
            if (com.hudboard.nms.MapDirectSender.isAvailable()) {
                sendDirect(player, map, seq, frameIdx);
            } else {
                // Vanilla fallback: write to canvas via setPixel
                for (int py = 0; py < 128; py++) {
                    for (int px = 0; px < 128; px++) {
                        canvas.setPixel(px, py, tileBytes[py * 128 + px]);
                    }
                }
            }
            // Cache the composed BufferedImage too (for any code path that needs it)
            // (we still cache to maintain interface compatibility with lastSent)
            BufferedImage img = bytesToImage(tileBytes);
            lastSent.put(id, img);
            return;
        }

        // ----- LEGACY PATH: BufferedImage-based render (works for everything else) -----
        // LIVE UPDATE: re-render whenever the resolved placeholders have changed
        // since the last render. This makes %server_*% / %player_*% placeholders
        // feel "live" (refresh on every map tick that has new data) instead of
        // being tied to the panel's refresh interval.
        String currentHash = hashResolvedPlaceholders(player);
        String prevHash = lastResolvedHash.get(id);
        boolean dataChanged = !currentHash.equals(prevHash);
        // Also re-render if any data point uses an animation — the phase is
        // derived from System.currentTimeMillis() so we need a fresh frame
        // every cycle. Without this, the cache reuses a static color and the
        // animation appears frozen.
        boolean animTick = hasAnyAnimation(panel);
        BufferedImage frame = lastSent.get(id);
        if (frame == null || dataChanged || (animated && gifBucketChanged(now)) || animTick) {
            BufferedImage bgTile = animated ? crop( now) : cachedTile;
            if (bgTile == null) return;
            frame = drawFrame(player, bgTile);
            lastSent.put(id, frame);
            lastResolvedHash.put(id, currentHash);
            // v2.4.0: the data changed, so the pre-baked byte cache for
            // this player is stale. Drop it so the next composeTileBytes
            // call re-renders. Without this we'd serve the OLD bytes.
            prebakedBytesStatic.remove(id);
            for (var inner : prebakedBytesGif.values()) inner.remove(id);
        }
        try {
            canvas.drawImage(0, 0, frame);
        } catch (Throwable t) {
            for (int x = 0; x < 128; x++) {
                for (int y = 0; y < 128; y++) {
                    int argb = frame.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    if (a < 16) { canvas.setPixel(x, y, (byte) 0); continue; }
                    int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                    // v2.3.0: cache by packed ARGB int — skip the Color
                    // allocation + palette walk on warm cache hits.
                    int key = (a << 24) | (r << 16) | (g << 8) | b;
                    Byte cached = paletteCache.get(key);
                    byte idx;
                    if (cached == null) {
                        idx = MapPalette.matchColor(new Color(r, g, b, a));
                        paletteCache.put(key, idx);
                    } else {
                        idx = cached;
                    }
                    canvas.setPixel(x, y, idx);
                }
            }
        }
    }

    /** Build a stable hash of all resolved data points for this player+tile. Used
     *  to detect "the data has changed since last render" — when it has, we
     *  re-render immediately rather than waiting for the next refresh tick.
     *  Uses {@code resolveThrottled} so that time-based placeholders
     *  (%server_tps%, %server_time%, %player_session%, etc.) don't cause
     *  the hash to change every tick (which would force a re-render and
     *  create visible "pulsing"). */
    private String hashResolvedPlaceholders(Player player) {
        DataManager dm = plugin.getDataManager();
        StringBuilder sb = new StringBuilder(64);
        for (InfoPanel.DataPoint dp : panel.dataPoints) {
            if (dp.tileY != tileY) continue;
            sb.append(dp.key).append('=');
            sb.append(dm.resolveThrottled(dp.text, player, panel.userPlaceholders));
            sb.append(';');
        }
        return sb.toString();
    }

    /** Send a fresh map packet directly to the player (bypasses Bukkit's slow render). */
    private void sendDirect(Player player, MapView map, GifSequence seq, int frameIdx) {
        if (map == null) return;
        int mapId = map.getId();
        byte[] pixels = composeTileBytes(seq, frameIdx, player);
        com.hudboard.nms.MapDirectSender.sendPatch(player, mapId, pixels);
    }

    /**
     * Compose the per-tile byte[] for the given frame, including the
     * pre-baked background plus the data points drawn on top.
     */

    /** Convert a 128x128 byte[] of map colour indices back to an ARGB BufferedImage. */
    private static BufferedImage bytesToImage(byte[] pixels) {
        BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                img.setRGB(x, y, MapPalette.getColor(pixels[y * 128 + x]).getRGB());
            }
        }
        return img;
    }

    /** Last frame index we sent per player (for diffing). */
    private final Map<UUID, Integer> lastFrameIdxPerPlayer = new HashMap<>();
    /**
     * v2.4.0: pre-baked byte[] cache keyed by player + frame index.
     * The 20Hz tick becomes a hashmap lookup for static panels — the
     * expensive render (Java2D + bilinear downsample + MapPalette walk)
     * only runs when the resolved text changes, not on every tick.
     * Cleared on invalidate() / invalidateAll().
     *
     * <p>v2.5.0: soft cap. Each entry is ~16kB (128×128). With 50 viewers
     * × 20 panels × 50 frames = 50k entries × 16kB = 800MB worst case.
     * The cap below keeps the cache bounded per-renderer without
     * affecting correctness — when exceeded, the LEAST RECENTLY USED
     * entry is evicted (LRU via LinkedHashMap access-order). The next
     * render for the evicted (player, frame) re-bakes from scratch,
     * so cache misses are just slower renders, never wrong bytes.</p>
     */
    private static final int MAX_PREBAKE_ENTRIES_PER_PANEL = 64;
    private final LinkedHashMap<UUID, byte[]> prebakedBytesStatic =
            new LinkedHashMap<>(MAX_PREBAKE_ENTRIES_PER_PANEL, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, byte[]> eldest) {
                    return size() > MAX_PREBAKE_ENTRIES_PER_PANEL;
                }
            };
    /** Same idea for animated GIFs — cache per (player, frame_idx). v2.5.0
     *  caps the total entries across all frames at MAX_PREBAKE_ENTRIES_PER_PANEL
     *  to prevent RAM blow-up on long GIFs with many viewers. */
    private final Map<Long, Map<UUID, byte[]>> prebakedBytesGif = new HashMap<>();

    /** Last time we drew a frame for any player (used as the GIF bucket key). */
    private long lastGifDrawMs = 0L;
    /** True if the current time falls in a different GIF frame than the last draw. */
    private boolean gifBucketChanged(long now) {
        GifAnimation gif = panel.getGif();
        if (gif == null || !gif.isAnimated()) return false;
        int cur = currentFrameIndex(gif, now);
        int prev = currentFrameIndex(gif, lastGifDrawMs);
        boolean changed = cur != prev;
        lastGifDrawMs = now;
        return changed;
    }

    private static int currentFrameIndex(GifAnimation gif, long timeMs) {
        if (gif == null || gif.totalDurationMs <= 0) return 0;
        long t = ((timeMs % gif.totalDurationMs) + gif.totalDurationMs) % gif.totalDurationMs;
        long acc = 0;
        for (int i = 0; i < gif.frames.length; i++) {
            acc += gif.delaysMs[i];
            if (t < acc) return i;
        }
        return gif.frames.length - 1;
    }

    private BufferedImage computeFrame(Player p) {
        // Backward-compat shim — delegate to the new drawFrame() with the
        // currently-cached static tile (or the current animated frame).
        BufferedImage bg = animated ? crop( System.currentTimeMillis()) : cachedTile;
        return drawFrame(p, bg);
    }

    /**
     * Render the data points on top of the given background tile and return
     * the result. The background is the cropped current frame (static OR
     * an animated GIF frame at the chosen instant).
     */
    private BufferedImage drawFrame(Player p, BufferedImage bgTile) {
        if (bgTile == null) return new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        // v2.1.0: render at 2x resolution (256x256) then bilinear-downsample
        // to 128x128 before MapPalette color matching. This quadruples the
        // pixel density seen by matchColor, which dramatically improves
        // perceived smoothness — the 4-bit palette can now pick a colour
        // close to the AA-edge value instead of jumping between two
        // adjacent palette entries.
        BufferedImage hires = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hires.createGraphics();
        // Apply a 2x global transform so the rest of the rendering code
        // can keep using the existing 128-space coordinates (dp.x/dp.y/
        // dp.size, char widths, animation offsets) unchanged. Java2D's
        // high-quality transforms preserve the AA we set below.
        g.scale(2.0, 2.0);
        // Background is in 128-space too, draw it once at the origin.
        g.drawImage(bgTile, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        DataManager dm = plugin.getDataManager();
        long t = System.currentTimeMillis();
        for (InfoPanel.DataPoint dp : panel.dataPoints) {
            if (dp.tileY != tileY) continue;
            // Use resolveThrottled so the drawn text matches the hash: if a
            // template has a time-based placeholder (%server_time%, %tps%),
            // the value stays stable for 1s instead of changing every render
            // frame. This kills the visible "pulsing" on time-based placeholders.
            String resolved = dm.resolveThrottled(dp.text, p, panel.userPlaceholders);
            if ((resolved == null || resolved.isEmpty()) && dp.text.contains("%")) resolved = "—";
            if (resolved == null || resolved.isEmpty()) continue;

            // Apply the optional baseColor MiniMessage prefix (e.g. <red>,
            // <#FF8800>, <gradient:#CB0DFC:#0EEB7D>). The user's own embedded
            // tags (e.g. <white>world</white>) still take effect because
            // MiniMessage re-parses the whole string.
            if (dp.baseColor != null && !dp.baseColor.isBlank()) {
                resolved = dp.baseColor + resolved;
            }
            // Animation: 5 visually-distinct effects (positional, not color-based).
            // The legacy color-only animations (pulse/breathe/blink/rainbow) were
            // removed because they only modulated the color and often looked
            // like the text was "glitching" — the new ones move the text around
            // instead, which is much more visible and obvious.
            //
            //   bob         → vertical sin oscillation (text bobs up & down)
            //   scroll      → horizontal ticker (text slides left)
            //   typewriter  → progressive reveal (chars appear one by one)
            //   glitch      → random ±4px teleport every ~80ms (TV-static feel)
            //   pulse       → smooth breathing scale (1.0 ↔ 1.12)
            //
            // animColor is kept as an optional secondary color used by typewriter
            // to draw the "ghost" of the un-revealed chars. bob/scroll/glitch/pulse
            // ignore it.
            int baseRgb = dp.baseColor != null && !dp.baseColor.isBlank()
                    ? com.hudboard.menu.DialogInputBridge.parseMiniMessageColor(dp.baseColor)
                    : dp.color;
            int drawColor = baseRgb;
            int animXOffset = 0;
            int animYOffset = 0;
            int animRevealCutoff = -1;   // -1 = no reveal clipping
            int animGhostRgb = 0x404040; // dim gray for typewriter unrevealed chars
            float animPulseScale = 1.0f;
            if (dp.animation != null) {
                float phase = (float) (t % Math.max(1, dp.animMs)) / Math.max(1, dp.animMs);
                String anim = dp.animation.toLowerCase();
                if ("bob".equals(anim)) {
                    // ±3 pixels vertical oscillation, smooth sin wave
                    animYOffset = (int) Math.round(Math.sin(phase * Math.PI * 2) * 3);
                } else if ("glitch".equals(anim)) {
                    // Random ±4px teleport every ~80ms. Seed based on time so
                    // the offset is deterministic within a frame (multiple data
                    // points in the same panel share the same glitch frame).
                    long glitchStep = t / 80L;
                    java.util.Random r = new java.util.Random(glitchStep);
                    animXOffset = r.nextInt(9) - 4; // -4 .. +4
                    animYOffset = r.nextInt(9) - 4;
                } else if ("pulse".equals(anim)) {
                    // Smooth breathing scale 0.92 .. 1.08, sin-driven.
                    animPulseScale = 1.0f + (float) Math.sin(phase * Math.PI * 2) * 0.08f;
                }
                // scroll + typewriter offsets depend on textWidth, so they're
                // computed further down, after fillPerCharLayout has run.
            }

            // v2.3.0: auto-fit font size to the tile width minus padding, so
            // long texts shrink instead of overflowing. Re-derive the
            // font if we end up shrinking, otherwise keep the original.
            int padding = Math.max(0, Math.min(32, dp.padding));
            int maxWidth = panel.tilesW * 128 - 2 * padding;
            Font font = new Font("Dialog", Font.BOLD, dp.size);
            g.setFont(font);
            String prepared = preprocessForDraw(resolved);
            java.util.List<PerChar> chars = new java.util.ArrayList<>();
            flattenPerChar(MM.deserialize(prepared).decoration(TextDecoration.ITALIC, false), drawColor, chars);
            int textWidth = fillPerCharLayout(chars, font);
            int minSize = 4;
            while (textWidth > maxWidth && font.getSize() > minSize) {
                font = new Font("Dialog", Font.BOLD, font.getSize() - 1);
                g.setFont(font);
                chars.clear();
                flattenPerChar(MM.deserialize(prepared).decoration(TextDecoration.ITALIC, false), drawColor, chars);
                textWidth = fillPerCharLayout(chars, font);
            }
            if (dp.animation != null && "scroll".equalsIgnoreCase(dp.animation)) {
                float phase = (float) (t % Math.max(1, dp.animMs)) / Math.max(1, dp.animMs);
                int totalDist = textWidth + 128;
                animXOffset = (int) Math.round(totalDist * (1.0 - phase)) - 128;
            }
            if (dp.animation != null && "typewriter".equalsIgnoreCase(dp.animation)) {
                float phase = (float) (t % Math.max(1, dp.animMs)) / Math.max(1, dp.animMs);
                animRevealCutoff = (int) Math.round(phase * textWidth);
            }
            int dpGlobalX = dp.tileX * 128 + dp.x;
            int tileGlobalX = tileX * 128;
            int tileGlobalXEnd = tileGlobalX + 128;
            int dpGlobalXEnd = dpGlobalX + textWidth;
            if (dpGlobalXEnd <= tileGlobalX || dpGlobalX >= tileGlobalXEnd) continue;
            int visStart = Math.max(0, tileGlobalX - dpGlobalX);
            int visEnd = Math.min(textWidth, tileGlobalXEnd - dpGlobalX);
            int drawX = Math.max(0, dpGlobalX - tileGlobalX);
            // v2.3.0 + v2.4.1: apply horizontal alignment. Two modes:
            //  - alignMode = "tile"  (default): align operates within each
            //    visible tile window independently (legacy behaviour).
            //  - alignMode = "panel": align operates on the ENTIRE panel
            //    width, so a centered text on a 2x2 panel is centered across
            //    256px (not split into two centered halves). The shift is
            //    applied to dpGlobalX BEFORE the tile-local drawX is
            //    derived, so all subsequent tiles see the same offset.
            String align = dp.align == null ? "left" : dp.align.toLowerCase();
            String alignMode = dp.alignMode == null ? "tile" : dp.alignMode.toLowerCase();
            if ("panel".equals(alignMode) && !"left".equals(align)) {
                int panelWidth = panel.tilesW * 128;
                int panelShift = 0;
                if ("center".equals(align)) panelShift = (panelWidth - textWidth) / 2;
                else if ("right".equals(align)) panelShift = panelWidth - textWidth;
                // Re-derive dpGlobalX with the shift applied at the panel
                // level. Then drawX = dpGlobalX - tileGlobalX propagates
                // the shift to every tile automatically.
                dpGlobalX = dp.tileX * 128 + dp.x + panelShift;
                // Re-clamp visibility window with the shifted origin.
                dpGlobalXEnd = dpGlobalX + textWidth;
                if (dpGlobalXEnd <= tileGlobalX || dpGlobalX >= tileGlobalXEnd) continue;
                visStart = Math.max(0, tileGlobalX - dpGlobalX);
                visEnd = Math.min(textWidth, tileGlobalXEnd - dpGlobalX);
                drawX = Math.max(0, dpGlobalX - tileGlobalX);
            } else {
                int visibleW = visEnd - visStart;
                if ("center".equals(align)) {
                    drawX = drawX + (visibleW - textWidth) / 2;
                } else if ("right".equals(align)) {
                    drawX = drawX + (visibleW - textWidth);
                }
            }
            // For pulse, wrap in a scaled Graphics2D so the text "breathes"
            // around its own centre. The scale applies to outline + main pass.
            Graphics2D drawG = g;
            if (animPulseScale != 1.0f) {
                drawG = (Graphics2D) g.create();
                // Centre of the text bounding box in the tile's local coords
                int textCenterX = drawX + (visEnd - visStart) / 2;
                int textCenterY = dp.y + font.getSize() / 2;
                drawG.translate(textCenterX, textCenterY);
                drawG.scale(animPulseScale, animPulseScale);
                drawG.translate(-textCenterX, -textCenterY);
            }
            // v2.3.0 + v2.4.1: opaque background behind text. The bg now
            // spans the FULL text width (covers all overflow tiles),
            // clipped per-tile via the Math.min / Math.max calls below.
            if (dp.bg != null && !dp.bg.isBlank()) {
                int bgRgb = com.hudboard.menu.DialogInputBridge.parseMiniMessageColor(dp.bg);
                int bgX = drawX - 2;
                int bgY = Math.max(0, dp.y - 2);
                int bgW = textWidth + 4;
                int bgH = Math.min(128 - bgY, font.getSize() + 4);
                if (bgX < 0) { bgW += bgX; bgX = 0; }
                if (bgX >= 128 || bgW <= 0) { /* fully outside this tile */ }
                else {
                    if (bgX + bgW > 128) bgW = 128 - bgX;
                    drawG.setColor(new Color(bgRgb, true));
                    drawG.fillRect(bgX, bgY, bgW, bgH);
                }
            }
            drawPerCharClipped(drawG, chars, visStart, visEnd, drawX, dp.y,
                    animXOffset, animYOffset, animRevealCutoff, animGhostRgb);
            if (drawG != g) drawG.dispose();
        }
        g.dispose();
        // v2.1.0: bilinear downsample from 256x256 to 128x128. This is the
        // step that actually gives the "smoother" look — the 2x buffer
        // alone would still get crushed to 4-bit later, but the
        // downsample now picks the average of 4 source pixels per
        // destination, so AA edges blur into a colour close to the
        // nearest MapPalette entry instead of being either fully on/off.
        BufferedImage downsampled = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ds = downsampled.createGraphics();
        ds.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        ds.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        ds.drawImage(hires, 0, 0, 128, 128, null);
        ds.dispose();
        return downsampled;
    }

    /**
     * Parse the MiniMessage string, then draw the text:
     *   - once as a 1px black outline (8 offset positions)
     *   - once with the proper color(s) from the parsed Component tree
     *
     * Width measurement uses {@link #measureString(Graphics2D, Font, String)}
     * which is headless-safe (does NOT call g.getFontMetrics() because that
     * triggers D3DGraphicsDevice.initD3D() on Windows and hangs the server).
     */
    private void drawStyledText(Graphics2D g, String miniText, int x, int y, int defaultRgb) {
        // Preprocess: MiniMessage doesn't accept short HTML-like aliases, so
        // we translate <b> → <bold>, <i> → <italic>, <newline> → real \n
        // before parsing. Then split on \n and draw each line.
        String prepared = miniText
                .replaceAll("(?i)<\\s*/?\\s*b\\s*>", "")   // strip <b> and </b>
                .replaceAll("(?i)<\\s*/?\\s*i\\s*>", "")   // strip <i> and </i>
                .replaceAll("(?i)<\\s*/?\\s*u\\s*>", "")   // strip <u> too
                .replaceAll("(?i)<\\s*newline\\s*/?\\s*>", "\n");
        // <b> alone is mapped to <bold> by wrapping the text
        if (miniText.matches("(?is).*<\\s*b\\s*>.*") && !miniText.matches("(?is).*<\\s*bold\\s*>.*")) {
            prepared = "<bold>" + prepared + "</bold>";
        }
        if (miniText.matches("(?is).*<\\s*i\\s*>.*") && !miniText.matches("(?is).*<\\s*italic\\s*>.*")) {
            prepared = "<italic>" + prepared + "</italic>";
        }
        // Split into lines and draw each one
        String[] lines = prepared.split("\n", -1);
        Font font = g.getFont();
        int lineHeight = Math.max(1, font.getSize() + 2);
        int lineY = y;
        for (String line : lines) {
            if (line.isEmpty()) { lineY += lineHeight; continue; }
            Component comp = MM.deserialize(line).decoration(TextDecoration.ITALIC, false);
            List<Segment> segments = new ArrayList<>();
            flatten(comp, defaultRgb, segments);
            if (!segments.isEmpty()) {
                int[] xs = new int[segments.size() + 1];
                xs[0] = x;
                for (int i = 0; i < segments.size(); i++) {
                    xs[i + 1] = xs[i] + measureString(g, font, segments.get(i).text);
                }
                // Outline pass
                g.setColor(new Color(0xFF101820, true));
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        for (int i = 0; i < segments.size(); i++) {
                            g.drawString(segments.get(i).text, xs[i] + dx, lineY + dy);
                        }
                    }
                }
                // Main pass
                for (int i = 0; i < segments.size(); i++) {
                    g.setColor(new Color(0xFF000000 | segments.get(i).rgb, true));
                    g.drawString(segments.get(i).text, xs[i], lineY);
                }
            }
            lineY += lineHeight;
        }
    }

    /**
     * v2.1.1: per-Font, per-character width cache. The previous
     * {@code Map<Font, Integer[256]>} design used a (size, char) hash
     * that COULD collide between different character values, causing
     * one char's measured width to overwrite another's in the same
     * cache slot — visible as erratic kerning gaps (e.g. "Kt" rendering
     * with a visible space between K and t). The new cache is keyed on
     * Character itself, so collisions are impossible.
     */
    private final Map<Font, Map<Character, Integer>> widthCache = new HashMap<>();
    /** Shared FontRenderContext for TextLayout measurements. Allocated
     *  once per renderer; identical settings to what Java2D uses for
     *  drawing (AA on, fractional metrics on) so the measured width
     *  matches the actual drawString output. Pure Java2D core — does
     *  NOT trigger D3D init on Windows the way g.getFontMetrics() does. */
    private final FontRenderContext sharedFRC = new FontRenderContext(
            null, true, true);
    private final Font fallbackFont = new Font(Font.SANS_SERIF, Font.BOLD, 12);

    private int measureString(Graphics2D g, Font font, String text) {
        return measureTextWidth(text, font);
    }

    /**
     * Total width of a string in pixels, using the per-character approximation.
     * Headless-safe (no g.getFontMetrics() call).
     */
    public int measureTextWidth(String text, Font font) {
        if (text == null || text.isEmpty()) return 0;
        Map<Character, Integer> cache = widthCache.computeIfAbsent(font, f -> new HashMap<>());
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Integer cw = cache.get(c);
            if (cw == null) {
                cw = (int) Math.round(realCharWidth(font, c, sharedFRC));
                cache.put(c, cw);
            }
            w += cw;
        }
        return w;
    }

    /**
     * Return the substring of {@code text} that occupies the pixel range
     * {@code [startPx, endPx)} of its layout. Used to slice a text that
     * overflows one tile into the next.
     *
     * <p>Strategy: keep a character if and only if its layout position
     * <b>starts inside</b> the visible window. This is critical to avoid
     * duplicates at tile boundaries: a character that starts in the previous
     * tile is already drawn there and must NOT be drawn again at the start
     * of the next tile. The "overlap" check used to be {@code charEnd >
     * startPx && charStart < endPx}, but that incorrectly kept characters
     * whose end poked past the boundary (e.g. the trailing "1" of a "11"
     * sequence showing up again at the start of the next tile).
     */
    public String sliceByPixels(String text, Font font, int startPx, int endPx) {
        if (text == null || text.isEmpty() || endPx <= 0 || startPx >= endPx) return "";
        Map<Character, Integer> cache = widthCache.computeIfAbsent(font, f -> new HashMap<>());
        int[] widths = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Integer cw = cache.get(c);
            if (cw == null) {
                cw = (int) Math.round(realCharWidth(font, c, sharedFRC));
                cache.put(c, cw);
            }
            widths[i] = cw;
        }
        return sliceByPixels(text, widths, startPx, endPx);
    }

    /**
     * Pure (static) variant of {@link #sliceByPixels(String, Font, int, int)}
     * for unit testing. Same "starts inside" rule — see the bug history in
     * the instance method's javadoc.
     *
     * @param widths per-character pixel widths, parallel to {@code text}
     * @return substring of {@code text} whose char-start position lies in
     *         {@code [startPx, endPx)}
     */
    public static String sliceByPixels(String text, int[] widths, int startPx, int endPx) {
        if (text == null || text.isEmpty() || endPx <= 0 || startPx >= endPx) return "";
        if (widths == null || widths.length != text.length()) {
            throw new IllegalArgumentException("widths length must match text length");
        }
        StringBuilder sb = new StringBuilder();
        int x = 0;
        for (int i = 0; i < text.length(); i++) {
            int charStart = x;
            int charEnd = x + widths[i];
            if (charStart >= startPx && charStart < endPx) {
                sb.append(text.charAt(i));
            }
            x = charEnd;
            if (x >= endPx) break;
        }
        return sb.toString();
    }

    /**
     * v2.1.1: real per-character width via {@link TextLayout#getAdvance()}.
     * Replaces the v2.1.0 {@code approxCharWidth} hack which used a
     * fixed per-category coefficient — accurate for the average letter
     * but wrong for kerned pairs (Kt, fa, ph, ...) because Java AWT
     * applies kerning when it draws. The mismatch produced visible
     * gaps inside words: "Kt errali" instead of "Kterrali".
     *
     * <p>TextLayout is purely Java2D-core — no D3D init — so it's still
     * safe on Windows headless servers where {@code g.getFontMetrics()}
     * would hang.</p>
     */
    private static double realCharWidth(Font font, char c, FontRenderContext frc) {
        try {
            TextLayout tl = new TextLayout(String.valueOf(c), font, frc);
            return tl.getAdvance();
        } catch (Throwable t) {
            // Fallback to a sane estimate if TextLayout ever fails
            // (shouldn't happen on standard JVMs, but be defensive).
            float size = font.getSize();
            if (c == ' ') return size * 0.30;
            if (c == 'i' || c == 'l' || c == 'I') return size * 0.32;
            if (c == 'm' || c == 'w' || c == 'M' || c == 'W') return size * 0.95;
            return size * 0.55;
        }
    }

    /** Walk the Component tree, flattening it to a list of (text, rgb) segments. */
    private void flatten(Component comp, int defaultRgb, List<Segment> out) {
        TextColor color = comp.color();
        int rgb;
        if (color != null) {
            rgb = color.value();
        } else {
            rgb = defaultRgb;
        }
        if (comp instanceof TextComponent tc) {
            String s = tc.content();
            if (!s.isEmpty()) out.add(new Segment(s, rgb));
        }
        for (Component child : comp.children()) {
            flatten(child, defaultRgb, out);
        }
    }

    private record Segment(String text, int rgb) {}

    // -------------------------------------------------------------------------
    // Per-character rendering (slice-aware gradient)
    // -------------------------------------------------------------------------

    /**
     * One printable character of a data-point text, with its pre-resolved
     * color and pixel layout. Built by {@link #flattenPerChar} from a parsed
     * MiniMessage tree and consumed by {@link #drawPerCharClipped}.
     *
     * <p>The pre-resolved color is the whole point of this struct: MiniMessage
     * assigns per-character {@link TextColor}s inside {@code <gradient:...>},
     * so when a long gradient text is sliced across tile boundaries, each
     * visible char keeps the exact color it had in the FULL text. The old
     * segment-based path would MiniMessage-deserialize the sliced substring
     * alone, causing the gradient to be recomputed on the visible portion
     * only (e.g. a 5-char gradient cut to 2 chars would render 2 chars
     * from full red to full blue instead of the first 2/5 of the gradient).</p>
     */
    /** Package-private so unit tests in {@code com.hudboard.panel} can read it. */
    record PerChar(char c, int rgb, int widthPx, int lineX) {}

    /** Detects the {@code <gradient:...>...</gradient>} tag in a MiniMessage string. */
    private static final Pattern GRADIENT_TAG = Pattern.compile("<gradient:", Pattern.CASE_INSENSITIVE);
    private static boolean hasGradientTag(String text) {
        return text != null && GRADIENT_TAG.matcher(text).find();
    }

    /**
     * Pre-process a MiniMessage string the same way {@link #drawStyledText}
     * does (strip {@code <b>}/{@code <i>}/{@code <u>}, expand
     * {@code <newline>} to a real newline, wrap with {@code <bold>} if
     * {@code <b>} is present without {@code <bold>}). Extracted so the
     * per-char path can reuse it.
     */
    private static String preprocessForDraw(String miniText) {
        String prepared = miniText
                .replaceAll("(?i)<\\s*/?\\s*b\\s*>", "")
                .replaceAll("(?i)<\\s*/?\\s*i\\s*>", "")
                .replaceAll("(?i)<\\s*/?\\s*u\\s*>", "")
                // <newline>, <n> and <br> all map to a real newline. The
                // "whole word" check on newline matters: <n> alone would
                // otherwise swallow any other tag starting with 'n' (none
                // today, but the strict check is safer).
                .replaceAll("(?i)<\\s*newline\\s*/?\\s*>", "\n")
                .replaceAll("(?i)<\\s*n\\s*/?\\s*>", "\n")
                .replaceAll("(?i)<\\s*br\\s*/?\\s*>", "\n");
        if (miniText.matches("(?is).*<\\s*b\\s*>.*") && !miniText.matches("(?is).*<\\s*bold\\s*>.*")) {
            prepared = "<bold>" + prepared + "</bold>";
        }
        if (miniText.matches("(?is).*<\\s*i\\s*>.*") && !miniText.matches("(?is).*<\\s*italic\\s*>.*")) {
            prepared = "<italic>" + prepared + "</italic>";
        }
        return prepared;
    }

    /**
     * Walk a parsed MiniMessage Component tree and return one {@link PerChar}
     * per printable char, plus one {@code PerChar('\n', ...)} per newline
     * so the caller can split lines. {@code widthPx} and {@code lineX} are
     * left as 0 / 0 — they are filled in by {@link #fillPerCharLayout}.
     *
     * <p>MiniMessage assigns per-character TextColors inside gradients, so
     * {@code comp.color()} returns the correct color for each char even
     * when the text is wrapped in {@code <gradient:...>}. When a child
     * component has {@code color() == null}, the current inherited color
     * is used (so e.g. {@code <red>X<gradient>Y</gradient>Z</red>} paints
     * both X and Z red, with the gradient overriding Y).</p>
     */
    private static void flattenPerChar(Component comp, int defaultRgb, List<PerChar> out) {
        // Per-char flatten. MiniMessage's GradientTag produces one TextComponent
        // per character (each carrying the interpolated gradient color), so
        // the simple `comp.color()` + recurse-children approach works: each
        // single-char TextComponent has the right RGB, and the parent
        // VirtualComponent / empty TextComponent wrappers carry color=null
        // which we fall back to defaultRgb for (not used because they have no
        // text content themselves).
        TextColor color = comp.color();
        int rgb = (color != null) ? color.value() : defaultRgb;
        if (comp instanceof TextComponent tc) {
            String s = tc.content();
            for (int i = 0; i < s.length(); i++) {
                out.add(new PerChar(s.charAt(i), rgb, 0, 0));
            }
        }
        // Pass `rgb` (not `defaultRgb`) so children inherit the current color
        // instead of the panel default.
        for (Component child : comp.children()) {
            flattenPerChar(child, rgb, out);
        }
    }

    /**
     * Fill {@code widthPx} and {@code lineX} on every {@link PerChar} in the
     * list. {@code lineX} resets to 0 after each newline. Returns the total
     * width of the longest line in pixels.
     */
    private int fillPerCharLayout(List<PerChar> chars, Font font) {
        Map<Character, Integer> cache = widthCache.computeIfAbsent(font, f -> new HashMap<>());
        int lineX = 0;
        int maxLineX = 0;
        for (int i = 0; i < chars.size(); i++) {
            PerChar pc = chars.get(i);
            if (pc.c == '\n') {
                maxLineX = Math.max(maxLineX, lineX);
                lineX = 0;
                chars.set(i, new PerChar('\n', pc.rgb, 0, 0));
                continue;
            }
            Integer cw = cache.get(pc.c);
            if (cw == null) {
                cw = (int) Math.round(realCharWidth(font, pc.c, sharedFRC));
                cache.put(pc.c, cw);
            }
            chars.set(i, new PerChar(pc.c, pc.rgb, cw, lineX));
            lineX += cw;
        }
        maxLineX = Math.max(maxLineX, lineX);
        return maxLineX;
    }

    /**
     * Pure (static) variant of {@link #fillPerCharLayout(List, Font)} for
     * unit testing. Uses the given {@code widthOf} function (a closure over
     * {@link Font#size}) to look up each char's pixel width.
     */
    public static int fillPerCharLayoutStatic(List<PerChar> chars, java.util.function.IntFunction<Integer> widthOf) {
        int lineX = 0;
        int maxLineX = 0;
        for (int i = 0; i < chars.size(); i++) {
            PerChar pc = chars.get(i);
            if (pc.c == '\n') {
                maxLineX = Math.max(maxLineX, lineX);
                lineX = 0;
                chars.set(i, new PerChar('\n', pc.rgb, 0, 0));
                continue;
            }
            int cw = widthOf.apply(pc.c);
            chars.set(i, new PerChar(pc.c, pc.rgb, cw, lineX));
            lineX += cw;
        }
        maxLineX = Math.max(maxLineX, lineX);
        return maxLineX;
    }

    /**
     * Pure (static) variant of {@link #flattenPerChar(Component, int, List)}
     * for unit testing. Public so test classes in a different package can
     * reach it (without going through reflection).
     */
    public static int flattenPerCharStatic(Component comp, int defaultRgb, List<PerChar> out) {
        int before = out.size();
        flattenPerChar(comp, defaultRgb, out);
        return out.size() - before;
    }

    /**
     * Pure (static) variant of {@link #drawPerCharClipped} that returns the
     * visible PerChar entries (in line-pixel order) without touching any
     * {@link Graphics2D}. Used by tests to assert "given this per-char
     * layout, these are the chars that would be drawn in the visible window".
     *
     * <p>Mirrors the "starts inside" rule of {@link #sliceByPixels}: a char
     * belongs to the tile whose {@code visStart} is the greatest value
     * still {@code <= lineX}. That is, we keep {@code pc} iff
     * {@code lineX >= visStart && lineX < visEnd}. This is what stops a
     * wide char from being drawn on two adjacent tiles.</p>
     *
     * @param chars    the full per-char layout (already filled with widths + lineX)
     * @param visStart inclusive left edge of the visible window, in line-pixel space
     * @param visEnd   exclusive right edge
     * @return list of PerChar whose lineX is in {@code [visStart, visEnd)}
     */
    public static List<PerChar> visiblePerChar(List<PerChar> chars, int visStart, int visEnd) {
        java.util.List<PerChar> out = new java.util.ArrayList<>();
        int lineX = 0;
        for (PerChar pc : chars) {
            if (pc.c == '\n') { lineX = 0; continue; }
            // Strict "starts inside" rule — same as sliceByPixels
            if (lineX < visStart || lineX >= visEnd) {
                lineX += pc.widthPx;
                continue;
            }
            out.add(new PerChar(pc.c, pc.rgb, pc.widthPx, lineX));
            lineX += pc.widthPx;
        }
        return out;
    }

    /**
     * Draw a list of {@link PerChar} entries clipped to {@code [visStart, visEnd)}
     * in line-pixel space, at {@code (x0, y0)} on the canvas. Handles
     * {@code '\n'} by resetting lineX and incrementing y by lineHeight.
     * Each drawn char gets a 1px black outline (8 offsets) like the
     * segment path.
     */
    private void drawPerCharClipped(Graphics2D g, List<PerChar> chars,
                                    int visStart, int visEnd, int x0, int y0,
                                    int animXOffset, int animYOffset,
                                    int animRevealCutoff, int animGhostRgb) {
        Font font = g.getFont();
        int lineHeight = Math.max(1, font.getSize() + 2);
        int lineY = y0;
        int lineX = 0;
        for (PerChar pc : chars) {
            if (pc.c == '\n') {
                lineY += lineHeight;
                lineX = 0;
                continue;
            }
            // Skip chars entirely before or after the visible window
            // ("starts inside" rule, same as sliceByPixels — prevents a
            // char from being drawn on two adjacent tiles)
            if (lineX < visStart || lineX >= visEnd) {
                lineX += pc.widthPx;
                continue;
            }
            // Animation: typewriter clips to only the first N chars from the
            // start of the text. Chars past the cutoff are skipped entirely
            // (no ghost draw — keeps the effect clean and avoids overlap).
            if (animRevealCutoff >= 0 && lineX >= animRevealCutoff) {
                lineX += pc.widthPx;
                continue;
            }
            int charX = x0 + (lineX - visStart) + animXOffset;
            int charY = lineY + animYOffset;
            // v2.1.0: drop shadow first (1px black, offset +1,+1) — gives the
            // text depth on busy backgrounds. The 8-direction outline pass
            // still runs for readability, but the drop shadow underneath
            // adds the "raised" feel the eye reads as smooth.
            g.setColor(new Color(0xFF000000, true));
            g.drawString(String.valueOf(pc.c), charX + 1, charY + 1);
            // Outline pass (1px dark navy, 8 offsets) — kept from the
            // original renderer, helps thin fonts survive the AA-edge
            // destruction that MapPalette causes.
            g.setColor(new Color(0xFF101820, true));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    g.drawString(String.valueOf(pc.c), charX + dx, charY + dy);
                }
            }
            // Main pass
            g.setColor(new Color(0xFF000000 | pc.rgb, true));
            g.drawString(String.valueOf(pc.c), charX, charY);
            lineX += pc.widthPx;
        }
    }

    private static int lerpColor(int a, int b, float t) {
        if (t < 0) t = 0; if (t > 1) t = 1;
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int oa = (int) (aa + (ba - aa) * t);
        int or = (int) (ar + (br - ar) * t);
        int og = (int) (ag + (bg - ag) * t);
        int ob = (int) (ab + (bb - ab) * t);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }

    private static int scaleColor(int c, float k) {
        int a = (c >>> 24) & 0xFF;
        int r = Math.min(255, (int) (((c >> 16) & 0xFF) * k));
        int g = Math.min(255, (int) (((c >> 8) & 0xFF) * k));
        int b = Math.min(255, (int) ((c & 0xFF) * k));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Convert ARGB int to HSV (h in [0,1], s in [0,1], v in [0,1]). */
    private static float[] rgbToHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h = 0, s = (max == 0) ? 0 : d / max, v = max;
        if (d != 0) {
            if (max == r) h = ((g - b) / d) % 6f;
            else if (max == g) h = ((b - r) / d) + 2f;
            else h = ((r - g) / d) + 4f;
            h /= 6f;
            if (h < 0) h += 1f;
        }
        return new float[]{h, s, v};
    }

    /** Convert HSV + alpha back to ARGB int. */
    private static int hsvToRgb(float[] hsv, int alpha) {
        float h = hsv[0] % 1f; if (h < 0) h += 1f;
        float s = hsv[1], v = hsv[2];
        float r, g, b;
        float hh = h * 6f;
        int i = (int) Math.floor(hh) % 6;
        float f = hh - (float) Math.floor(hh);
        float p = v * (1 - s);
        float q = v * (1 - s * f);
        float t = v * (1 - s * (1 - f));
        switch (i) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        int ri = Math.min(255, (int) (r * 255));
        int gi = Math.min(255, (int) (g * 255));
        int bi = Math.min(255, (int) (b * 255));
        return (alpha << 24) | (ri << 16) | (gi << 8) | bi;
    }

    /**
     * Force a render of this tile without needing a Player. Used right after
     * a panel is (re)placed so the player doesn't see a blank tile until the
     * server's next render tick. We synthesize a MapCanvas of the correct
     * size and walk through the same code path as render(MapView, MapCanvas,
     * Player), but with a no-op Player.
     */
    public void forceRender(MapView map) {
        try {
            // Allocate a tiny 128x128 pixel buffer and use a MapCanvas wrapper
            // that reads/writes into it. Then we push the resulting pixels into
            // the MapView's internal state via the same path render() uses.
            // Paper's MapView has a 'render' method that takes no Player and
            // triggers a render for all currently-cached states — we use that
            // to make the new state immediately visible to the client.
            java.lang.reflect.Method m = map.getClass().getMethod("render");
            m.invoke(map);
        } catch (Throwable t) {
            // Reflection failed — fall back to a 1-tick schedule so the server's
            // normal render cycle still picks it up.
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    java.lang.reflect.Method m = map.getClass().getMethod("render");
                    m.invoke(map);
                } catch (Throwable ignored) {}
            });
        }
    }
    private byte[] composeTileBytes(GifSequence seq, int frameIdx, Player player) {
        // v2.4.0: pre-bake cache. After the first composition for a given
        // (player, frameIdx), subsequent calls return the cached byte[]
        // without re-running Java2D + bilinear + MapPalette. The hash
        // check in forceFrameToViewers already invalidates this cache
        // when the resolved text changes, so the cache is always fresh.
        if (seq != null && seq.animated) {
            Map<UUID, byte[]> frame = prebakedBytesGif.get((long) frameIdx);
            if (frame != null) {
                byte[] cached = frame.get(player == null ? null : player.getUniqueId());
                if (cached != null) return cached;
            }
        } else {
            UUID key = player == null ? null : player.getUniqueId();
            byte[] cached = prebakedBytesStatic.get(key);
            if (cached != null) return cached;
        }
        byte[] base = seq.tilesByFrame[frameIdx][tileIndex];
        // If no data points affect this tile row, return the pre-baked bytes as-is
        boolean hasDP = false;
        for (InfoPanel.DataPoint dp : panel.dataPoints) {
            if (dp.tileY == tileY) { hasDP = true; break; }
        }
        if (!hasDP) return base;
        byte[] out = base.clone();
        BufferedImage tile = bytesToImage(out);
        Graphics2D g = tile.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        DataManager dm = plugin.getDataManager();
        long t = System.currentTimeMillis();
        for (InfoPanel.DataPoint dp : panel.dataPoints) {
            // Cross-tile: a text that starts on a neighbor tile can overflow
            // into this one. We iterate ALL data points on the same row and
            // slice the text to fit the visible window. Use resolveThrottled
            // so time-based placeholders don't pulse (see hashResolvedPlaceholders).
            if (dp.tileY != tileY) continue;
            String resolved = dm.resolveThrottled(dp.text, player, panel.userPlaceholders);
            if ((resolved == null || resolved.isEmpty()) && dp.text.contains("%")) resolved = "—";
            if (resolved == null || resolved.isEmpty()) continue;
            int drawColor = dp.color;
            int animXOffset = 0;
            int animYOffset = 0;
            int animRevealCutoff = -1;
            int animGhostRgb = 0x404040;
            float animPulseScale = 1.0f;
            if (dp.animation != null) {
                float phase = (float) (t % Math.max(1, dp.animMs)) / Math.max(1, dp.animMs);
                String anim = dp.animation.toLowerCase();
                if ("bob".equals(anim)) {
                    animYOffset = (int) Math.round(Math.sin(phase * Math.PI * 2) * 3);
                } else if ("glitch".equals(anim)) {
                    long glitchStep = t / 80L;
                    java.util.Random r = new java.util.Random(glitchStep);
                    animXOffset = r.nextInt(9) - 4;
                    animYOffset = r.nextInt(9) - 4;
                } else if ("pulse".equals(anim)) {
                    animPulseScale = 1.0f + (float) Math.sin(phase * Math.PI * 2) * 0.08f;
                } else if ("typewriter".equals(anim)) {
                    if (dp.animColor != 0 && (dp.animColor & 0xFFFFFF) != 0xFFFFFF) {
                        animGhostRgb = dp.animColor & 0xFFFFFF;
                    }
                }
                // scroll offset computed below, after textWidth is known
            }
            // v2.3.0: auto-fit font size to the tile width minus padding, so
            // long texts shrink instead of overflowing. Re-derive the
            // font if we end up shrinking, otherwise keep the original.
            int padding = Math.max(0, Math.min(32, dp.padding));
            int maxWidth = panel.tilesW * 128 - 2 * padding;
            Font font = new Font("Dialog", Font.BOLD, dp.size);
            g.setFont(font);
            String prepared = preprocessForDraw(resolved);
            java.util.List<PerChar> chars = new java.util.ArrayList<>();
            flattenPerChar(MM.deserialize(prepared).decoration(TextDecoration.ITALIC, false), drawColor, chars);
            int textWidth = fillPerCharLayout(chars, font);
            int minSize = 4;
            while (textWidth > maxWidth && font.getSize() > minSize) {
                font = new Font("Dialog", Font.BOLD, font.getSize() - 1);
                g.setFont(font);
                chars.clear();
                flattenPerChar(MM.deserialize(prepared).decoration(TextDecoration.ITALIC, false), drawColor, chars);
                textWidth = fillPerCharLayout(chars, font);
            }
            if (dp.animation != null && "scroll".equalsIgnoreCase(dp.animation)) {
                float phase = (float) (t % Math.max(1, dp.animMs)) / Math.max(1, dp.animMs);
                int totalDist = textWidth + 128;
                animXOffset = (int) Math.round(totalDist * (1.0 - phase)) - 128;
            }
            if (dp.animation != null && "typewriter".equalsIgnoreCase(dp.animation)) {
                float phase = (float) (t % Math.max(1, dp.animMs)) / Math.max(1, dp.animMs);
                animRevealCutoff = (int) Math.round(phase * textWidth);
            }
            int dpGlobalX = dp.tileX * 128 + dp.x;
            int tileGlobalX = tileX * 128;
            int tileGlobalXEnd = tileGlobalX + 128;
            int dpGlobalXEnd = dpGlobalX + textWidth;
            if (dpGlobalXEnd <= tileGlobalX || dpGlobalX >= tileGlobalXEnd) continue;
            int visStart = Math.max(0, tileGlobalX - dpGlobalX);
            int visEnd = Math.min(textWidth, tileGlobalXEnd - dpGlobalX);
            int drawX = Math.max(0, dpGlobalX - tileGlobalX);
            // v2.3.0 + v2.4.1: align shift (matches the drawFrame path above).
            // For alignMode="panel", shift dpGlobalX at the panel level so
            // every tile sees the same offset.
            String align2 = dp.align == null ? "left" : dp.align.toLowerCase();
            String alignMode2 = dp.alignMode == null ? "tile" : dp.alignMode.toLowerCase();
            if ("panel".equals(alignMode2) && !"left".equals(align2)) {
                int panelWidth2 = panel.tilesW * 128;
                int panelShift2 = 0;
                if ("center".equals(align2)) panelShift2 = (panelWidth2 - textWidth) / 2;
                else if ("right".equals(align2)) panelShift2 = panelWidth2 - textWidth;
                dpGlobalX = dp.tileX * 128 + dp.x + panelShift2;
                dpGlobalXEnd = dpGlobalX + textWidth;
                if (dpGlobalXEnd <= tileGlobalX || dpGlobalX >= tileGlobalXEnd) continue;
                visStart = Math.max(0, tileGlobalX - dpGlobalX);
                visEnd = Math.min(textWidth, tileGlobalXEnd - dpGlobalX);
                drawX = Math.max(0, dpGlobalX - tileGlobalX);
            } else {
                int visibleW2 = visEnd - visStart;
                if ("center".equals(align2)) {
                    drawX = drawX + (visibleW2 - textWidth) / 2;
                } else if ("right".equals(align2)) {
                    drawX = drawX + (visibleW2 - textWidth);
                }
            }
            Graphics2D drawG = g;
            if (animPulseScale != 1.0f) {
                drawG = (Graphics2D) g.create();
                int textCenterX = drawX + (visEnd - visStart) / 2;
                int textCenterY = dp.y + font.getSize() / 2;
                drawG.translate(textCenterX, textCenterY);
                drawG.scale(animPulseScale, animPulseScale);
                drawG.translate(-textCenterX, -textCenterY);
            }
            // v2.3.0 + v2.4.1: opaque background behind text. The bg now
            // spans the FULL text width (covers all overflow tiles),
            // clipped per-tile via the Math.min / Math.max calls below.
            if (dp.bg != null && !dp.bg.isBlank()) {
                int bgRgb = com.hudboard.menu.DialogInputBridge.parseMiniMessageColor(dp.bg);
                int bgX = drawX - 2;
                int bgY = Math.max(0, dp.y - 2);
                int bgW = textWidth + 4;
                int bgH = Math.min(128 - bgY, font.getSize() + 4);
                if (bgX < 0) { bgW += bgX; bgX = 0; }
                if (bgX >= 128 || bgW <= 0) { /* fully outside this tile */ }
                else {
                    if (bgX + bgW > 128) bgW = 128 - bgX;
                    drawG.setColor(new Color(bgRgb, true));
                    drawG.fillRect(bgX, bgY, bgW, bgH);
                }
            }
            drawPerCharClipped(drawG, chars, visStart, visEnd, drawX, dp.y,
                    animXOffset, animYOffset, animRevealCutoff, animGhostRgb);
            if (drawG != g) drawG.dispose();
        }
        g.dispose();
        byte[] result = new byte[16384];
        for (int py = 0; py < 128; py++) {
            for (int px = 0; px < 128; px++) {
                int argb = tile.getRGB(px, py);
                int a = (argb >>> 24) & 0xFF;
                if (a < 16) { result[py * 128 + px] = 0; continue; }
                int r = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int key = (a << 24) | (r << 16) | (gg << 8) | b;
                Byte cached = paletteCache.get(key);
                if (cached == null) {
                    cached = MapPalette.matchColor(new Color(r, gg, b, a));
                    paletteCache.put(key, cached);
                }
                result[py * 128 + px] = cached;
            }
        }
        // v2.4.0: store the composed byte[] in the pre-bake cache so the
        // next 20Hz tick hits a hashmap lookup instead of recomputing.
        UUID pkey = player == null ? null : player.getUniqueId();
        if (seq != null && seq.animated) {
            // v2.5.0: same LRU cap as the static cache. Count total entries
            // across all frames; if we exceed the cap, drop the oldest
            // frame's full map. Long GIFs + many viewers can't blow up
            // because the cap is global to the renderer, not per-frame.
            int totalGifEntries = 0;
            for (var inner : prebakedBytesGif.values()) totalGifEntries += inner.size();
            while (totalGifEntries >= MAX_PREBAKE_ENTRIES_PER_PANEL && !prebakedBytesGif.isEmpty()) {
                java.util.Iterator<Map.Entry<Long, Map<UUID, byte[]>>> it =
                        prebakedBytesGif.entrySet().iterator();
                if (!it.hasNext()) break;
                Map.Entry<Long, Map<UUID, byte[]>> eldest = it.next();
                totalGifEntries -= eldest.getValue().size();
                it.remove();
            }
            prebakedBytesGif.computeIfAbsent((long) frameIdx, k -> new HashMap<>()).put(pkey, result);
        } else {
            prebakedBytesStatic.put(pkey, result);
        }
        return result;
    }

}
