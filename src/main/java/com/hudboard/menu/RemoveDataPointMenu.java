package com.hudboard.menu;

import com.hudboard.HudBoardPlugin;
import com.hudboard.lang.Lang;
import com.hudboard.panel.InfoPanelInstance;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Submenu opened by clicking "- Remove data point" in the main panel editor.
 * Lists every data point; click one to delete it.
 */
public class RemoveDataPointMenu {

    private final HudBoardPlugin plugin;
    private final InfoPanelInstance inst;
    private final Inventory inv;

    public RemoveDataPointMenu(HudBoardPlugin plugin, InfoPanelInstance inst) {
        this.plugin = plugin;
        this.inst = inst;
        this.inv = Bukkit.createInventory(null, 54, Component.text()
                .append(Component.text("Remove: ", NamedTextColor.RED))
                .append(Component.text(inst.name, NamedTextColor.WHITE))
                .build());
        redraw();
    }

    public void open(Player p) { p.openInventory(inv); MenuSfx.open(p, plugin); }
    public Inventory getInventory() { return inv; }

    public void redraw() {
        // Filler
        ItemStack filler = glass(Material.GRAY_STAINED_GLASS_PANE, " ", new ArrayList<>());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);
        // Data point slots
        int count = Math.min(27, inst.profile.dataPoints.size());
        for (int i = 0; i < count; i++) {
            var dp = inst.profile.dataPoints.get(i);
            ItemStack item = new ItemStack(Material.RED_DYE);
            ItemMeta m = item.getItemMeta();
            m.displayName(Component.text("§c" + dp.key).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            String preview = dp.text == null ? " " : dp.text;
            if (preview.length() > 50) preview = preview.substring(0, 47) + "...";
            lore.add(Component.text("§7Tile: §f(" + dp.tileX + ", " + dp.tileY + ")").decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("§7Text: §f" + preview).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("§7").decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("§c▶ Click to delete this data point").decoration(TextDecoration.ITALIC, false));
            m.lore(lore);
            item.setItemMeta(m);
            inv.setItem(9 + i, item);
        }
        // Bottom: back
        ItemStack back = glass(Material.ARROW, "§7← Back to editor", new ArrayList<>());
        inv.setItem(45, back);
        ItemStack close = glass(Material.STRUCTURE_VOID, "§7Close", new ArrayList<>());
        inv.setItem(53, close);
    }

    public boolean handleClick(InventoryClickEvent e) {
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54) return true;
        Player p = (Player) e.getWhoClicked();
        MenuSfx.click(p, plugin);
        if (slot == 45) {
            p.closeInventory();
            new PanelEditMenu(plugin, inst).open(p);
            return true;
        }
        if (slot == 53) { p.closeInventory(); return true; }
        if (slot >= 9 && slot < 36) {
            int idx = slot - 9;
            if (idx >= inst.profile.dataPoints.size()) return true;
            String key = inst.profile.dataPoints.get(idx).key;
            // v2.3.0: snapshot BEFORE removal so the user can Undo.
            inst.snapshotForUndo();
            inst.profile.dataPoints.remove(idx);
            plugin.getPanelManager().saveProfileToDisk(inst.profile);
            plugin.getPanelManager().invalidate(inst);
            p.sendMessage(plugin.getLang().get("gui.dp-deleted", "%key%", key));
            // Re-open the remove submenu with the updated list, or back to editor
            if (inst.profile.dataPoints.isEmpty()) {
                p.closeInventory();
                new PanelEditMenu(plugin, inst).open(p);
            } else {
                redraw();
                p.updateInventory();
            }
        }
        return true;
    }

    private ItemStack glass(Material mat, String name, List<Component> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        m.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        if (lore != null && !lore.isEmpty()) m.lore(lore);
        item.setItemMeta(m);
        return item;
    }
}
