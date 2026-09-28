package com.hudboard.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: drive {@link DataManager#discoverPapiIdentifiers()}
 * through a fake PAPI class that lives in the test source tree.
 *
 * <p>Why this exists: the real {@code PlaceholderAPI} class is only
 * present at runtime on a Paper server. Without a mock we could never
 * exercise the {@code ((Class&lt;?&gt;) papi).getMethod("getRegisteredIdentifiers")}
 * reflection path from a unit test, and v1.3.5 was lost to an
 * {@code InvocationTargetException} in exactly that path. Mocking it
 * forces the code to load the static method via reflection and proves
 * the dispatch still works when the plugin manager isn't around.</p>
 *
 * <p>The mock class lives in the test sources — same package as the
 * test runner — so we can hand it directly to the manager via the
 * package-private test hook.</p>
 */
public class DataManagerPapiMockTest {

    private DataManager dm;

    @BeforeEach
    public void setUp() {
        dm = new DataManager(null);
        dm.setPapiClassForTest(FakePapi.class);
    }

    @Test
    public void discover_returnsRegisteredIds_lowercased() {
        // FakePapi.getRegisteredIdentifiers() returns
        // [Armor, Player, Vault, SERVERTIME] which after lowercase should
        // match what a real server with those PAPI expansions installed
        // would produce.
        Set<String> ids = dm.discoverPapiIdentifiers(true);
        assertTrue(ids.contains("armor"), "Should lowercase 'Armor' → 'armor'");
        assertTrue(ids.contains("player"));
        assertTrue(ids.contains("vault"));
        assertTrue(ids.contains("servertime"));
        assertEquals(4, ids.size(), "No spurious duplicates or empties");
    }

    @Test
    public void discover_returnsEmptyWhenNoPapi() {
        dm.setPapiClassForTest(null);
        Set<String> ids = dm.discoverPapiIdentifiers();
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    public void discover_dedupsCaseSensitiveDuplicates() {
        // FakePapi2 returns [armor, Armor, ARMOR, armor] — all four
        // collapse to the single normalised id.
        dm.setPapiClassForTest(FakePapi2.class);
        Set<String> ids = dm.discoverPapiIdentifiers();
        assertEquals(1, ids.size());
        assertTrue(ids.contains("armor"));
    }

    // ------------------------------------------------------------------
    // Fake PAPI classes. These are *not* mocks — they're real classes
    // the JVM can load and reflect over. The reflection path under
    // test relies on the static-method dispatch matching the real
    // PlaceholderAPI surface, so using a stub class is closer to the
    // production behaviour than e.g. a Mockito mock would be.
    // ------------------------------------------------------------------

    /** Returns a hardcoded identifier list simulating a populated PAPI. */
    public static class FakePapi {
        public static Collection<String> getRegisteredIdentifiers() {
            return Arrays.asList("Armor", "Player", "Vault", "SERVERTIME");
        }
    }

    /** Returns duplicates with mixed casing — tests normalisation. */
    public static class FakePapi2 {
        public static Collection<String> getRegisteredIdentifiers() {
            return Arrays.asList("armor", "Armor", "ARMOR", "armor");
        }
    }
}
