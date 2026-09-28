package com.hudboard.panel;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A decoded animated GIF. Holds all frames + their per-frame delays in
 * milliseconds, and can serve the correct frame for a given time.
 *
 * GIF spec stores delay as centiseconds (1/100 s) but most modern encoders
 * use 10ms increments. We normalize to milliseconds.
 *
 * Memory: each frame is the same ARGB size as the source image. A 256x256
 * GIF with 30 frames is roughly 30 * 256 * 256 * 4 = 7.5 MB.
 */
public final class GifAnimation {

    public final BufferedImage[] frames;
    public final int[] delaysMs;
    public final int totalDurationMs;

    public GifAnimation(BufferedImage[] frames, int[] delaysMs) {
        this.frames = frames;
        this.delaysMs = delaysMs;
        int sum = 0;
        for (int d : delaysMs) sum += d;
        this.totalDurationMs = Math.max(1, sum);
    }

    /** True if this animation has more than one distinct frame. */
    public boolean isAnimated() { return frames != null && frames.length > 1; }

    /**
     * Return the frame visible at the given wall-clock time. Loops infinitely.
     * If the animation is a single frame, returns it always.
     */
    public BufferedImage getFrameAt(long timeMs) {
        if (frames == null || frames.length == 0) return null;
        if (frames.length == 1) return frames[0];
        if (totalDurationMs <= 0) return frames[0];
        long t = ((timeMs % totalDurationMs) + totalDurationMs) % totalDurationMs;
        long acc = 0;
        for (int i = 0; i < frames.length; i++) {
            acc += delaysMs[i];
            if (t < acc) return frames[i];
        }
        return frames[frames.length - 1];
    }

    /** Approximate memory footprint in bytes. */
    public int memoryBytes() {
        if (frames == null) return 0;
        int b = 0;
        for (BufferedImage f : frames) {
            if (f != null) b += f.getWidth() * f.getHeight() * 4;
        }
        return b;
    }

    /**
     * Read an animated GIF from a file. Returns null if the file is not a GIF
     * or has zero frames. Single-frame GIFs are returned as a 1-frame animation.
     */
    public static GifAnimation fromFile(java.io.File file) {
        if (file == null || !file.isFile()) return null;
        String name = file.getName().toLowerCase();
        if (!name.endsWith(".gif")) return null;
        try (javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(file)) {
            if (in == null) return null;
            java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) return null;
            javax.imageio.ImageReader reader = readers.next();
            reader.setInput(in);
            int n = reader.getNumImages(true);
            if (n <= 0) { reader.dispose(); return null; }
            List<BufferedImage> frames = new ArrayList<>(n);
            List<Integer> delays = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                BufferedImage frame = reader.read(i);
                frames.add(frame);
                // GIF delay is in centiseconds (1/100 s), 0 = "use default 10cs"
                int delayCs = 10;  // default 100ms per the GIF spec
                try {
                    javax.imageio.metadata.IIOMetadata md = reader.getImageMetadata(i);
                    if (md != null) {
                        String fmt = md.getNativeMetadataFormatName();
                        javax.imageio.metadata.IIOMetadataNode root = (javax.imageio.metadata.IIOMetadataNode) md.getAsTree(fmt);
                        // Look for <GraphicControlExtension><DelayTime>value</DelayTime>
                        javax.imageio.metadata.IIOMetadataNode gce = findNode(root, "GraphicControlExtension");
                        if (gce != null) {
                            String d = gce.getAttribute("delayTime");
                            if (d != null) {
                                try { delayCs = Integer.parseInt(d.trim()); } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                // Convert centiseconds to milliseconds (min 20ms to avoid stutter)
                int delayMs = Math.max(20, delayCs * 10);
                delays.add(delayMs);
            }
            reader.dispose();
            return new GifAnimation(
                    frames.toArray(new BufferedImage[0]),
                    delays.stream().mapToInt(Integer::intValue).toArray());
        } catch (Exception e) {
            return null;
        }
    }

    private static javax.imageio.metadata.IIOMetadataNode findNode(javax.imageio.metadata.IIOMetadataNode root, String name) {
        if (root == null) return null;
        if (name.equals(root.getNodeName())) return root;
        for (int i = 0; i < root.getLength(); i++) {
            javax.imageio.metadata.IIOMetadataNode found = findNode((javax.imageio.metadata.IIOMetadataNode) root.item(i), name);
            if (found != null) return found;
        }
        return null;
    }
}
