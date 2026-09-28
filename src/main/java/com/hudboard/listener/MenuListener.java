package com.hudboard.listener;

import com.hudboard.HudBoardPlugin;
import com.hudboard.menu.DataPointEditorMenu;
import com.hudboard.menu.PanelEditMenu;
import com.hudboard.menu.RemoveDataPointMenu;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Routes clicks inside any HudBoard GUI (panel editor, per-property editor,
 * remove submenu, placeholder browser) to the right handler.
 *
 * <p>All text input is now done through Paper Dialog API (see
 * {@link com.hudboard.menu.DialogInputBridge}) — no anvil, no sign.
 */
public class MenuListener implements Listener {

    private final HudBoardPlugin plugin;
    public MenuListener(HudBoardPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() != null) return;
        String title = plainTitle(e.getView().title());
        if (title == null) return;

        if (title.startsWith("HudBoard:")) {
            // Main panel editor — "HudBoard: <panel-name>"
            String name = title.substring("HudBoard:".length()).trim();
            var inst = plugin.getPanelManager().get(name, true);
            if (inst == null) { e.setCancelled(true); return; }
            new PanelEditMenu(plugin, inst).handleClick(e);
        } else if (title.startsWith("Remove: ")) {
            // Remove-data-point submenu — "Remove: <panel-name>"
            String name = title.substring("Remove: ".length()).trim();
            var inst = plugin.getPanelManager().get(name, true);
            if (inst == null) { e.setCancelled(true); return; }
            new RemoveDataPointMenu(plugin, inst).handleClick(e);
        } else if (title.startsWith("Edit ")) {
            // Per-property editor — title is "Edit <panel-name>:<data-point-key>"
            // The panel name is required so we route to the right panel. If two
            // panels share a data-point key (e.g. both have a "title"), the old
            // "Edit: <key>" format made us always edit the first one.
            String rest = title.substring("Edit ".length()).trim();
            int colon = rest.indexOf(':');
            if (colon <= 0) { e.setCancelled(true); return; }
            String panelName = rest.substring(0, colon);
            String key = rest.substring(colon + 1);
            var inst = plugin.getPanelManager().get(panelName, true);
            if (inst == null) { e.setCancelled(true); return; }
            int idx = -1;
            for (int i = 0; i < inst.profile.dataPoints.size(); i++) {
                if (inst.profile.dataPoints.get(i).key.equals(key)) { idx = i; break; }
            }
            if (idx < 0) { e.setCancelled(true); return; }
            new DataPointEditorMenu(plugin, inst, idx).handleClick(e);
        } else if (title.startsWith("Placeholders for: ")) {
            // Placeholder browser — singleton dispatched by player UUID
            com.hudboard.menu.PlaceholderBrowser.dispatch(e.getWhoClicked().getUniqueId(), e);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() != null) return;
        String title = plainTitle(e.getView().title());
        if (title != null && (title.startsWith("HudBoard:") || title.startsWith("Edit ")
                || title.startsWith("Remove: ") || title.startsWith("Placeholders for: "))) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        String title = plainTitle(e.getView().title());
        if (title != null && title.startsWith("Placeholders for: ")) {
            com.hudboard.menu.PlaceholderBrowser.close(e.getPlayer().getUniqueId());
        }
    }

    private static String plainTitle(net.kyori.adventure.text.Component comp) {
        if (comp == null) return null;
        return PlainTextComponentSerializer.plainText().serialize(comp);
    }
}
