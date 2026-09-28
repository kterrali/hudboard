package com.hudboard.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ManualKeysStorage}. We instantiate with
 * {@code plugin=null} so {@code save()} becomes a no-op (mirrors
 * {@code PlaceholderGroupManager}'s test setup). All tests rely only on
 * the in-memory map, which is exactly what's used for the v2.0.1
 * end-to-end behaviour.
 */
public class ManualKeysStorageTest {

    private ManualKeysStorage store;

    @BeforeEach
    public void setUp() {
        store = new ManualKeysStorage(null);
        store.load(); // bootstraps a fresh in-memory state
    }

    @Test
    public void add_normalisesPercentAndCase() {
        assertTrue(store.add("jobs", "%Jobs_Top_Lvl%"));
        assertEquals(List.of("jobs_top_lvl"), store.keysFor("jobs"));
    }

    @Test
    public void add_returnsFalseForDuplicate() {
        assertTrue(store.add("vip", "vip_rank"));
        assertFalse(store.add("vip", "VIP_RANK"));
        assertEquals(1, store.keysFor("vip").size());
    }

    @Test
    public void add_returnsFalseForNullOrBlank() {
        assertFalse(store.add("jobs", ""));
        assertFalse(store.add("jobs", "   "));
        assertFalse(store.add(null, "k"));
        assertFalse(store.add("jobs", null));
    }

    @Test
    public void keysFor_unknownSource_returnsEmpty() {
        assertTrue(store.keysFor("unknown").isEmpty());
        assertTrue(store.keysFor(null).isEmpty());
    }

    @Test
    public void remove_existingKey_returnsTrue_andDeletesEmptySource() {
        assertTrue(store.add("solo", "only_key"));
        assertTrue(store.remove("solo", "only_key"));
        assertTrue(store.keysFor("solo").isEmpty());
        // The source itself is gone (auto-cleaned)
        assertFalse(store.all().containsKey("solo"));
    }

    @Test
    public void remove_unknown_returnsFalse() {
        assertFalse(store.remove("missing", "k"));
        assertFalse(store.remove("solo", "k"));
    }

    @Test
    public void remove_keepsSourceIfOtherKeysRemain() {
        store.add("multi", "a");
        store.add("multi", "b");
        assertTrue(store.remove("multi", "a"));
        assertEquals(List.of("b"), store.keysFor("multi"));
        assertTrue(store.all().containsKey("multi"));
    }

    @Test
    public void clearSource_known_dropsIt() {
        store.add("a", "1");
        store.add("b", "2");
        assertTrue(store.clearSource("a"));
        assertNull(store.all().get("a"));
        assertNotNull(store.all().get("b"));
    }

    @Test
    public void clearSource_unknown_returnsFalse() {
        assertFalse(store.clearSource("nope"));
        assertFalse(store.clearSource(null));
    }

    @Test
    public void clear_wipesEverything() {
        store.add("a", "1");
        store.add("b", "2");
        store.clear();
        assertTrue(store.all().isEmpty());
    }

    @Test
    public void add_separateKeys_areIsolated() {
        store.add("jobs", "jobs_rank");
        store.add("vip", "vip_rank");
        // Different sources — should NOT collide.
        assertEquals(List.of("jobs_rank"), store.keysFor("jobs"));
        assertEquals(List.of("vip_rank"), store.keysFor("vip"));
    }

    @Test
    public void add_afterRemove_reAdds() {
        store.add("x", "y");
        store.remove("x", "y");
        assertTrue(store.add("x", "y")); // Should succeed — remove dropped it
        assertEquals(List.of("y"), store.keysFor("x"));
    }

    @Test
    public void all_returnsUnmodifiableSnapshot() {
        store.add("a", "k");
        var all = store.all();
        assertThrows(UnsupportedOperationException.class,
                () -> all.put("z", java.util.List.of("k")));
        // Mutating keysFor's list directly must also throw — the map
        // values are wrapped in Collections.unmodifiableList.
        assertThrows(UnsupportedOperationException.class,
                () -> all.get("a").add("another"));
    }
}
