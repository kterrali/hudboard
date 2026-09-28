package com.hudboard.panel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A user-defined panel profile (PNG + sidecar yml).
 * Lives in plugins/HudBoard/panels/<id>.png + <id>.yml
 *
 * Each profile has a single background image (static PNG/JPG or animated GIF).
 * There is no per-instance "mode" anymore — every placed instance of a given
 * profile uses the same background.
 */
public class InfoPanel {

    public String id;
    public String name;
    public String description;
    public String permission;
    public File file;
    /** Lazily loaded. Access via {@link #getImage()} — never read directly. */
    private volatile BufferedImage image;
    /** Lazily loaded animated GIF (frames + delays) for the default image. */
    private volatile GifAnimation defaultGif;
    public int tilesW, tilesH;
    /** v2.4.0: per-panel disabled-worlds override. If non-null/non-empty,
     *  a placed instance whose world is in this list is skipped during
     *  the render tick (no frame sent, no listener attached). Null/empty
     *  = follow the global {@code config.yml} setting. */
    public java.util.List<String> disabledWorlds;

    public final List<DataPoint> dataPoints = new ArrayList<>();
    public final Map<String, String> userPlaceholders = new LinkedHashMap<>();

    /** Wall-clock ms of last image access (used to decide when to unload). */
    private long lastImageAccessMs = 0L;

    /**
     * Return the (static) background image for this profile, loaded lazily.
     * Returns null if the file cannot be read.
     * For animated GIFs, the renderer should call {@link #getFrameAt(long)} instead.
     */
    public BufferedImage getImage() {
        lastImageAccessMs = System.currentTimeMillis();
        BufferedImage local = image;
        if (local != null) return local;
        if (file == null) return null;
        synchronized (this) {
            if (image != null) return image;
            try {
                image = ImageIO.read(file);
            } catch (IOException e) {
                java.util.logging.Logger.getLogger("HudBoard")
                        .warning("Failed to load panel image " + file + ": " + e.getMessage());
                image = null;
            }
            return image;
        }
    }

    /**
     * Return the frame of the appropriate image (static or GIF) visible at
     * the given wall-clock time. Falls back to the static image if the file
     * is not an animated GIF.
     */
    public BufferedImage getFrameAt(long timeMs) {
        GifAnimation gif = getGif();
        if (gif != null && gif.isAnimated()) {
            return gif.getFrameAt(timeMs);
        }
        return getImage();
    }

    /**
     * Look up (lazily) the animated GIF for this profile. Returns null if
     * the file is not a GIF, doesn't exist, or could not be parsed.
     */
    public GifAnimation getGif() {
        GifAnimation local = defaultGif;
        if (local != null) return local == GIF_NULL ? null : local;
        if (file == null) return null;
        // Only attempt GIF load if the file extension is .gif
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".gif")) {
            defaultGif = GIF_NULL;
            return null;
        }
        synchronized (this) {
            if (defaultGif != null) return defaultGif == GIF_NULL ? null : defaultGif;
            defaultGif = GifAnimation.fromFile(file);
            if (defaultGif == null) defaultGif = GIF_NULL;
            return defaultGif == GIF_NULL ? null : defaultGif;
        }
    }

    /** Sentinel: "no GIF for this profile" (a single-frame GIF is also a valid result). */
    private static final GifAnimation GIF_NULL = new GifAnimation(new BufferedImage[0], new int[0]);

    // -------------------------------------------------------------------------
    // GifSequence (pre-baked, async-loaded, the new fast path)
    // -------------------------------------------------------------------------

    private volatile GifSequence defaultSequence;
    /** Sentinel for "no GIF for this profile". */
    private static final GifSequence SEQ_NULL = GifSequence.empty();

    /**
     * Returns a future that completes with the pre-baked {@link GifSequence}
     * for this profile, or null if no animated image is available.
     * Loading happens off the main thread.
     */
    public CompletableFuture<GifSequence> getSequence() {
        GifSequence cached = defaultSequence;
        if (cached != null) {
            return cached == SEQ_NULL ? CompletableFuture.completedFuture(null) : CompletableFuture.completedFuture(cached);
        }
        if (file == null || !file.getName().toLowerCase(Locale.ROOT).endsWith(".gif")) {
            defaultSequence = SEQ_NULL;
            return CompletableFuture.completedFuture(null);
        }
        int rw = tilesW, rh = tilesH;
        return GifSequence.loadAsync(file, rw, rh).thenApply(seq -> {
            if (seq == null || !seq.animated) {
                defaultSequence = SEQ_NULL;
                return null;
            }
            defaultSequence = seq;
            return seq;
        });
    }

    /**
     * Estimate current memory footprint of the loaded image(s) in bytes.
     * A 1280x1280 ARGB BufferedImage is 4 bytes/pixel = 6.55 MB.
     */
    public long estimateMemoryBytes() {
        long bytes = 0;
        if (image != null) bytes += (long) image.getWidth() * image.getHeight() * 4;
        if (defaultGif != null && defaultGif != GIF_NULL) bytes += defaultGif.memoryBytes();
        return bytes;
    }

    /**
     * Unload the image(s) to free memory. The next call to {@link #getImage()}
     * or {@link #getGif()} will reload from disk. Returns the number of bytes
     * freed (approximate).
     */
    public long unloadImages() {
        long freed = 0;
        if (image != null) {
            freed += (long) image.getWidth() * image.getHeight() * 4;
            image = null;
        }
        if (defaultGif != null && defaultGif != GIF_NULL) {
            freed += defaultGif.memoryBytes();
        }
        defaultGif = null;
        if (defaultSequence != null && defaultSequence != SEQ_NULL) {
            freed += defaultSequence.memoryBytes();
        }
        defaultSequence = null;
        return freed;
    }

    /** Returns true if the image hasn't been accessed in over `maxAgeMs` milliseconds. */
    public boolean isStale(long maxAgeMs) {
        return lastImageAccessMs > 0
                && System.currentTimeMillis() - lastImageAccessMs > maxAgeMs;
    }

    public static class DataPoint {
        public String key;
        public int tileX, tileY;
        public int x, y;
        public String text;
        public int size = 12;
        /** Base text color as a 0xRRGGBB int. Kept for backward-compat with
         *  existing yml files; if {@link #baseColor} is set, it overrides
         *  this with a MiniMessage tag (e.g. <red>, <#FF8800>, <gradient:...>). */
        public int color = 0xFFFFFF;
        /** Optional MiniMessage color tag applied as a prefix to the text
         *  before rendering. Examples: <red>, <#FF8800>, <gradient:#CB0DFC:#0EEB7D>.
         *  When set, this takes precedence over the int {@link #color}. */
        public String baseColor;
        public String animation;     // null | pulse | blink | breathe
        public int animColor = 0xFF8888;
        public long animMs = 1500;
        // --- v2.3.0: new visual fields ---
        /** Optional opaque background color drawn behind the text. MiniMessage
         *  tag (e.g. <red>, <#FF8800>) or null for transparent. Improves
         *  readability on busy map backgrounds. */
        public String bg;
        /** Horizontal alignment: "left" (default), "center", "right". */
        public String align = "left";
        /** Padding in pixels (0..32) between the text and the tile edge.
         *  Applied to whichever side the align targets. */
        public int padding = 0;
        /** v2.4.1: alignment scope.
         *  "tile"  (default) — {@link #align} operates within each visible
         *           tile window independently (legacy behaviour).
         *  "panel" — {@link #align} operates on the entire panel width
         *           (so a centered text on a 2×2 panel is centered across
         *           the full 256px, not split into two centered halves). */
        public String alignMode = "tile";
    }
}
