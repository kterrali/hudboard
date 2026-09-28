package com.hudboard.panel;

import com.hudboard.HudBoardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.map.MapPalette;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A pre-baked animated GIF, ready to render on map canvases.
 *
 * <h2>Why pre-bake?</h2>
 * <p>The naive approach is to read the GIF and crop+draw it on every map
 * render. That's slow (BufferedImage ops, GC pressure) and produces choppy
 * animation because Minecraft only re-renders maps every 250-500ms.</p>
 *
 * <p>Instead, on load we:</p>
 * <ol>
 *   <li>Decode all frames + delays via {@link ImageReader} (or the madgag
 *       fallback if javax.imageio fails).</li>
 *   <li>Handle GIF disposal methods correctly (restore-to-previous /
 *       restore-to-background) so partial-frame GIFs compose properly.</li>
 *   <li>For every (frame, tile) pair, downsample to 128x128 and quantize
 *       to the vanilla {@link MapPalette} colour index. The result is a
 *       {@code byte[128*128]} ready to be written to a {@link org.bukkit.map.MapCanvas}
 *       with {@code setPixel()} — or sent directly via NMS packet injection.</li>
 * </ol>
 *
 * <p>At render time, we do {@code System.arraycopy(frames[currentFrameIdx][tile], 0,
 * output, 0, 16384)} and then iterate to call {@code setPixel()}. No image
 * processing, no allocations.</p>
 *
 * <h2>Disposal method handling</h2>
 * <p>Real GIFs use disposal methods to avoid storing the full image in every
 * frame. For example a "loading spinner" might only store the rotating arc
 * in each frame and rely on {@code restoreToBackground} to clear the previous
 * arc before drawing the next.</p>
 *
 * <p>We replicate this with a "master image" + "previous image" buffer:</p>
 * <ol>
 *   <li>Start with a transparent master.</li>
 *   <li>For each frame: apply onto the master, snapshot, then either keep
 *       the master, clear the modified region, or roll back to the previous
 *       snapshot, depending on the frame's disposal method.</li>
 *   <li>The snapshots are what we pre-bake into {@code tilesByFrame}.</li>
 * </ol>
 */
public class GifSequence {

    /** Pre-baked tile bytes: {@code tilesByFrame[frameIdx][tileIdx][pixelIdx]}. */
    public final byte[][][] tilesByFrame;
    /** Per-frame delay in ms. {@code delaysMs[frameIdx]}. */
    public final int[] delaysMs;
    /** Sum of {@link #delaysMs}. */
    public final int totalDurationMs;
    /** Width/height of the source GIF, after any resize. */
    public final int width, height;
    /** Tile grid. */
    public final int tilesW, tilesH;
    /** True if this is an animated GIF (more than 1 frame). */
    public final boolean animated;

    private GifSequence(byte[][][] tb, int[] d, int w, int h) {
        this.tilesByFrame = tb;
        this.delaysMs = d;
        int sum = 0; for (int v : d) sum += v;
        this.totalDurationMs = sum;
        this.width = w; this.height = h;
        this.tilesW = (w + 127) / 128;
        this.tilesH = (h + 127) / 128;
        this.animated = d.length > 1;
    }

    /** Sentinel factory: a no-frame, no-tile, not-animated placeholder. */
    public static GifSequence empty() {
        return new GifSequence(new byte[0][0][0], new int[0], 0, 0);
    }

    /** Frame index active at wall-clock time {@code timeMs}. */
    public int frameAtTime(long timeMs) {
        if (!animated || totalDurationMs <= 0) return 0;
        long t = ((timeMs % totalDurationMs) + totalDurationMs) % totalDurationMs;
        long acc = 0;
        for (int i = 0; i < delaysMs.length; i++) {
            acc += delaysMs[i];
            if (t < acc) return i;
        }
        return delaysMs.length - 1;
    }

    /** Total memory used by the pre-baked data. */
    public long memoryBytes() {
        long b = 0;
        for (byte[][] f : tilesByFrame) for (byte[] t : f) b += t.length;
        return b;
    }

    // ---------------------------------------------------------------------
    // Loading (async, multi-strategy fallback)
    // ---------------------------------------------------------------------

