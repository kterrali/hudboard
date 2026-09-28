package com.hudboard.panel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InfoPanelRenderer#sliceByPixels(String, int[], int, int)}
 * and the per-character cross-tile path (the fix for the "gradient is wrong
 * after a tile boundary" bug).
 *
 * <p>These tests run pure — no Bukkit, no Paper, no AWT, no Minecraft server.
 * They exercise the static helpers that the renderer's draw path calls.</p>
 */
class InfoPanelRendererSliceTest {

    // -------------------------------------------------------------------
    // sliceByPixels — the original bug fix
    // -------------------------------------------------------------------

    @Test
    void slice_emptyOrNull_returnsEmpty() {
        // null text → empty (the function short-circuits before the widths check)
        assertEquals("", InfoPanelRenderer.sliceByPixels((String) null, new int[0], 0, 10));
        // empty text → empty
        assertEquals("", InfoPanelRenderer.sliceByPixels("", new int[0], 0, 10));
        // null widths with non-empty text is a programming error (not tested here)
    }

    @Test
    void slice_invalidRange_returnsEmpty() {
        // endPx <= 0
        assertEquals("", InfoPanelRenderer.sliceByPixels("hello", new int[]{1,1,1,1,1}, 0, 0));
        assertEquals("", InfoPanelRenderer.sliceByPixels("hello", new int[]{1,1,1,1,1}, 0, -1));
        // startPx >= endPx
        assertEquals("", InfoPanelRenderer.sliceByPixels("hello", new int[]{1,1,1,1,1}, 5, 5));
        assertEquals("", InfoPanelRenderer.sliceByPixels("hello", new int[]{1,1,1,1,1}, 7, 5));
    }

    @Test
    void slice_fullWindow_returnsAll() {
        // "hello", 5 wide chars, window [0, 5) → "hello"
        assertEquals("hello", InfoPanelRenderer.sliceByPixels("hello", new int[]{1,1,1,1,1}, 0, 5));
    }

    @Test
    void slice_partialWindow_returnsOverlappingChars() {
        // "hello", 1-wide chars: visible chars are those with startPx in [1, 4) → "ell"
        assertEquals("ell", InfoPanelRenderer.sliceByPixels("hello", new int[]{1,1,1,1,1}, 1, 4));
    }

    /**
     * The original bug: with variable-width chars, a char that straddles the
     * start boundary was kept BOTH by the previous tile and the next tile,
     * because the old check was {@code charEnd > startPx && charStart < endPx}
     * (overlap) instead of {@code charStart >= startPx && charStart < endPx}
     * (starts inside).
     *
     * <p>Concretely: text "ab" with widths {10, 10}. At startPx=5, the 'a'
     * straddles (startPx 0, endPx 10) so the old overlap check would keep
     * 'a' on the next tile — duplicating it. The strict check drops it.</p>
     */
    @Test
    void slice_doesNotDuplicateCharThatStraddlesStartBoundary() {
        // "ab", widths 10 each. Slicing [5, 15) — the 'a' is at [0,10), so
        // its startPx=0 which is OUTSIDE [5,15). The new check correctly
        // drops it; the old overlap check would have kept it.
        assertEquals("b", InfoPanelRenderer.sliceByPixels("ab", new int[]{10, 10}, 5, 15));
    }

    @Test
    void slice_keepsCharThatEndsPastEndPx_ifItStartedInside() {
        // "abc" widths {5,5,5}, slicing [0, 12) — 'c' starts at 10 (inside)
        // and ends at 15 (past endPx=12). It MUST be kept; otherwise the
        // trailing char of a panel goes missing.
        assertEquals("abc", InfoPanelRenderer.sliceByPixels("abc", new int[]{5, 5, 5}, 0, 12));
    }

    @Test
    void slice_keepsCharThatStartsAtExactlyStartPx() {
        // 'b' starts at 5 = startPx. Inclusive on the left.
        assertEquals("b", InfoPanelRenderer.sliceByPixels("ab", new int[]{5, 5}, 5, 10));
    }

    @Test
    void slice_dropsCharThatStartsAtExactlyEndPx() {
        // 'b' starts at 5 = endPx of the [0, 5) window. Exclusive on the right.
        assertEquals("a", InfoPanelRenderer.sliceByPixels("ab", new int[]{5, 5}, 0, 5));
    }

    @Test
    void slice_realWorldCrossTile_15PixelsWide_3Tiles128() {
        // 50 chars, each 7px wide → 350 px total. Tiles are 128 wide.
        // With the "starts inside" rule:
        //   tile 0 [0, 128)   → startPx 0..126 → 19 chars (0..18)
        //   tile 1 [128, 256) → startPx 126..245 → wait, char 18 starts at
        //     18*7=126 which is < 128, so it belongs to tile 0. Char 19
        //     starts at 133 (>= 128) so it belongs to tile 1.
        //   tile 0 has chars 0..18 (19 chars), tile 1 has chars 19..36
        //   (18 chars), tile 2 [256, 350) has chars 37..49 (13 chars).
        String text = "a".repeat(50);
        int[] widths = new int[50];
        java.util.Arrays.fill(widths, 7);
        String t0 = InfoPanelRenderer.sliceByPixels(text, widths, 0, 128);
        String t1 = InfoPanelRenderer.sliceByPixels(text, widths, 128, 256);
        String t2 = InfoPanelRenderer.sliceByPixels(text, widths, 256, 350);
        assertEquals(19, t0.length(), "tile 0 should have 19 chars (startPx 0,7,14,..,126)");
        assertEquals(18, t1.length(), "tile 1 should have 18 chars (startPx 133,..,245)");
        assertEquals(13, t2.length(), "tile 2 should have 13 chars (startPx 259,..,343)");
        // No char is duplicated across tiles
        assertEquals(50, t0.length() + t1.length() + t2.length());
        // Reassembled equals the original
        assertEquals(text, t0 + t1 + t2);
    }

    @Test
    void slice_rejectsWidthsLengthMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> InfoPanelRenderer.sliceByPixels("hello", new int[]{1, 1, 1}, 0, 5));
    }

    // -------------------------------------------------------------------
    // flattenPerChar — the per-char color computation that fixes gradients
    // -------------------------------------------------------------------

    private final MiniMessage MM = MiniMessage.miniMessage();

    @Test
    void flattenPerChar_plainText_singleColor() {
        // "<red>Hi</red>" → 2 chars, both red
        List<InfoPanelRenderer.PerChar> out = new ArrayList<>();
        int n = InfoPanelRenderer.flattenPerCharStatic(
                MM.deserialize("<red>Hi</red>"), 0xFF0000, out);
        assertEquals(2, n);
        assertEquals(2, out.size());
        assertEquals('H', out.get(0).c());
        assertEquals('i', out.get(1).c());
        // Both same color (the red from the <red> tag)
        // MiniMessage's <red> is the legacy Minecraft color code red: #FF5555
        assertEquals(0xFF5555, out.get(0).rgb());
        assertEquals(0xFF5555, out.get(1).rgb());
    }

    @Test
    void flattenPerChar_plainText_inheritsDefault() {
        // No tags, no color in component → use the defaultRgb arg.
        List<InfoPanelRenderer.PerChar> out = new ArrayList<>();
        InfoPanelRenderer.flattenPerCharStatic(MM.deserialize("Hi"), 0xC8AA6E, out);
        assertEquals(0xC8AA6E, out.get(0).rgb());
        assertEquals(0xC8AA6E, out.get(1).rgb());
    }

    @Test
    void flattenPerChar_gradient_assignsPerCharColors() {
        // The whole point of the per-char path: a 5-char gradient gives
        // 5 distinct colors, not 1.
        List<InfoPanelRenderer.PerChar> out = new ArrayList<>();
        InfoPanelRenderer.flattenPerCharStatic(
                MM.deserialize("<gradient:red:blue>Hello</gradient>"), 0xFFFFFF, out);
        assertEquals(5, out.size());
        // First char must be red (#FF5555), last must be blue (#5555FF)
        assertEquals(0xFF5555, out.get(0).rgb(), "H should be red-ish");
        assertEquals(0x5555FF, out.get(4).rgb(), "o should be blue-ish");
        // Middle chars are intermediate (R+G+B all in 0x55-0xFF range)
        for (InfoPanelRenderer.PerChar pc : out) {
            int r = (pc.rgb() >> 16) & 0xFF;
            int g = (pc.rgb() >> 8) & 0xFF;
            int b = pc.rgb() & 0xFF;
            assertTrue(r >= 0x55 && r <= 0xFF, "r out of expected range: " + Integer.toHexString(r));
            assertTrue(g >= 0x55 && g <= 0xFF, "g out of expected range: " + Integer.toHexString(g));
            assertTrue(b >= 0x55 && b <= 0xFF, "b out of expected range: " + Integer.toHexString(b));
        }
        // Each consecutive pair should be distinct (gradient = different colors)
        for (int i = 0; i < out.size() - 1; i++) {
            assertNotEquals(out.get(i).rgb(), out.get(i + 1).rgb(),
                    "consecutive gradient chars should differ");
        }
    }

    @Test
    void slice_newline_aliases_allWork() {
        // <newline>, <n>, <br>, <NEWLINE> and <n /> all produce a real \n.
        // The per-char list should contain a PerChar with c=='\n' for each.
        String[] tags = {"<newline>", "<n>", "<br>", "<NEWLINE>", "<n />"};
        for (String tag : tags) {
            String input = "Hello" + tag + "World";
            List<InfoPanelRenderer.PerChar> chars = new ArrayList<>();
            InfoPanelRenderer.flattenPerCharStatic(
                    MM.deserialize(preprocessForDrawTag(input)), 0xFFFFFF, chars);
            // Walk the per-char list, count newlines vs visible chars
            int newlines = 0;
            int visible = 0;
            for (InfoPanelRenderer.PerChar pc : chars) {
                if (pc.c() == '\n') newlines++;
                else visible++;
            }
            assertEquals(1, newlines, tag + " should produce exactly one newline");
            assertEquals(10, visible, tag + " should keep both 'Hello' and 'World' (5+5 chars)");
        }
    }

    /**
     * The actual preprocessing is private to the renderer. We re-implement
     * just the newline aliases here so the test doesn't need a Bukkit
     * Plugin. Keep this in sync with {@code InfoPanelRenderer#preprocessForDraw}.
     */
    private static String preprocessForDrawTag(String text) {
        return text
                .replaceAll("(?i)<\\s*newline\\s*/?\\s*>", "\n")
                .replaceAll("(?i)<\\s*n\\s*/?\\s*>", "\n")
                .replaceAll("(?i)<\\s*br\\s*/?\\s*>", "\n");
    }

    @Test
    void flattenPerChar_colorInheritance_acrossGradient() {
        // Text inside a <red>...</red> wrapper but outside the gradient block
        // should keep the red color. The gradient block's "Y" starts at red
        // and goes to blue over 1 char (so it stays red). "Z" should inherit
        // the red from the outer <red> wrapper.
        List<InfoPanelRenderer.PerChar> out = new ArrayList<>();
        InfoPanelRenderer.flattenPerCharStatic(
                MM.deserialize("<red>X<gradient:red:blue>Y</gradient>Z</red>"), 0xFFFFFF, out);
        assertEquals(3, out.size());
        assertEquals('X', out.get(0).c());
        assertEquals('Y', out.get(1).c());
        assertEquals('Z', out.get(2).c());
        assertEquals(0xFF5555, out.get(0).rgb(), "X should be red (from <red>)");
        assertEquals(0xFF5555, out.get(1).rgb(), "Y should be red (start of gradient)");
        assertEquals(0xFF5555, out.get(2).rgb(), "Z should be red (inherited, NOT default white)");
    }

    // -------------------------------------------------------------------
    // The actual bug we fixed: gradient colors are preserved cross-tile
    // -------------------------------------------------------------------

    @Test
    void crossTile_gradient_preservesColorsAcrossTileBoundary() {
        // 5 chars, each 7px wide → 35px total. First tile: [0, 16), second
        // tile: [16, 35). The gradient is red→blue over 5 chars, so:
        //   H(red) e(mix) l(mix) l(mix) o(blue)
        // After slicing with "starts inside" rule:
        //   tile 0 (visStart=0, visEnd=16): H(0), e(7), l(14) — 3 chars
        //   tile 1 (visStart=16, visEnd=35): l(21), o(28) — 2 chars
        // (the 'l' at startPx 14 belongs to tile 0 because 14 < 16)
        // The OLD segment-based code would MiniMessage-deserialize each slice
        // and recompute the gradient, giving wrong colors on tile 1.
        List<InfoPanelRenderer.PerChar> all = new ArrayList<>();
        InfoPanelRenderer.flattenPerCharStatic(
                MM.deserialize("<gradient:red:blue>Hello</gradient>"), 0xFFFFFF, all);
        InfoPanelRenderer.fillPerCharLayoutStatic(all, c -> 7);

        List<InfoPanelRenderer.PerChar> tile0 = InfoPanelRenderer.visiblePerChar(all, 0, 16);
        List<InfoPanelRenderer.PerChar> tile1 = InfoPanelRenderer.visiblePerChar(all, 16, 35);

        // Tile 0: H (lineX 0), e (lineX 7), l (lineX 14). All 3 have
        // lineX < 16, so all belong to tile 0.
        assertEquals(3, tile0.size());
        assertEquals('H', tile0.get(0).c());
        assertEquals('e', tile0.get(1).c());
        assertEquals('l', tile0.get(2).c());
        // Colors must be the FIRST 3 colors of the original 5-char gradient
        // (i.e. reddish), NOT a recomputed 3-step gradient that's mostly red.
        int tile0Red = tile0.get(2).rgb(); // 'l' on tile 0
        int allRedThird = all.get(2).rgb();
        assertEquals(allRedThird, tile0Red,
                "tile-0 'l' must keep its original 3rd-of-5 gradient color");

        // Tile 1: l (lineX 21), o (lineX 28). The 'l' at lineX 14 belongs to
        // tile 0 (14 < 16), so it's NOT in tile 1. Only chars with lineX in
        // [16, 35) are kept: l(21), o(28).
        assertEquals(2, tile1.size());
        assertEquals('l', tile1.get(0).c());
        assertEquals('o', tile1.get(1).c());
        // 'o' on tile 1 must be the original last-of-5 color (blue-ish), NOT
        // a recomputed 2-step gradient that lands at the blue endpoint after
        // a single 'l' (the old bug).
        int tile1Blue = tile1.get(1).rgb();
        int allBlue = all.get(4).rgb();
        assertEquals(allBlue, tile1Blue,
                "tile-1 'o' must keep the FULL-text last color, not a recomputed 2-step gradient");
        // The blue should be near 0x5555FF (the last color of red→blue over 5 chars)
        int b = tile1Blue & 0xFF;
        assertTrue(b >= 0x55, "blue channel of 'o' must be ≥ 0x55, got " + Integer.toHexString(b));
    }

    @Test
    void crossTile_gradient_longText_slicingStaysConsistent() {
        // 20-char gradient, 8 px each → 160 px. Across 2 tiles (128 each).
        // Tile 0 [0, 128): chars with startPx in [0, 128) → chars 0..16
        // (startPx 0, 8, 16, ..., 120) — wait, char 16 starts at 128 which
        // is excluded. So tile 0 has chars 0..15 (16 chars, startPx 0..120).
        // Tile 1 [128, 160): chars with startPx in [128, 160) → chars 16..19
        // (startPx 128, 136, 144, 152). 4 chars.
        String text = "X".repeat(20);
        List<InfoPanelRenderer.PerChar> all = new ArrayList<>();
        InfoPanelRenderer.flattenPerCharStatic(
                MM.deserialize("<gradient:#FFD700:#FF8800>" + text + "</gradient>"), 0xFFFFFF, all);
        InfoPanelRenderer.fillPerCharLayoutStatic(all, c -> 8);

        List<InfoPanelRenderer.PerChar> t0 = InfoPanelRenderer.visiblePerChar(all, 0, 128);
        List<InfoPanelRenderer.PerChar> t1 = InfoPanelRenderer.visiblePerChar(all, 128, 160);
        assertEquals(16, t0.size());
        assertEquals(4, t1.size());
        // The colors of tile 0 + tile 1 should be the same as the original
        // per-char colors (in order, no duplicates, no skips)
        for (int i = 0; i < 20; i++) {
            InfoPanelRenderer.PerChar orig = all.get(i);
            InfoPanelRenderer.PerChar sliced = (i < 16) ? t0.get(i) : t1.get(i - 16);
            assertEquals(orig.rgb(), sliced.rgb(),
                    "char " + i + " color differs between full text and slice: "
                            + Integer.toHexString(orig.rgb()) + " vs " + Integer.toHexString(sliced.rgb()));
        }
    }
}
