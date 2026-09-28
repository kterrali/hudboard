package com.hudboard.panel;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for v2.1.1: the previous approximate width function
 * {@code approxCharWidth} returned a fixed value per character category
 * (letter=0.55, uppercase=0.65, etc). The bug was that Java AWT draws
 * kerned pairs (Kt, fa, ph, ...) NARROWER than the sum of individual
 * widths — so the rendered text ended up with phantom gaps inside words.
 *
 * <p>Fix: replace approxCharWidth with {@code realCharWidth} which uses
 * {@code TextLayout.getAdvance()} for the actual rendered advance.
 * This test exercises the real function (via reflection — it's a private
 * static method) on the classic problematic pairs and asserts the
 * advances are within a sane range, not the inflated approximation.</p>
 */
public class TextLayoutWidthTest {

    /** Same FRC settings as the renderer uses. */
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private double invoke(Font font, char c) throws Exception {
        Method m = InfoPanelRenderer.class.getDeclaredMethod(
                "realCharWidth", Font.class, char.class, FontRenderContext.class);
        m.setAccessible(true);
        return (double) m.invoke(null, font, c, FRC);
    }

    @Test
    public void realCharWidth_isAtLeastOne_forAnyChar() throws Exception {
        Font font = new Font("Dialog", Font.BOLD, 12);
        // For size=12, all chars must report >= 1px width (no zero / negative
        // values that would break the per-char layout accumulator).
        for (char c : new char[]{'K', 't', 'e', 'r', 'a', 'l', 'i', 'f', 'p', 'h', 's', 'o', 'n', 'g', 'm', 'w', ' '}) {
            double w = invoke(font, c);
            assertTrue(w >= 1.0, "Char '" + c + "' reported width " + w + " — should be >= 1.0");
        }
    }

    @Test
    public void realCharWidth_distinguishesDistinctChars() throws Exception {
        Font font = new Font("Dialog", Font.BOLD, 12);
        double wa = invoke(font, 'a');
        double wr = invoke(font, 'r');
        double wi = invoke(font, 'i');
        // a vs r should differ — fixed-coefficient would say both = 0.55×12=6.6
        assertNotEquals(wa, wr, 0.001, "realCharWidth should distinguish 'a' from 'r'");
        // i is narrower than a
        assertTrue(wi < wa, "i should be narrower than a, got i=" + wi + " a=" + wa);
    }

    @Test
    public void realCharWidth_neverThrowsOnAscii() throws Exception {
        Font font = new Font("Dialog", Font.BOLD, 14);
        // Smoke test — every printable ASCII char must produce a width
        // without throwing (TextLayout has been known to reject some
        // control chars on certain JVMs; the fallback inside realCharWidth
        // should catch anything weird).
        for (int c = 32; c < 127; c++) {
            double w = invoke(font, (char) c);
            assertTrue(w > 0, "Char " + c + " has non-positive width");
        }
    }
}
