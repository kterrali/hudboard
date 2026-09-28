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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-data-point editor. Opens when an admin clicks a data point slot in
 * the main panel editor. Shows one button per editable property; clicking
 * a property opens the native Paper Dialog with the current value pre-filled.
 *
 * <h2>Layout (45 slots, 5 rows)</h2>
 * <pre>
 *   0  ..  8  row 0  : header — key / current values
 *   9  .. 17  row 1  : [tile-x] [tile-y] [x] [y]
 *  18  .. 26  row 2  : [size] [base-color] [animation] [text] [?]
 *  27  .. 35  row 3  : (empty — was anim-ms/anim-color, now in the animation dialog)
 *  36  .. 44  row 4  : [← Back] [Delete] [Close]
 * </pre>
 *
 * <p>All edits auto-save and re-render immediately. No Save button.
 */
public class DataPointEditorMenu {

    private final HudBoardPlugin plugin;
    private final InfoPanelInstance inst;
    private final int dataPointIndex;
    private final Inventory inv;

    public DataPointEditorMenu(HudBoardPlugin plugin, InfoPanelInstance inst, int dataPointIndex) {
        this.plugin = plugin;
        this.inst = inst;
        this.dataPointIndex = dataPointIndex;
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dataPointIndex);
        // Title includes the panel name so the listener can route clicks to the
        // right panel. Without it, two panels with a "title" data point would
        // both route to whichever one's editor was opened first.
        this.inv = Bukkit.createInventory(null, 45, Component.text()
                .append(Component.text("Edit ", NamedTextColor.GOLD))
                .append(Component.text(inst.name + ":" + dp.key, NamedTextColor.WHITE))
                .build());
        redraw();
    }

    public void open(Player p) { p.openInventory(inv); MenuSfx.open(p, plugin); }
    public Inventory getInventory() { return inv; }
    public int dataPointIndex() { return dataPointIndex; }
    public InfoPanelInstance instance() { return inst; }

    private InfoPanel.DataPoint dp() {
        return inst.profile.dataPoints.get(dataPointIndex);
    }

    public void redraw() {
        ItemStack filler = glass(Material.GRAY_STAINED_GLASS_PANE, " ", new ArrayList<>());
        for (int i = 0; i < 45; i++) inv.setItem(i, filler);

        InfoPanel.DataPoint dp = dp();

        // Row 0: header
        inv.setItem(0, glass(Material.LIME_STAINED_GLASS_PANE, "§a§l" + dp.key,
                List.of("§7Cliquez un champ pour le modifier", "§7dans le dialog Minecraft.")));
        inv.setItem(4, glass(Material.YELLOW_STAINED_GLASS_PANE, "§6§lValeurs actuelles",
                List.of("§7Tuile : §f(" + dp.tileX + ", " + dp.tileY + ")",
                        "§7Pos : §f(" + dp.x + ", " + dp.y + ")  Taille : §f" + dp.size,
                        "§7Couleur : §f#" + String.format("%06X", dp.color & 0xFFFFFF),
                        dp.baseColor != null && !dp.baseColor.isBlank()
                                ? "§7Couleur de base : §f" + dp.baseColor
                                : "§7Couleur de base : §7(couleur int d'origine)",
                        "§7(Texte long déborde sur la tuile suivante)")));
        inv.setItem(8, glass(Material.LIME_STAINED_GLASS_PANE, "§a§l" + dp.key,
                List.of("§7Cliquez un champ pour le modifier", "§7dans le dialog Minecraft.")));

        // Row 1: tile-x, tile-y, x, y
        inv.setItem(9,  propertyButton("§e§ltile-x",  "§7" + dp.tileX,        "tile-x"));
        inv.setItem(10, propertyButton("§e§ltile-y",  "§7" + dp.tileY,        "tile-y"));
        inv.setItem(12, propertyButton("§e§lx",       "§7" + dp.x,            "x"));
        inv.setItem(14, propertyButton("§e§ly",       "§7" + dp.y,            "y"));

        // Row 2: size, base-color, animation, text, placeholders browser
        // Note: animation params (type + speed + color) are all edited from
        // the single animation button, which opens a 3-input dialog. No
        // separate anim-ms / anim-color slots — the editor stays clean.
        inv.setItem(18, propertyButton("§e§lsize",        "§7" + dp.size, "size"));
        inv.setItem(20, baseColorButton(dp));
        inv.setItem(22, animationButton(dp));
        inv.setItem(24, propertyButton("§e§ltext",        "§7" + truncate(dp.text, 30), "text"));
        inv.setItem(26, placeholdersButton());

        // Row 3 left intentionally empty (was anim-ms/anim-color slots,
        // now folded into the animation dialog).

        // Row 4: back / delete / close
        inv.setItem(36, backButton());
        inv.setItem(40, deleteButton());
        inv.setItem(44, closeButton());
    }

    // ----------------------------------------------------------------------
    //                              BUTTONS
    // ----------------------------------------------------------------------

    private ItemStack propertyButton(String name, String value, String propertyKey) {
        return glass(Material.PAPER, name, List.of(value, "§7", "§e▶ Cliquer pour modifier"));
    }

    /** Animation button — opens a native Dialog with 3 inputs (type, speed,
     *  color) for the full animation config. The new animations are positional
     *  (bob / scroll / typewriter) rather than color-based. */
    private ItemStack animationButton(InfoPanel.DataPoint dp) {
        String current = dp.animation == null ? "none" : dp.animation.toLowerCase();
        int rgb = dp.animColor & 0xFFFFFF;
        String hex = String.format("#%06X", rgb);
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta m = item.getItemMeta();
        m.displayName(Component.text("§e§lanimation : §f" + current)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("§7§fbob §7— oscillation verticale").decoration(TextDecoration.ITALIC, false),
                Component.text("§7§fscroll §7— bandeau défilant").decoration(TextDecoration.ITALIC, false),
                Component.text("§7§ftypewriter §7— lettre par lettre").decoration(TextDecoration.ITALIC, false),
                Component.text("§7").decoration(TextDecoration.ITALIC, false),
                Component.text("§7Vitesse : §f" + dp.animMs + " ms §7· Couleur : §f" + hex).decoration(TextDecoration.ITALIC, false),
                Component.text("§7").decoration(TextDecoration.ITALIC, false),
                Component.text("§e▶ Cliquer pour modifier type, vitesse, couleur").decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(m);
        return item;
    }

    /** Base text color button — opens a native Dialog asking for any
     *  MiniMessage color tag (named, hex, or gradient). The chosen tag is
     *  prepended to the text automatically. */
    private ItemStack baseColorButton(InfoPanel.DataPoint dp) {
        String current = dp.baseColor == null || dp.baseColor.isBlank() ? "(par défaut)" : dp.baseColor;
        ItemStack item = new ItemStack(Material.RED_DYE);
        ItemMeta m = item.getItemMeta();
        m.displayName(Component.text("§c§lcouleur : §f" + current)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("§7Appliquée automatiquement au texte.").decoration(TextDecoration.ITALIC, false),
                Component.text("§7Vous pouvez garder §f<white>...</white> §7à l'intérieur").decoration(TextDecoration.ITALIC, false),
                Component.text("§7pour surligner un mot.").decoration(TextDecoration.ITALIC, false),
                Component.text("§7").decoration(TextDecoration.ITALIC, false),
                Component.text("§7Tags : §f<red> <blue> <#FF8800> <gradient:red:blue>").decoration(TextDecoration.ITALIC, false),
                Component.text("§7").decoration(TextDecoration.ITALIC, false),
                Component.text("§e▶ Cliquer pour modifier").decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(m);
        return item;
    }

    /** Animation color button — opens a native Dialog asking for any
     *  MiniMessage color tag (named, hex, or gradient). The chosen color
     *  is the secondary color used by pulse / breathe / blink animations. */
    private ItemStack animColorButton(InfoPanel.DataPoint dp) {
        int rgb = dp.animColor & 0xFFFFFF;
        String hex = String.format("#%06X", rgb);
        String name = colorNameFromRgb(rgb);
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta m = item.getItemMeta();
        m.displayName(Component.text("§d§lcouleur anim : §f" + hex + " §7(" + name + ")")
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("§7Couleur secondaire pour les animations.").decoration(TextDecoration.ITALIC, false),
                Component.text("§7Cliquer pour choisir une couleur").decoration(TextDecoration.ITALIC, false),
                Component.text("§7(nom, hex, ou gradient).").decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(m);
        return item;
    }

    private ItemStack placeholdersButton() {
        return glass(Material.BOOKSHELF, "§b§l? Parcourir les placeholders",
                List.of("§7Tous les placeholders disponibles", "§7avec leur valeur en direct.",
                        "§7Copiez en un clic."));
    }

    private ItemStack backButton() {
        return glass(Material.ARROW, "§7← Retour à l'éditeur",
                List.of("§7Retour au menu principal du panneau."));
    }

    private ItemStack deleteButton() {
        return glass(Material.RED_DYE, "§c§lSupprimer ce point",
                List.of("§7Retire ce point du panneau", "§7et sauvegarde."));
    }

    private ItemStack closeButton() {
        return glass(Material.BARRIER, "§7Fermer",
                List.of("§7Ferme ce menu."));
    }

    private static String colorNameFromRgb(int rgb) {
        if (rgb == 0xFFFFFF) return "blanc";
        if (rgb == 0xFF5555) return "rouge";
        if (rgb == 0x5555FF) return "bleu";
        if (rgb == 0x55FF55) return "vert";
        if (rgb == 0xFFAA00) return "or";
        if (rgb == 0xFFFF55) return "jaune";
        if (rgb == 0x55FFFF) return "cyan";
        if (rgb == 0xAAAAAA) return "gris";
        if (rgb == 0x000000) return "noir";
        return "perso";
    }

    private String truncate(String s, int max) {
        if (s == null) return "(empty)";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
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

    public boolean handleClick(InventoryClickEvent e) {
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 45) return true;
        Player p = (Player) e.getWhoClicked();
        MenuSfx.click(p, plugin);
        Lang lang = plugin.getLang();
        InfoPanelManager mgr = plugin.getPanelManager();

        // Row 4: navigation
        if (slot == 36) {  // Back
            p.closeInventory();
            new PanelEditMenu(plugin, inst).open(p);
            return true;
        }
        if (slot == 40) {  // Delete
            String key = dp().key;
            inst.profile.dataPoints.remove(dataPointIndex);
            mgr.saveProfileToDisk(inst.profile);
            mgr.invalidate(inst);
            forceRenderAllTiles(inst);
            p.sendMessage(lang.get("gui.dp-deleted", "%key%", key));
            p.closeInventory();
            new PanelEditMenu(plugin, inst).open(p);
            return true;
        }
        if (slot == 44) {  // Close
            p.closeInventory();
            return true;
        }

        // Row 2 special: base color, animation picker, placeholders browser
        if (slot == 20) {  // base color
            p.setItemOnCursor(null);
            p.closeInventory();
            plugin.getDialogBridge().openBaseColorChoice(p, inst, dataPointIndex);
            return true;
        }
        if (slot == 22) {  // animation: open the full 3-input editor (type+speed+color)
            p.setItemOnCursor(null);
            p.closeInventory();
            plugin.getDialogBridge().openAnimationEditor(p, inst, dataPointIndex);
            return true;
        }
        if (slot == 26) {  // placeholders browser
            p.setItemOnCursor(null);
            p.closeInventory();
            new PlaceholderBrowser(plugin, inst.name, dp().key, dataPointIndex).open(p);
            return true;
        }

        // Property buttons (open the right Dialog)
        String prop = slotToProperty(slot);
        if (prop == null) return true;
        p.setItemOnCursor(null);
        p.closeInventory();
        if (prop.equals("text")) {
            plugin.getDialogBridge().openMultilineText(p, inst, dataPointIndex);
        } else {
            plugin.getDialogBridge().openTextProperty(p, inst, dataPointIndex, prop);
        }
        return true;
    }

    private String slotToProperty(int slot) {
        return switch (slot) {
            case 9  -> "tile-x";
            case 10 -> "tile-y";
            case 12 -> "x";
            case 14 -> "y";
            case 18 -> "size";
            case 24 -> "text";
            case 27 -> "anim-ms";
            default -> null;
        };
    }

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
