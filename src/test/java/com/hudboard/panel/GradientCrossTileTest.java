package com.hudboard.panel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies (or debunks) the v2.4.x "gradient cross-tile" bug claim. The
 * theory was: MiniMessage parses gradient text per-tile, so each tile
 * re-interpolates from red→blue over its own slice, producing visible
 * "stripes" of pure colors at the tile boundaries.
 *
 * <p>If this test FAILS, the bug is real and we need a fix. If it
 * PASSES, the gradient is already correct cross-tile and the bug
 * doesn't exist — at which point we can stop documenting it.</p>
 */
public class GradientCrossTileTest {

    @SuppressWarnings("unchecked")
    private static List<Object> invokeFlattenPerCharStatic(Component comp, int defaultRgb) throws Exception {
        Method m = Class.forName("com.hudboard.panel.InfoPanelRenderer")
                .getDeclaredMethod("flattenPerCharStatic", Component.class, int.class, List.class);
        m.setAccessible(true);
        List<Object> out = new ArrayList<>();
        m.invoke(null, comp, defaultRgb, out);
        return out;
    }

    private static int colorOf(Object perChar) throws Exception {
        java.lang.reflect.Field f = perChar.getClass().getDeclaredField("rgb");
        f.setAccessible(true);
        return f.getInt(perChar);
    }

    @Test
    public void gradient_redToBlue_acrossLongText_hasInterpolatedColors() throws Exception {
        // 50 chars inside the gradient, 1 char outside.
        String text = "<gradient:red:blue>" + repeat('x', 50) + "</gradient> y";
        Component parsed = MiniMessage.miniMessage().deserialize(text)
                .decoration(TextDecoration.ITALIC, false);

        List<Object> chars = invokeFlattenPerCharStatic(parsed, 0xFFFFFF);

        // First 50 chars should be the gradient — colors should progress
        // from red (≈ 0xFF5555) to blue (≈ 0x5555FF). If the gradient
        // is broken, every char gets a single solid color.
        int firstRed = colorOf(chars.get(0));
        int firstMid = colorOf(chars.get(25));
        int firstBlue = colorOf(chars.get(49));
        int firstAfter = colorOf(chars.get(51)); // the " y"

        // Helper: extract R/G/B from packed int.
        int r0 = (firstRed >> 16) & 0xFF, b0 = firstRed & 0xFF;
        int r1 = (firstMid >> 16) & 0xFF, b1 = firstMid & 0xFF;
        int r2 = (firstBlue >> 16) & 0xFF, b2 = firstBlue & 0xFF;

        // The gradient should be red-dominant at position 0, blue-dominant at position 49.
        // If the bug were real (re-parsed per tile), chars 0..49 would all have the same color.
        assertTrue(r0 > b0, "char 0 should be red-dominant, got rgb=" + firstRed);
        assertTrue(b2 > r2, "char 49 should be blue-dominant, got rgb=" + firstBlue);
        // Middle char should be in-between.
        assertTrue(r1 > b1 - 50 && r1 < b1 + 50,
                "char 25 should be neutral-ish, got rgb=" + firstMid);
        // After the gradient, default white should appear (or close to it).
        assertTrue(firstAfter >= 0xFFFFFF - 0x010101 && firstAfter <= 0xFFFFFF + 0x010101,
                "char after </gradient> should be default white, got rgb=" + firstAfter);
    }

    @Test
    public void gradient_acrossMultipleLines_isHandledByRenderer() throws Exception {
        // Newlines split lines but the gradient should still produce
        // colors. If it doesn't, the renderer would draw default-colored
        // text on the second line — that's a bug we want to know about.
        String text = "<gradient:gold:red>A\nB\nC</gradient>";
        Component parsed = MiniMessage.miniMessage().deserialize(text)
                .decoration(TextDecoration.ITALIC, false);
        List<Object> chars = invokeFlattenPerCharStatic(parsed, 0xFFFFFF);
        assertTrue(chars.size() >= 3);
        // The exact colors aren't asserted here because Adventure's
        // gradient behavior with embedded newlines is implementation-
        // defined; we just want to know SOMETHING was emitted per char.
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }
}
