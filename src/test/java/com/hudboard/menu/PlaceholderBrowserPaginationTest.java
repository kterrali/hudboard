package com.hudboard.menu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure-math pagination slice of {@link PlaceholderBrowser}.
 * We extract {@code pageSlice(totalItems, page)} as a static helper precisely
 * so it can be exercised without spinning up a Bukkit world — the
 * {@code PlaceholderBrowser} constructor needs a live
 * {@link org.bukkit.inventory.Inventory}, which JUnit can't supply.
 *
 * <p>The math itself is small (36 papers per page, ceiling division) but
 * pagination is the kind of thing where an off-by-one bug leaves the user
 * looking at an empty page or missing papers, so a few regression tests
 * are worth their keep.</p>
 */
public class PlaceholderBrowserPaginationTest {

    @Test
    public void emptyList_singlePage_noItems() {
        int[] r = PlaceholderBrowser.pageSlice(0, 0);
        assertEquals(1, r[0], "Empty list still shows 1 page (avoids 0/0 divide)");
        assertEquals(0, r[1], "from=0");
        assertEquals(0, r[2], "to=0 (no items)");
    }

    @Test
    public void singleItem_fitsOnPage1() {
        int[] r = PlaceholderBrowser.pageSlice(1, 0);
        assertEquals(1, r[0], "1 item = 1 page");
        assertEquals(0, r[1]);
        assertEquals(1, r[2]);
    }

    @Test
    public void exactly36Items_singlePage() {
        int[] r = PlaceholderBrowser.pageSlice(36, 0);
        assertEquals(1, r[0], "36 items fill exactly 1 page");
        assertEquals(0, r[1]);
        assertEquals(36, r[2]);
    }

    @Test
    public void exactly37Items_twoPages() {
        int[] r0 = PlaceholderBrowser.pageSlice(37, 0);
        assertEquals(2, r0[0]);
        assertEquals(0, r0[1]);
        assertEquals(36, r0[2], "Page 1 holds indices 0..35");

        int[] r1 = PlaceholderBrowser.pageSlice(37, 1);
        assertEquals(2, r1[0]);
        assertEquals(36, r1[1]);
        assertEquals(37, r1[2], "Page 2 holds the single overflow item");
    }

    @Test
    public void seventySixItems_threePages_realisticStatistic() {
        // Statistic expansion shipped 76 templates in the user's install
        // and triggered the original pagination request.
        int[] r0 = PlaceholderBrowser.pageSlice(76, 0);
        assertEquals(3, r0[0]);
        assertEquals(0, r0[1]);
        assertEquals(36, r0[2]);

        int[] r1 = PlaceholderBrowser.pageSlice(76, 1);
        assertEquals(3, r1[0]);
        assertEquals(36, r1[1]);
        assertEquals(72, r1[2]);

        int[] r2 = PlaceholderBrowser.pageSlice(76, 2);
        assertEquals(3, r2[0]);
        assertEquals(72, r2[1]);
        assertEquals(76, r2[2], "Page 3 has the remaining 4 items");
    }

    @Test
    public void exactMultipleOf36_snapToLastPage() {
        // 72 items = exactly 2 full pages.
        int[] r1 = PlaceholderBrowser.pageSlice(72, 1);
        assertEquals(2, r1[0]);
        assertEquals(36, r1[1]);
        assertEquals(72, r1[2], "Page 2 stops at the last item, no empty overflow");
    }

    @Test
    public void pageBeyondEnd_clampsToLastPage() {
        // User pages past the end (e.g. after a refresh shrunk the list) —
        // we must snap back to a valid page, not crash on negative from.
        int[] r = PlaceholderBrowser.pageSlice(10, 5);
        assertEquals(1, r[0]);
        assertEquals(0, r[1]);
        assertEquals(10, r[2]);
    }

    @Test
    public void negativePage_clampsToZero() {
        int[] r = PlaceholderBrowser.pageSlice(20, -3);
        assertEquals(1, r[0]);
        assertEquals(0, r[1]);
        assertEquals(20, r[2]);
    }

    @Test
    public void negativeItemCount_treatedAsZero() {
        int[] r = PlaceholderBrowser.pageSlice(-1, 0);
        assertEquals(1, r[0]);
        assertEquals(0, r[1]);
        assertEquals(0, r[2]);
    }
}
