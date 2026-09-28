package com.hudboard.menu;

import com.hudboard.HudBoardPlugin;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Tiny shared SFX helper for the inventory-GUI menus. We centralise it here
 * so the click handler doesn't repeat the {@code try/catch} dance four
 * times per file, and so a single missing config key degrades to "silent"
 * rather than crashing the click handler.
 *
 * <p>All sounds honour the admin's {@code config.yml}. Empty / whitespace
 * values mean "no sound for this slot" (let the admin disable them
 * individually).</p>
 *
 * <p>This class was added in v1.4.1 — Phase 3 polissage — because the user
 * asked for menu clicks to "feel" responsive. We previously only had
 * place / remove sounds (config-managed in {@code sound-on-place} and
 * {@code sound-on-remove}).</p>
 */
public final class MenuSfx {

    private MenuSfx() {}

    /** Play the configured click sound. No-op if config is empty. */
    public static void click(Player p, HudBoardPlugin plugin) {
        if (p == null || plugin == null) return;
        String name = plugin.getConfigManager().soundMenuClick();
        play(p, name, 0.6f, 1.4f);
    }

    /** Play the configured "deny" / invalid action sound. */
    public static void deny(Player p, HudBoardPlugin plugin) {
        if (p == null || plugin == null) return;
        String name = plugin.getConfigManager().soundMenuDeny();
        // Default noise: ENCHANT_THORNS_HIT isn't configurable per default;
        // the admin can pick anything. We try the explicit name first.
        if (name == null || name.isBlank()) {
            play(p, "ENTITY_VILLAGER_NO", 0.6f, 1.2f);
        } else {
            play(p, name, 0.6f, 1.2f);
        }
    }

    /** Play the configured menu-open sound. No-op if config is empty. */
    public static void open(Player p, HudBoardPlugin plugin) {
        if (p == null || plugin == null) return;
        String name = plugin.getConfigManager().soundMenuOpen();
        play(p, name, 0.5f, 1.0f);
    }

    /** Play any sound by string name. Empty / blank = no-op. Swallows the
     *  IllegalArgumentException thrown by {@code Sound.valueOf} when the
     *  configured name doesn't exist on this MC version. */
    public static void play(Player p, String name, float volume, float pitch) {
        if (p == null) return;
        if (name == null || name.isBlank()) return;
        try {
            p.playSound(p.getLocation(), Sound.valueOf(name), volume, pitch);
        } catch (IllegalArgumentException ex) {
            // Misconfigured / removed in this MC version — silently skip.
        } catch (Throwable t) {
            // Don't let SFX errors ever bubble up into a click handler.
        }
    }
}
