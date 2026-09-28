package com.hudboard.panel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-math tests for the 5 animation phases used by HudBoard.
 * These don't run Bukkit — they verify the math the renderer uses to
 * compute offsets and scales is sane.
 */
class InfoPanelAnimationsTest {

    /** Replicates the bob offset formula: ±3px vertical sin oscillation. */
    private static int bobOffset(long t, long periodMs) {
        double phase = (double) (t % periodMs) / periodMs;
        return (int) Math.round(Math.sin(phase * Math.PI * 2) * 3);
    }

    /** Replicates the glitch step: a new "step" starts every 80ms. */
    private static long glitchStep(long t) { return t / 80L; }

    /** Replicates the glitch X/Y offset for a given step. */
    private static int glitchOffset(long step, int salt) {
        java.util.Random r = new java.util.Random(step * 1000L + salt);
        return r.nextInt(9) - 4;
    }

    /** Replicates the pulse scale formula: 1.0 ± 0.08. */
    private static float pulseScale(long t, long periodMs) {
        double phase = (double) (t % periodMs) / periodMs;
        return 1.0f + (float) Math.sin(phase * Math.PI * 2) * 0.08f;
    }

    // -- bob --------------------------------------------------------------

    @Test
    void bob_offset_is_in_range() {
        for (long t = 0; t < 10_000; t += 50) {
            int off = bobOffset(t, 1500);
            assertTrue(off >= -3 && off <= 3, "bob offset out of range at t=" + t + ": " + off);
        }
    }

    @Test
    void bob_offset_returns_to_zero_at_full_cycle() {
        // sin(2π) == 0, so at t == periodMs the offset should be 0
        assertEquals(0, bobOffset(1500L, 1500L));
        assertEquals(0, bobOffset(3000L, 1500L));
    }

    // -- glitch -----------------------------------------------------------

    @Test
    void glitch_step_advances_every_80ms() {
        long s0 = glitchStep(0L);
        long s1 = glitchStep(80L);
        long s2 = glitchStep(160L);
        assertEquals(s0 + 1, s1);
        assertEquals(s0 + 2, s2);
        // Within the same step the seed stays the same
        assertEquals(s0, glitchStep(40L));
        assertEquals(s0, glitchStep(79L));
    }

    @Test
    void glitch_offset_is_deterministic_per_step() {
        long step = glitchStep(500L);
        // Same step → same offsets
        assertEquals(glitchOffset(step, 0), glitchOffset(step, 0));
        // Different steps → likely different offsets (could collide, but rarely)
        // We just verify the formula doesn't crash and stays in range
        for (long s = 0; s < 100; s++) {
            assertTrue(glitchOffset(s, 0) >= -4 && glitchOffset(s, 0) <= 4);
            assertTrue(glitchOffset(s, 1) >= -4 && glitchOffset(s, 1) <= 4);
        }
    }

    // -- pulse ------------------------------------------------------------

    @Test
    void pulse_scale_stays_in_safe_range() {
        for (long t = 0; t < 10_000; t += 50) {
            float scale = pulseScale(t, 1500);
            assertTrue(scale >= 0.9f && scale <= 1.1f,
                    "pulse scale out of range at t=" + t + ": " + scale);
        }
    }

    @Test
    void pulse_scale_returns_to_one_at_full_cycle() {
        // sin(2π) == 0 → scale = 1.0
        float s = pulseScale(1500L, 1500L);
        assertEquals(1.0f, s, 1e-6f);
    }

    // -- scroll + typewriter sanity ---------------------------------------

    @Test
    void scroll_total_distance_equals_textWidth_plus_tileWidth() {
        // Mirrors the formula in the renderer
        int textWidth = 200;
        long t = 750L, periodMs = 1500L;
        double phase = (double) (t % periodMs) / periodMs;
        int totalDist = textWidth + 128;
        int offset = (int) Math.round(totalDist * (1.0 - phase)) - 128;
        assertTrue(offset >= -128 && offset <= 200,
                "scroll offset should be in [-128, textWidth], got " + offset);
    }
}