    /**
     * Load a GIF from disk asynchronously. The result is a fully pre-baked
     * {@link GifSequence}. Loading happens on a worker thread; the returned
     * future completes on the same worker.
     */
    public static CompletableFuture<GifSequence> loadAsync(File f, int reqTilesW, int reqTilesH) {
        Executor exec = HudBoardPlugin.getAsyncExecutor();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loadWithFallback(f, reqTilesW, reqTilesH);
            } catch (Throwable t) {
                Logger.getLogger("HudBoard").log(Level.WARNING,
                        "[HudBoard] Failed to load GIF " + f + ": " + t, t);
                return null;
            }
        }, exec);
    }

    private static GifSequence loadWithFallback(File f, int reqTilesW, int reqTilesH) throws IOException {
        Throwable last = null;
        // Strategy 1: javax.imageio (handles 99% of GIFs)
        try { return loadWithJavaX(f, reqTilesW, reqTilesH); }
        catch (Throwable t) { last = t; }
        // Strategy 2: madgag GIF library (fallback for malformed GIFs)
        try { return loadWithMadGag(f, reqTilesW, reqTilesH); }
        catch (Throwable t) { last = t; }
        // Strategy 3: static fallback (treat as single-frame image)
        try { return loadStatic(f, reqTilesW, reqTilesH); }
        catch (Throwable t) { last = t; }
        throw new IOException("All GIF loaders failed: " + last, last);
    }

    private static GifSequence loadWithJavaX(File f, int reqTilesW, int reqTilesH) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(f)) {
            if (iis == null) throw new IOException("Could not open stream for " + f);
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(iis);
            int numFrames = reader.getNumImages(true);
            if (numFrames <= 0) throw new IOException("No frames in GIF " + f);
            // Read stream metadata to know the canvas size
            int canvasW = 0, canvasH = 0;
            IIOMetadata streamMd = reader.getStreamMetadata();
            if (streamMd != null) {
                IIOMetadataNode root = (IIOMetadataNode) streamMd.getAsTree(streamMd.getNativeMetadataFormatName());
                IIOMetadataNode lsd = findChild(root, "LogicalScreenDescriptor");
                if (lsd != null) {
                    canvasW = Integer.parseInt(lsd.getAttribute("logicalScreenWidth"));
                    canvasH = Integer.parseInt(lsd.getAttribute("logicalScreenHeight"));
                }
            }
            if (canvasW <= 0 || canvasH <= 0) {
                BufferedImage first = reader.read(0);
                canvasW = first.getWidth();
                canvasH = first.getHeight();
            }
            // We pad/resize to a multiple of 128 so tiles line up with the map grid
            int targetW = Math.max(reqTilesW * 128, roundUp(canvasW, 128));
            int targetH = Math.max(reqTilesH * 128, roundUp(canvasH, 128));
            // Read frames + their disposal / delay metadata
            List<FrameSource> sources = new ArrayList<>(numFrames);
            for (int i = 0; i < numFrames; i++) {
                BufferedImage img = reader.read(i);
                IIOMetadata imgMd = reader.getImageMetadata(i);
                IIOMetadataNode root = (IIOMetadataNode) imgMd.getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode gce = findChild(root, "GraphicControlExtension");
                int delay = 100;
                String disposal = "none";
                if (gce != null) {
                    try {
                        int cs = Integer.parseInt(gce.getAttribute("delayTime"));
                        delay = Math.max(20, cs * 10);   // centiseconds -> ms, floor 20ms
                    } catch (NumberFormatException ignored) {}
                    String d = gce.getAttribute("disposalMethod");
                    if (d != null) disposal = d;
                }
                int x = 0, y = 0;
                IIOMetadataNode desc = findChild(root, "ImageDescriptor");
                if (desc != null) {
                    x = Integer.parseInt(desc.getAttribute("imageLeftPosition"));
                    y = Integer.parseInt(desc.getAttribute("imageTopPosition"));
                }
                sources.add(new FrameSource(img, x, y, delay, disposal));
            }
            return bake(sources, targetW, targetH);
        }
    }

    private static GifSequence loadWithMadGag(File f, int reqTilesW, int reqTilesH) throws IOException {
        // Use reflection so we don't hard-fail if the lib isn't on the classpath.
        // (It's bundled as a runtime dependency in pom.xml so reflection should
        // always succeed; this is just defensive.)
        try {
            Class<?> decoderClass = Class.forName("com.madgag.gif.fmsware.GifDecoder");
            Object decoder = decoderClass.getDeclaredConstructor().newInstance();
            // read(InputStream) returns 0 on success
            try (InputStream in = java.nio.file.Files.newInputStream(f.toPath())) {
                Method read = decoderClass.getMethod("read", InputStream.class);
                int rc = (int) read.invoke(decoder, in);
                if (rc != 0) throw new IOException("GifDecoder.read returned " + rc);
            }
            Method getFrameCount = decoderClass.getMethod("getFrameCount");
            int n = (int) getFrameCount.invoke(decoder);
            Method getFrame = decoderClass.getMethod("getFrame", int.class);
            Method getDelay = decoderClass.getMethod("getDelay", int.class);
            Method getImage = decoderClass.getMethod("getImage");  // GifDecoder stores one image at a time
            List<FrameSource> sources = new ArrayList<>(n);
            int w = 0, h = 0;
            for (int i = 0; i < n; i++) {
                getFrame.invoke(decoder, i);
                BufferedImage img = (BufferedImage) getImage.invoke(decoder);
                int delay = Math.max(20, (int) getDelay.invoke(decoder, i));
                if (w == 0) { w = img.getWidth(); h = img.getHeight(); }
                // madgag doesn't expose disposal easily, treat as "none"
                sources.add(new FrameSource(img, 0, 0, delay, "none"));
            }
            int targetW = Math.max(reqTilesW * 128, roundUp(w, 128));
            int targetH = Math.max(reqTilesH * 128, roundUp(h, 128));
            return bake(sources, targetW, targetH);
        } catch (ClassNotFoundException cnfe) {
            throw new IOException("madgag GIF library not available", cnfe);
        } catch (Throwable t) {
            throw new IOException("madgag load failed: " + t, t);
        }
    }

    private static GifSequence loadStatic(File f, int reqTilesW, int reqTilesH) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null) throw new IOException("Cannot read image: " + f);
        int targetW = Math.max(reqTilesW * 128, roundUp(img.getWidth(), 128));
        int targetH = Math.max(reqTilesH * 128, roundUp(img.getHeight(), 128));
        List<FrameSource> sources = new ArrayList<>(1);
        sources.add(new FrameSource(img, 0, 0, 0, "none"));
        return bake(sources, targetW, targetH);
    }

    private record FrameSource(BufferedImage img, int x, int y, int delayMs, String disposal) {}

    /**
     * Apply the disposal-method logic and produce pre-baked byte[][][].
     */
    private static GifSequence bake(List<FrameSource> sources, int targetW, int targetH) {
        int n = sources.size();
        int tilesW = (targetW + 127) / 128;
        int tilesH = (targetH + 127) / 128;
        int tileCount = tilesW * tilesH;
        byte[][][] tiles = new byte[n][tileCount][128 * 128];
        int[] delays = new int[n];

        // Master + previous-snapshot, both ARGB. Used to honour disposal methods.
        BufferedImage master = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D masterG = master.createGraphics();
        masterG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        BufferedImage previous = null;
        try {
            for (int i = 0; i < n; i++) {
                FrameSource f = sources.get(i);
                // Snapshot the previous master if any frame in the future will need it
                previous = deepCopy(master);

                // Draw frame onto master at its declared position
                BufferedImage resized = ensureSize(f.img, targetW, targetH);
                masterG.drawImage(resized, f.x, f.y, null);

                // Bake this frame's pixels into per-tile byte arrays
                delays[i] = f.delayMs;
                encodeMasterToTiles(master, tilesW, tilesH, tiles[i]);

                // Apply disposal
                String d = f.disposal == null ? "none" : f.disposal;
                switch (d) {
                    case "restoreToBackgroundColor" -> {
                        // Clear the (x, y, w, h) region to transparent
                        Graphics2D g2 = master.createGraphics();
                        g2.setComposite(AlphaComposite.Clear);
                        g2.fillRect(f.x, f.y, resized.getWidth(), resized.getHeight());
                        g2.dispose();
                    }
                    case "restoreToPrevious" -> {
                        // Roll the master back to the snapshot taken before this frame
                        if (previous != null) {
                            Graphics2D g2 = master.createGraphics();
                            g2.setComposite(AlphaComposite.Src);
                            g2.drawImage(previous, 0, 0, null);
                            g2.dispose();
                        }
                    }
                    case "none", "unspecified", "" -> { /* keep master as-is */ }
                    default -> { /* unknown disposal → no-op (same as none) */ }
                }
            }
        } finally {
            masterG.dispose();
        }
        return new GifSequence(tiles, delays, targetW, targetH);
    }

    private static BufferedImage ensureSize(BufferedImage in, int w, int h) {
        if (in.getWidth() == w && in.getHeight() == h) return in;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(in, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage deepCopy(BufferedImage in) {
        BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(in, 0, 0, null);
        g.dispose();
        return out;
    }

    private static void encodeMasterToTiles(BufferedImage master, int tilesW, int tilesH, byte[][] tileBytes) {
        int idx = 0;
        for (int ty = 0; ty < tilesH; ty++) {
            for (int tx = 0; tx < tilesW; tx++) {
                byte[] tile = tileBytes[idx++];
                int baseX = tx * 128;
                int baseY = ty * 128;
                for (int py = 0; py < 128; py++) {
                    int y = baseY + py;
                    if (y >= master.getHeight()) {
                        // Past bottom — fill with 0 (transparent)
                        for (int px = 0; px < 128; px++) tile[py * 128 + px] = 0;
                        continue;
                    }
                    for (int px = 0; px < 128; px++) {
                        int x = baseX + px;
                        if (x >= master.getWidth()) { tile[py * 128 + px] = 0; continue; }
                        int argb = master.getRGB(x, y);
                        int a = (argb >>> 24) & 0xFF;
                        if (a < 16) { tile[py * 128 + px] = 0; continue; }   // transparent
                        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                        tile[py * 128 + px] = MapPalette.matchColor(new Color(r, g, b, a));
                    }
                }
            }
        }
    }

    private static int roundUp(int v, int step) { return ((v + step - 1) / step) * step; }

    private static IIOMetadataNode findChild(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            IIOMetadataNode child = (IIOMetadataNode) parent.item(i);
            if (name.equals(child.getNodeName())) return child;
        }
        return null;
    }
}
