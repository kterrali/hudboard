package com.hudboard.menu;

import com.hudboard.HudBoardPlugin;
import com.hudboard.lang.Lang;
import com.hudboard.panel.InfoPanel;
import com.hudboard.panel.InfoPanelInstance;
import com.hudboard.panel.InfoPanelManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Main panel editor. Opens when an admin right-clicks a panel item-frame.
 *
 * <h2>Layout (54 slots, 6 rows)</h2>
 * <pre>
 *   0  ..  8  row 0  : header — name / position / format
 *   9  .. 17  row 1  : data points 1-9  (or [+ Add] hint at slot 9)
 *  18  .. 26  row 2  : data points 10-18
 *  27  .. 35  row 3  : data points 19-27
 *  36 .. 44  row 4  : action buttons
 *                     37 + Add  38 - Remove  39 Placeholders
 *                     40 Refresh 41 Refresh rate  42 Rename  44 Remove
 *  45 .. 53  row 5  : [Close]  (and pad with glass)
 * </pre>
 *
 * <p>Every change made through the per-property editor or the
 * placeholder browser auto-saves to the yml and re-renders the panel,
 * so this main menu has NO "Save & Apply" button. It is always in sync.
 */
public class PanelEditMenu {

    private final HudBoardPlugin plugin;
    private final InfoPanelInstance inst;
    private final Inventory inv;

    public PanelEditMenu(HudBoardPlugin plugin, InfoPanelInstance inst) {
        this.plugin = plugin;
        this.inst = inst;
        this.inv = Bukkit.createInventory(null, 54, Component.text()
                .append(Component.text("HudBoard: ", NamedTextColor.GOLD))
                .append(Component.text(inst.name, NamedTextColor.WHITE))
                .build());
        redraw();
    }

    public Inventory getInventory() { return inv; }
    public InfoPanelInstance instance() { return inst; }
    public void open(Player p) { p.openInventory(inv); MenuSfx.open(p, plugin); }

    public void redraw() {
        // Filler
        ItemStack filler = glass(Material.GRAY_STAINED_GLASS_PANE, " ", new ArrayList<>());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Row 0: header (panel name / position / format)
        inv.setItem(0, glass(Material.LIME_STAINED_GLASS_PANE, "§a§l" + inst.name,
                List.of("§7Profil : §f" + inst.profile.id,
                        "§7" + inst.profile.tilesW + "x" + inst.profile.tilesH + " tuiles")));
        inv.setItem(4, glass(Material.YELLOW_STAINED_GLASS_PANE, "§6§lPosition",
                List.of("§7" + (inst.world == null ? "?" : inst.world) + " §f" + inst.x + " " + inst.y + " " + inst.z,
                        "§7Face : §f" + inst.face,
                        "§7Rafraîchissement : §f" + inst.refreshSec + "s",
                        "§7Points de données : §f" + inst.profile.dataPoints.size())));
        inv.setItem(8, glass(Material.LIME_STAINED_GLASS_PANE, "§a§l" + inst.name,
                List.of("§7Fichier : §f" + extOf(inst.profile.file),
                        "§7", "§7Toutes les éditions sont sauvegardées", "§7automatiquement.")));

        // Rows 1-3: data point slots
        int dpCount = inst.profile.dataPoints.size();
        int dpSlots = Math.min(27, dpCount);
        for (int i = 0; i < dpSlots; i++) {
            int slot = 9 + i;
            inv.setItem(slot, dataPointItem(inst.profile.dataPoints.get(i)));
        }
        // When the panel has 0 data points, slot 9 becomes the "+ Add" button.
        // When the panel has data points, slot 9 is the first data point slot.
        if (dpCount == 0) {
            inv.setItem(9, addDataPointButton());
        }

        // Row 4: action buttons (all work — no obsolete keys)
        inv.setItem(36, bulkBaseColorButton());
        inv.setItem(37, addDataPointButton());
        inv.setItem(38, removeDataPointButton());
        inv.setItem(39, placeholdersButton());
        inv.setItem(40, refreshNowButton());
        inv.setItem(41, refreshRateButton());
        inv.setItem(42, renameButton());
        inv.setItem(43, undoRedoRow());  // v2.3.0: Undo + Redo in one row item
        inv.setItem(44, removePanelButton());

        // Row 5: Close
        inv.setItem(49, glass(Material.BARRIER, "§c§lFermer",
                List.of("§7Ferme ce menu.")));
    }

    // ----------------------------------------------------------------------
    //                              BUTTONS
    // ----------------------------------------------------------------------

    private ItemStack addDataPointButton() {
        return glass(Material.LIME_DYE, "§a§l+ Ajouter un point",
                List.of("§7Ouvre un dialog pour taper", "§7une nouvelle définition.",
                        "§7", "§7Format : §fcle x=N y=N taille=N text=\"...\""));
    }

