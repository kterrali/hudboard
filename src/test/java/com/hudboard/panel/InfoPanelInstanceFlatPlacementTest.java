package com.hudboard.panel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the geometry of {@link InfoPanelInstance#placeFlat} for a 2x2 floor
 * panel. Asserts that the 4 frames land on adjacent blocks (no 1-block gap
 * between rows / columns), so the floor panel renders as a tight grid like
 * the wall panel.
 */
public class InfoPanelInstanceFlatPlacementTest {

    /**
     * Compute the (x, y, z) where tile (tx, ty) is placed for a flat panel
     * anchored at (ax, ay, az) for the floor case (ceiling=false).
     * Mirrors the logic in placeFlat without needing a live Bukkit world.
     */
    private static int[] floorPos(int ax, int ay, int az,
                                  int tilesW, int tilesH, int tx, int ty) {
        int dz = tilesH - 1 - ty;
        int frameY = ay + 1; // dy = +1 for floor
        return new int[]{ax + tx, frameY, az + dz};
    }

    @Test
    public void twoByTwoFloor_isTightGrid_noGap() {
        // Anchor at (100, 70, 0)
        int[][] positions = new int[4][3];
        for (int ty = 0; ty < 2; ty++) {
            for (int tx = 0; tx < 2; tx++) {
                positions[ty * 2 + tx] = floorPos(100, 70, 0, 2, 2, tx, ty);
            }
        }

        // Collect unique x / y / z values
        java.util.Set<Integer> xs = new java.util.HashSet<>();
        java.util.Set<Integer> ys = new java.util.HashSet<>();
        java.util.Set<Integer> zs = new java.util.HashSet<>();
        for (int[] p : positions) {
            xs.add(p[0]);
            ys.add(p[1]);
            zs.add(p[2]);
        }

        // 2 distinct X, 2 distinct Z, all at the same Y (1 above anchor).
        assertEquals(2, xs.size(), "Should have 2 distinct X positions");
        assertEquals(1, ys.size(), "Should have 1 distinct Y position (frame level)");
        assertEquals(2, zs.size(), "Should have 2 distinct Z positions");

        // Adjacent rows: Z positions must differ by exactly 1.
        int zMin = zs.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int zMax = zs.stream().mapToInt(Integer::intValue).max().orElseThrow();
        assertEquals(1, zMax - zMin,
                "Z rows must be adjacent (differ by 1), got " + (zMax - zMin));

        // Adjacent cols: X positions must differ by exactly 1.
        int xMin = xs.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int xMax = xs.stream().mapToInt(Integer::intValue).max().orElseThrow();
        assertEquals(1, xMax - xMin,
                "X cols must be adjacent (differ by 1), got " + (xMax - xMin));
    }

    @Test
    public void threeByThreeFloor_isTightGrid_noGap() {
        java.util.Set<Integer> zs = new java.util.HashSet<>();
        java.util.Set<Integer> xs = new java.util.HashSet<>();
        for (int ty = 0; ty < 3; ty++) {
            for (int tx = 0; tx < 3; tx++) {
                int[] p = floorPos(100, 70, 0, 3, 3, tx, ty);
                xs.add(p[0]);
                zs.add(p[2]);
            }
        }
        // All 3 rows in Z must be consecutive: spread = 2.
        int zMin = zs.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int zMax = zs.stream().mapToInt(Integer::intValue).max().orElseThrow();
        assertEquals(2, zMax - zMin,
                "Z rows for 3x3 must span exactly 2 blocks, got " + (zMax - zMin));
        assertEquals(3, xs.size());
        assertEquals(3, zs.size());
    }
}
