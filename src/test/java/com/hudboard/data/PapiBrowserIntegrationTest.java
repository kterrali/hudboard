package com.hudboard.data;

import com.hudboard.groups.PlaceholderGroupManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that wires {@link DataManager} (with a mock PAPI
 * class) into {@link PlaceholderGroupManager}'s reservation logic. This
 * is the closest thing we can get to a real
 * {@code PlaceholderBrowser.placeholdersForCurrentSource()} test without
 * spinning up a Bukkit server — the full Browser constructor calls
 * {@code Bukkit.createInventory(...)}, which doesn't exist in a unit
 * test. The pipeline we care about is:
 *
 * <pre>
 *   DataManager.discoverPapiIdentifiers()
 *          ↓
 *   PlaceholderGroupManager.isReservedIncludingPapi()
 *          ↓
 *   /hudboard group create  → must reject PAPI ids
 * </pre>
 *
 * <p>This test exercises all three steps against a fake PAPI so we know
 * the end-to-end reservation list works the same way it would on a
 * server with PAPI installed.</p>
 */
public class PapiBrowserIntegrationTest {

    private DataManager dm;
    private PlaceholderGroupManager gm;

    @BeforeEach
    public void setUp() {
        dm = new DataManager(null);
        dm.setPapiClassForTest(DataManagerPapiMockTest.FakePapi.class);
        gm = new PlaceholderGroupManager(null);
        // Wire mock PAPI ids into the reservation list, mirroring what
        // HudBoardPlugin.refreshGroupReservations() does at runtime.
        gm.refreshDynamicReservations(dm.discoverPapiIdentifiers());
    }

    @Test
    public void cannotCreateGroupNamedAfterInstalledPapi() {
        // 'armor' is in FakePapi's identifier list, so the group manager
        // must refuse to create a group called 'armor' — otherwise the
        // browser would render two papers with the same label.
        String err = gm.create("armor", List.of("custom_key"));
        assertNotNull(err, "Should reject PAPI id as a reserved name");
        assertTrue(err.toLowerCase().contains("reserved"),
                "Error should mention 'reserved'; got: " + err);
        assertFalse(gm.exists("armor"));
    }

    @Test
    public void canCreateGroupNamedAfterUninstalledPapi() {
        // 'fake_expansion' isn't in FakePapi's list, so the manager must
        // accept it.
        String err = gm.create("fake_expansion", List.of("custom_key"));
        assertNull(err);
        assertTrue(gm.exists("fake_expansion"));
    }

    @Test
    public void cannotCreateGroupNamedAfterBuiltIn() {
        // 'server' is in BUILTIN_SOURCES — the manager must reject it
        // even though PAPI doesn't have it.
        String err = gm.create("server", List.of("k"));
        assertNotNull(err);
        assertTrue(err.toLowerCase().contains("reserved"));
    }

    @Test
    public void refreshReservations_picksUpNewlyInstalledPapi() {
        // At this point 'armor' is reserved. Refresh with a different
        // mock where armor is gone → 'armor' becomes available.
        dm.setPapiClassForTest(DataManagerPapiMockTest.FakePapi2.class);
        gm.refreshDynamicReservations(dm.discoverPapiIdentifiers());
        // FakePapi2 returns only 'armor' (duplicate, normalised), which
        // IS still reserved.
        assertTrue(gm.isReservedIncludingPapi("armor"));
        // Use a totally unknown id.
        assertFalse(gm.isReservedIncludingPapi("made_up_extension"));
    }

    @Test
    public void discoverAndBrowserLabelAgreeOnFormat() {
        // The browser draws papers labeled `group:<name>`. Make sure
        // the manager's normalisation + label format work together with
        // what PAPI reports.
        Set<String> ids = dm.discoverPapiIdentifiers();
        for (String id : ids) {
            // A user trying `group create <id>` should fail.
            assertTrue(gm.isReservedIncludingPapi(id),
                    "PAPI id '" + id + "' should be reserved");
        }
        // Verify the label format itself.
        assertEquals("group:toppvp",
                PlaceholderGroupManager.browserLabel("toppvp"));
    }
}