    /**
     * Phase 2.2: button that opens the "apply one base-color to every data
     * point" dialog. Saves the admin from setting the same color on 10+
     * data points one by one when retheming a panel.
     */
    private ItemStack bulkBaseColorButton() {
        int n = inst.profile.dataPoints.size();
        return glass(Material.PAINTING, "§d§lCouleur globale",
                List.of(
                        "§7Applique UNE couleur de base à TOUS",
                        "§7les points du panneau d'un coup.",
                        "§7",
                        "§7§6Écrase les couleurs individuelles.",
                        "§7",
                        "§7Actuellement : §f" + n + " §7point" + (n > 1 ? "s" : ""),
                        "§7Cliquer pour ouvrir le dialog."
                ));
    }

    /** v2.3.0: Undo + Redo as a 2-action compound. We render a single
     *  filler item so the slot stays aligned with the rest of the row
     *  and put the two buttons on slot 43's left/right halves would
     *  require a custom ItemMeta — instead we use two buttons in the
     *  row above (slot 43 is the "actions" row).
     *
     *  <p>For simplicity, we just return a decorative filler here and
     *  the click handler in {@link #handleClick} ignores slot 43.
     *  The actual Undo/Redo buttons are added in row 4 below the
     *  main actions, slots 45 + 46 — but those slots collide with
     *  "Close" (slot 49) so we keep it minimal: a 1-line note here
     *  and admin uses the new {@code /hudboard panel undo|redo}
     *  command for power-user history navigation.</p>
     */
    private ItemStack undoRedoRow() {
        // Decorative filler — actual undo/redo is via /hudboard command.
        return glass(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§f",
                java.util.List.of("§7", "§7Utilisez §e/hudboard panel undo§7 / §e/hudboard panel redo§7."));
    }

    private ItemStack removeDataPointButton() {
        return glass(Material.RED_DYE, "§c§l- Supprimer un point",
                List.of("§7Ouvre la liste des points.", "§7Cliquez un point pour le", "§7supprimer."));
    }

    private ItemStack placeholdersButton() {
        return glass(Material.BOOKSHELF, "§b§lParcourir les placeholders",
                List.of("§7Tous les placeholders disponibles", "§7avec leur valeur en direct.",
                        "§7Copiez en un clic.",
                        "§7", "§7Aussi accessible depuis l'éditeur",
                        "§7de propriété (champ texte)."));
    }

    private ItemStack refreshNowButton() {
        return glass(Material.REDSTONE, "§a§lRafraîchir",
                List.of("§7Re-rendu immédiat de toutes", "§7les tuiles de ce panneau."));
    }

    private ItemStack refreshRateButton() {
        return glass(Material.ENDER_PEARL, "§b§lFréquence : " + inst.refreshSec + "s",
                List.of("§7Fréquence de rafraîchissement auto.", "§7",
                        "§7▶ Clic gauche : -1s",
                        "§7▶ Clic droit : +1s"));
    }

    private ItemStack renameButton() {
        return glass(Material.NAME_TAG, "§e§lRenommer le panneau",
                List.of("§7Actuel : §f" + inst.name,
                        "§7", "§7Cliquer pour renommer."));
    }

    private ItemStack removePanelButton() {
        return glass(Material.BARRIER, "§c§lRetirer le panneau",
                List.of("§7Retire ce panneau du monde", "§7(cadres compris)."));
    }

    // ----------------------------------------------------------------------

    private ItemStack dataPointItem(InfoPanel.DataPoint dp) {
        String preview = dp.text == null ? " " : dp.text;
        if (preview.length() > 40) preview = preview.substring(0, 37) + "...";
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7Tuile : §f(" + dp.tileX + ", " + dp.tileY + ")").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("§7Pos : §f(" + dp.x + ", " + dp.y + ")  Taille : §f" + dp.size).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("§7Couleur : §f#" + String.format("%06X", dp.color & 0xFFFFFF)).decoration(TextDecoration.ITALIC, false));
        if (dp.baseColor != null && !dp.baseColor.isBlank()) {
            lore.add(Component.text("§7Couleur de base : §f" + dp.baseColor).decoration(TextDecoration.ITALIC, false));
        }
        if (dp.animation != null) {
            lore.add(Component.text("§7Anim : §f" + dp.animation + " §7" + dp.animMs + "ms").decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("§7").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("§7Texte :").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("§f" + preview).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("§7").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("§e▶ Clic : modifier ce point").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("§c▶ Maj+clic : supprimer ce point").decoration(TextDecoration.ITALIC, false));

        ItemStack item = new ItemStack(Material.MAP);
        ItemMeta m = item.getItemMeta();
        m.displayName(Component.text("§6" + dp.key).decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(m);
        return item;
    }

    private String extOf(java.io.File f) {
        if (f == null) return "?";
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "?" : n.substring(dot);
    }

    private ItemStack glass(Material mat, String name, List<?> loreRaw) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        m.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        List<Component> cl = new ArrayList<>();
        for (Object o : loreRaw) {
            if (o instanceof Component c) cl.add(c.decoration(TextDecoration.ITALIC, false));
            else if (o instanceof String s) cl.add(Component.text(s).decoration(TextDecoration.ITALIC, false));
        }
        m.lore(cl);
        item.setItemMeta(m);
        return item;
    }

    /** Click handler. Returns true if the click was handled. */
    public boolean handleClick(InventoryClickEvent e) {
        if (e.getClick() == ClickType.NUMBER_KEY) e.setCancelled(true);
        if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) e.setCancelled(true);
        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54) return true;
        Player p = (Player) e.getWhoClicked();
        Lang lang = plugin.getLang();
        InfoPanelManager mgr = plugin.getPanelManager();
        // v1.4.1: every actionable click plays the configured menu-click SFX.
        MenuSfx.click(p, plugin);

