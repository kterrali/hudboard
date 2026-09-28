package com.hudboard.groups;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PlaceholderGroupManager}. These exercise the pure
 * validation/storage logic without spinning up a Bukkit plugin instance —
 * we instantiate the manager with {@code null} (the save() call would
 * NPE but every test path here only touches the in-memory map).
 *
 * <p>Group persistence is intentionally trivial (a YML dump of the whole
 * map on every mutation), so the file I/O doesn't need its own tests.
 * Validation rules and dedup behaviour, on the other hand, are the
 * kind of thing where an off-by-one or a forgotten {@code toLowerCase}
 * will silently corrupt groups.yml.</p>
 */
public class PlaceholderGroupManagerTest {

    private PlaceholderGroupManager gm;

    @BeforeEach
    public void setUp() {
        // Plugin isn't touched for validation/storage-only paths, so null
        // is fine. Tests that need disk I/O would override this with a real
        // plugin instance — we don't have any of those yet.
        gm = new PlaceholderGroupManager(null);
    }

    @Test
    public void create_validName_noKeysYet_returnsNull() {
        // Empty list is acceptable at creation time — the admin can add
        // placeholders with `group add` later.
        assertNull(gm.create("TopPvP", List.of()));
    }

    @Test
    public void create_normalisesSurroundingPercentAndCase() {
        gm.create("TopPvP", List.of("%Top1_Kills_Name%", "%Top1_Kills_Value%"));
        var stored = gm.all().get("toppvp");
        assertNotNull(stored);
        assertEquals(List.of("top1_kills_name", "top1_kills_value"), stored);
    }

    @Test
    public void create_duplicateName_returnsError() {
        gm.create("TopPvP", List.of("k1"));
        String err = gm.create("toppvp", List.of("k2")); // case-insensitive duplicate
        assertNotNull(err);
        assertTrue(err.toLowerCase().contains("already exists"), "Got: " + err);
    }

    @Test
    public void create_reservedName_returnsError() {
        String err = gm.create("server", List.of("k1"));
        assertNotNull(err);
        assertTrue(err.toLowerCase().contains("reserved"), "Got: " + err);
    }

    @Test
    public void create_invalidNameCharacters_returnsError() {
        assertNotNull(gm.create("name with spaces", List.of("k")));
        assertNotNull(gm.create("name/with/slashes", List.of("k")));
        assertNotNull(gm.create("", List.of("k")));
        assertNotNull(gm.create("a".repeat(33), List.of("k"))); // too long
    }

    @Test
    public void create_allBlankKeys_succeedsWithEmptyList() {
        // Since v1.4.0 a group can be created with zero keys — the admin
        // adds placeholders with `group add` afterwards.
        assertNull(gm.create("Empty", new java.util.ArrayList<>(List.of("", "   "))));
        assertTrue(gm.exists("empty"));
        assertTrue(gm.all().get("empty").isEmpty());
    }

    @Test
    public void create_blankKeysMixedWithReal_keepsOnlyReal() {
        assertNull(gm.create("Mixed", new java.util.ArrayList<>(List.of("", "real1", "  ", "real2"))));
        assertEquals(List.of("real1", "real2"), gm.all().get("mixed"));
    }

    @Test
    public void create_dedupsKeys() {
        gm.create("Dup", List.of("a", "A", "%a%", "b"));
        var stored = gm.all().get("dup");
        assertNotNull(stored);
        assertEquals(List.of("a", "b"), stored);
    }

    @Test
    public void addKey_appendsAndPersists() {
        gm.create("G", List.of("a"));
        assertTrue(gm.addKey("g", "b"));
        assertTrue(gm.addKey("G", "a"));     // already present — no-op success
        assertEquals(List.of("a", "b"), gm.all().get("g"));
    }

    @Test
    public void addKey_unknownGroup_returnsFalse() {
        assertFalse(gm.addKey("Nope", "k"));
    }

    @Test
    public void removeKey_returnsFalseOnUnknownGroupOrKey() {
        gm.create("G", List.of("a", "b"));
        assertFalse(gm.removeKey("nope", "a"));
        assertFalse(gm.removeKey("g", "missing"));
        assertEquals(List.of("a", "b"), gm.all().get("g"));
    }

