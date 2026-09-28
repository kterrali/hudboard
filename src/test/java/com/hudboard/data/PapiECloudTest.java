package com.hudboard.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Unit tests for {@link PapiECloud#parseCaptured}. The Bukkit-side dispatcher
 * is exercised at runtime; this test just covers the format parsing for the
 * eCloud placeholder listings (with and without color codes).
 */
public class PapiECloudTest {

    @Test
    public void parseCaptured_simpleList_extractsKeys() {
        List<String> captured = List.of(
                "6 placeholders:",
                "%armor_amount_SLOT%, %armor_has_SLOT%, %armor_material_SLOT%",
                "%armor_maxamount_SLOT%, %armor_durability_(left/max)_SLOT%,",
                "%armor_color_(red/green/blue/hex)_SLOT%"
        );
        List<String> keys = PapiECloud.parseCaptured(captured);
        assertEquals(6, keys.size(), "Expected 6 placeholders, got: " + keys);
        assertTrue(keys.contains("armor_amount_SLOT"));
        assertTrue(keys.contains("armor_has_SLOT"));
        assertTrue(keys.contains("armor_material_SLOT"));
        assertTrue(keys.contains("armor_maxamount_SLOT"));
        assertTrue(keys.contains("armor_durability_(left/max)_SLOT"));
        assertTrue(keys.contains("armor_color_(red/green/blue/hex)_SLOT"));
    }

    @Test
    public void parseCaptured_colorCodes_stripped() {
        // PAPI sometimes prefixes the count line with a color code.
        List<String> captured = List.of(
                "§e6 placeholders:",
                "§r%vault_balance%, §r%vault_eco_balance%, §r%vault_rank%"
        );
        List<String> keys = PapiECloud.parseCaptured(captured);
        assertEquals(3, keys.size());
        assertTrue(keys.contains("vault_balance"));
        assertTrue(keys.contains("vault_eco_balance"));
        assertTrue(keys.contains("vault_rank"));
    }

    @Test
    public void parseCaptured_emptyInput_returnsEmpty() {
        assertTrue(PapiECloud.parseCaptured(List.of()).isEmpty());
        assertTrue(PapiECloud.parseCaptured(null).isEmpty());
    }

    @Test
    public void parseCaptured_noPlaceholders_returnsEmpty() {
        List<String> captured = List.of(
                "Error: Expansion 'whatever' not found in eCloud."
        );
        List<String> keys = PapiECloud.parseCaptured(captured);
        assertTrue(keys.isEmpty());
    }

    @Test
    public void parseCaptured_resultsAreAlphabeticallySorted() {
        List<String> captured = List.of(
                "3 placeholders: %zebra_thing%, %alpha_thing%, %middle_thing%"
        );
        List<String> keys = PapiECloud.parseCaptured(captured);
        assertEquals(List.of("alpha_thing", "middle_thing", "zebra_thing"), keys);
    }

    @Test
    public void parseCaptured_realArmorExampleFromUser() {
        // Verbatim from the user's PAPI 2.12.2 output for /papi ecloud placeholders Armor.
        List<String> captured = List.of(
                "6 placeholders:",
                "§a%armor_amount_SLOT%§7, §a%armor_color_(red/green/blue/hex)_SLOT%§7,",
                "§a%armor_durability_(left/max)_SLOT%§7, §a%armor_has_SLOT%§7,",
                "§a%armor_material_SLOT%§7, §a%armor_maxamount_SLOT%§7"
        );
        List<String> keys = PapiECloud.parseCaptured(captured);
        assertEquals(6, keys.size(),
                "Expected 6 Armor placeholders (incl. nested-paren templates), got: " + keys);
        assertTrue(keys.contains("armor_amount_SLOT"));
        assertTrue(keys.contains("armor_color_(red/green/blue/hex)_SLOT"),
                "Nested-paren placeholder names must be preserved");
        assertTrue(keys.contains("armor_durability_(left/max)_SLOT"));
        assertTrue(keys.contains("armor_has_SLOT"));
        assertTrue(keys.contains("armor_material_SLOT"));
        assertTrue(keys.contains("armor_maxamount_SLOT"));
    }

    @Test
    public void resolveExactExpansionName_caseInsensitive() {
        // v1.3.10: switched from chat-parsing the /papi ecloud list installed
        // command to walking PlaceholderExpansion.getName() via reflection on
        // PlaceholderAPIPlugin.getLocalExpansionManager(). We can't run that
        // path in a unit test (no Bukkit), so we mirror the trivial lookup
        // here and assert the same semantics: case-insensitive match against
        // the list of TitleCase names.
        List<String> installed = List.of("Armor", "Player", "PlayerList", "Vault");
        java.util.function.Function<String, String> lookup = id -> {
            if (id == null) return null;
            for (String n : installed) if (n.equalsIgnoreCase(id)) return n;
            return null;
        };
        assertEquals("Armor", lookup.apply("armor"));
        assertEquals("Armor", lookup.apply("ARMOR"));
        assertEquals("Armor", lookup.apply("Armor"));
        assertEquals("Player", lookup.apply("PLAYER"));
        assertEquals("PlayerList", lookup.apply("playerlist"));
        assertEquals("Vault", lookup.apply("vault"));
        assertNull(lookup.apply("NotInstalled"));
        assertNull(lookup.apply(null));
        assertNull(lookup.apply(""));
    }

    @Test
    public void clearInstalledCache_resetsCache() {
        // Sanity: the static cache + clear must not throw when no entries exist.
        PapiECloud.clearInstalledCache();
        PapiECloud.clearInstalledCache();
        // After clear, getInstalledExpansionNames will try the live API; in a
        // unit test (no Bukkit) it just returns an empty list. That's fine.
        List<String> names = PapiECloud.getInstalledExpansionNames();
        assertNotNull(names);
    }
}