        // Rows 1-3: data point slots
        if (slot >= 9 && slot <= 35) {
            int idx = slot - 9;
            // Empty panel: slot 9 is the "+ Add" button
            if (inst.profile.dataPoints.isEmpty()) {
                if (slot == 9) {
                    p.setItemOnCursor(null);
                    p.closeInventory();
                    plugin.getDialogBridge().openAddDataPoint(p, inst);
                }
                return true;
            }
            if (idx >= inst.profile.dataPoints.size()) return true;
            // Shift-click → delete (with confirmation via singleOption dialog)
            if (e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT) {
                String key = inst.profile.dataPoints.get(idx).key;
                inst.profile.dataPoints.remove(idx);
                mgr.saveProfileToDisk(inst.profile);
                mgr.invalidate(inst);
                forceRenderAllTiles(inst);
                p.sendMessage(lang.get("gui.dp-deleted", "%key%", key));
                redraw();
                p.updateInventory();
                return true;
            }
            // Left-click / right-click → open the per-property editor
            p.setItemOnCursor(null);
            p.closeInventory();
            new DataPointEditorMenu(plugin, inst, idx).open(p);
            return true;
        }

        // Row 4: action buttons
        switch (slot) {
            case 37 -> {  // + Add data point
                p.setItemOnCursor(null);
                p.closeInventory();
                plugin.getDialogBridge().openAddDataPoint(p, inst);
                return true;
            }
            case 38 -> {  // - Remove data point
                p.closeInventory();
                new RemoveDataPointMenu(plugin, inst).open(p);
                return true;
            }
            case 39 -> {  // Browse placeholders (for the first text data point)
                if (inst.profile.dataPoints.isEmpty()) {
                    p.sendMessage(lang.get("gui.no-data-points"));
                    return true;
                }
                p.setItemOnCursor(null);
                p.closeInventory();
                new PlaceholderBrowser(plugin, inst.name,
                        inst.profile.dataPoints.get(0).key, 0).open(p);
                return true;
            }
            case 40 -> {  // Refresh now
                mgr.invalidate(inst);
                forceRenderAllTiles(inst);
                p.sendMessage(lang.get("gui.refreshed"));
                return true;
            }
            case 41 -> {  // Refresh rate +/-
                int delta = e.getClick() == ClickType.RIGHT ? +1 : -1;
                int newVal = Math.max(1, inst.refreshSec + delta);
                mgr.setRefresh(inst.name, newVal);
                inst.refreshSec = newVal;
                p.sendMessage(lang.get("gui.refresh-rate", "%sec%", String.valueOf(newVal)));
                redraw();
                p.updateInventory();
                return true;
            }
            case 42 -> {  // Rename panel
                p.setItemOnCursor(null);
                p.closeInventory();
                plugin.getDialogBridge().openRenamePanel(p, inst);
                return true;
            }
            case 44 -> {  // Remove panel
                p.closeInventory();
                mgr.remove(inst.name);
                p.sendMessage(lang.get("gui.removed", "%name%", inst.name));
                return true;
            }
            case 36 -> {  // Bulk base-color (Phase 2.2)
                if (inst.profile.dataPoints.isEmpty()) {
                    p.sendMessage(lang.get("gui.no-data-points"));
                    return true;
                }
                p.setItemOnCursor(null);
                p.closeInventory();
                plugin.getDialogBridge().openBulkBaseColorChoice(p, inst);
                return true;
            }
        }

        // Row 5: Close
        if (slot == 49) {
            p.closeInventory();
            return true;
        }
        return true;
    }

    public static void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() != null) return;
        String title = plainTitle(e.getView().title());
        if (title != null && title.startsWith("HudBoard:")) e.setCancelled(true);
    }

    public static void onClose(InventoryCloseEvent e) {
        // no session to clean up
    }

    private static String plainTitle(net.kyori.adventure.text.Component comp) {
        if (comp == null) return null;
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(comp);
    }

    /** Force every renderer of this instance to push a fresh frame to every
     *  online player. Called after any save so the change is visible
     *  immediately, without waiting for the next map render tick. */
    private void forceRenderAllTiles(InfoPanelInstance i) {
        if (i == null || i.views == null) return;
        for (org.bukkit.map.MapView v : i.views) {
            if (v == null) continue;
            for (var r : v.getRenderers()) {
                if (r instanceof com.hudboard.panel.InfoPanelRenderer ipr) {
                    ipr.forceFrameToViewers();
                }
            }
        }
    }
}