    @Test
    public void removeKey_lastKey_deletesGroup() {
        gm.create("Solo", List.of("only"));
        assertTrue(gm.removeKey("solo", "only"));
        assertFalse(gm.exists("solo"));
        assertFalse(gm.delete("solo"));     // already gone
    }

    @Test
    public void delete_removesGroup() {
        gm.create("G", List.of("a"));
        assertTrue(gm.delete("g"));
        assertFalse(gm.exists("g"));
    }

    @Test
    public void isReserved_builtInNames() {
        for (String n : PlaceholderGroupManager.BUILTIN_SOURCES) {
            assertTrue(gm.isReserved(n), "Built-in '" + n + "' should be reserved");
        }
    }

    @Test
    public void isReservedIncludingPapi_dynamicList() {
        gm.refreshDynamicReservations(Set.of("Armor", "Player", "vault"));
        assertTrue(gm.isReservedIncludingPapi("armor"), "Case-insensitive PAPI match");
        assertTrue(gm.isReservedIncludingPapi("VAULT"));
        assertFalse(gm.isReservedIncludingPapi("FreeName"));
    }

    @Test
    public void browserLabel_hasGroupPrefix() {
        assertEquals("group:toppvp", PlaceholderGroupManager.browserLabel("toppvp"));
        // Even with empty name we still emit a valid label (used as Map key).
        assertEquals("group:", PlaceholderGroupManager.browserLabel(""));
    }

    // ---------------------------------------------------------------------
    // v1.4.1: YAML export / import
    // ---------------------------------------------------------------------

    @Test
    public void exportYaml_roundTripParsesBack() {
        gm.create("GroupA", List.of("key1", "key2"));
        gm.create("GroupB", List.of("k3"));
        String yaml = gm.exportYaml();
        assertTrue(yaml.contains("groupa"), "YAML must contain normalised group names");
        assertTrue(yaml.contains("key1"));
        // Re-import into a fresh manager and verify the keys survived.
        PlaceholderGroupManager copy = new PlaceholderGroupManager(null);
        int added = copy.importYaml(yaml, s -> { /* swallow warnings */ });
        assertEquals(3, added);
        assertEquals(List.of("key1", "key2"), copy.all().get("groupa"));
        assertEquals(List.of("k3"), copy.all().get("groupb"));
    }

    @Test
    public void importYaml_mergesExistingGroup_doesNotOverwrite() {
        gm.create("G", List.of("a", "b"));
        // YAML arrives with a DIFFERENT key, plus the existing 'a' (should dedup).
        String yaml = "G:\n  - a\n  - c\n";
        int added = gm.importYaml(yaml, s -> { });
        assertEquals(1, added, "Only 'c' is new");
        assertEquals(List.of("a", "b", "c"), gm.all().get("g"));
    }

    @Test
    public void importYaml_skipsReservedName_warns() {
        String yaml = "server:\n  - somekey\n";
        java.util.List<String> warnings = new java.util.ArrayList<>();
        int added = gm.importYaml(yaml, warnings::add);
        assertEquals(0, added);
        assertFalse(gm.exists("server"));
        assertFalse(warnings.isEmpty(), "Should warn about reserved name");
    }

    @Test
    public void importYaml_skipsInvalidYaml_silentlyReturnsZero() {
        int added = gm.importYaml("not:: valid:: yaml [[[", s -> { });
        assertEquals(0, added);
    }

    @Test
    public void importYaml_blankInput_returnsZero() {
        assertEquals(0, gm.importYaml("", s -> { }));
        assertEquals(0, gm.importYaml(null, s -> { }));
    }

    @Test
    public void importYaml_normalisesPercentStripAndCase() {
        // SnakeYAML 1.x can choke on bare `%` in flow-style; use single-quoted
        // strings to force it to treat them as literal.
        String yaml = "Mixed:\n  - '%Foo_BAR%'\n  - baz\n";
        int added = gm.importYaml(yaml, s -> { });
        assertEquals(2, added);
        assertEquals(List.of("foo_bar", "baz"), gm.all().get("mixed"));
    }
}
