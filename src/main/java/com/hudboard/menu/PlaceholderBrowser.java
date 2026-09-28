package com.hudboard.menu;

import com.hudboard.HudBoardPlugin;
import com.hudboard.data.DataManager;
import com.hudboard.data.PapiECloud;
import com.hudboard.panel.InfoPanelInstance;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placeholder browser. Opens a chest GUI (54 slots).
 *
 * <h2>Design (v1.3.4)</h2>
 * <ul>
 *   <li><b>Level 1 (SOURCES)</b>: one paper per source — 9 built-in
 *       categories + every PAPI expansion.</li>
 *   <li><b>Level 2 (PLACEHOLDERS)</b>: one paper per placeholder of the
 *       chosen source, with the live resolved value as lore.</li>
 *   <li><b>Empty source</b>: when an expansion uses the legacy hook
 *       system (e.g. armor, player, …) and returns no placeholders, the
 *       browser shows a "type manually" action that opens a chat prompt
 *       so the user can still insert any placeholder by name.</li>
 * </ul>
 */
public class PlaceholderBrowser {

    private static final java.util.Map<UUID, PlaceholderBrowser> OPEN = new java.util.HashMap<>();
    /** Set while a placeholder dialog is open, so we suppress the inventory
     *  click listener for that player (no double handler). v1.3.9 used to
     *  guard against chat-conversation re-entry; it's now used as a
     *  defensive belt for the dialog flow too. */
    private static final java.util.Set<UUID> AWAITING_INPUT = ConcurrentHashMap.newKeySet();

    public static boolean dispatch(UUID player, InventoryClickEvent e) {
        if (AWAITING_INPUT.contains(player)) return false; // ignore while in chat prompt
        PlaceholderBrowser pb = OPEN.get(player);
        if (pb == null) return false;
        return pb.handleClick(e);
    }

    public static void close(UUID player) { OPEN.remove(player); }

    /** Built-in categories — always shown first. */
    private static final String[] BUILTIN_SOURCES = {
            "server", "player", "world", "economy", "top", "target",
            "runtime", "hudboard-api", "misc"
    };

    private static final Material MAT_BUILTIN_PAPER = Material.PAPER;
    private static final Material MAT_PAPI_PAPER = Material.MAP;
    private static final Material MAT_PLACEHOLDER = Material.PAPER;
    private static final Material MAT_SELECTED = Material.LIME_WOOL;

    private final HudBoardPlugin plugin;
    private final String panelName;
    private final String dataPointKey;
    private final int dpIndex;
    private final Inventory inv;
    private String selectedPlaceholder = null;
    private ViewMode mode = ViewMode.SOURCES;
    private String currentSource = null;
    private boolean currentSourceIsPapi = false;
    /** Placeholder keys fetched from PAPI eCloud for the current source.
     *  {@code null} = never fetched (still showing "Type manually" / "Fetch from eCloud").
     *  Empty list = fetched, eCloud returned nothing (expansion not in eCloud).
     *  Non-empty = those keys are shown as clickable placeholder papers. */
    private java.util.List<String> eCloudFetchedKeys = null;
    /** v2.0.1: per-source manual keys now live in {@link ManualKeysStorage}
     *  (persisted to {@code plugins/HudBoard/manual-keys.yml}). We keep a
     *  snapshot in {@link #manualKeysCache} for the lifetime of the
     *  browser instance so the merge logic doesn't hit disk on every
     *  redraw. The cache is refreshed every time the browser re-opens
     *  (so server-side mutations from {@code /hudboard manual ...}
     *  become visible without a plugin reload). */
    private java.util.Map<String, java.util.List<String>> manualKeysCache = new java.util.HashMap<>();
    /** Internal accessor so {@link #placeholdersForCurrentSource()} and
     *  {@link #insertPlaceholderFromDialog(Player, String)} speak to the
     *  cache through a single chokepoint. */
    private java.util.List<String> manualKeysForCurrentSource() {
        return manualKeysCache.getOrDefault(currentSource, java.util.Collections.emptyList());
    }
    /** Current page in the placeholder list (v1.3.14 pagination).
     *  Some expansions (Statistic, Server, …) ship 50+ templates; the
     *  browser's main grid only holds 36 slots (9..44), so anything beyond
     *  gets paginated. Reset to 0 on source change / fetch / refresh. */
    private int currentPage = 0;
    /** How many placeholder papers fit on one page (= rows 9..44 inclusive). */
    private static final int PAPERS_PER_PAGE = 36;

    /**
     * Pure pagination math, extracted for unit testing (no Bukkit). Given
     * the total number of items and a current page index (0-based), returns
     * the half-open [from, to) index range for the slice that should be
     * rendered, plus the total page count. Always clamps the page index to
     * a valid range — callers don't need to bounds-check.
     *
     * <p>Both numbers are inclusive of the page's first index and
     * exclusive of the next page's first index, matching {@link List#subList}
     * semantics.</p>
     *
     * @param totalItems total placeholder keys for the current source (>= 0)
     * @param page       0-based page index (may be out of range; clamped)
     * @return {@code [totalPages, from, to]} packed in a 3-element array
     */
    public static int[] pageSlice(int totalItems, int page) {
        if (totalItems < 0) totalItems = 0;
        int totalPages = Math.max(1, (totalItems + PAPERS_PER_PAGE - 1) / PAPERS_PER_PAGE);
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;
        int from = page * PAPERS_PER_PAGE;
        int to = Math.min(totalItems, from + PAPERS_PER_PAGE);
        return new int[]{totalPages, from, to};
    }

    public enum ViewMode { SOURCES, PLACEHOLDERS }

    public PlaceholderBrowser(HudBoardPlugin plugin, String panelName, String dataPointKey, int dpIndex) {
        this.plugin = plugin;
        this.panelName = panelName;
        this.dataPointKey = dataPointKey;
        this.dpIndex = dpIndex;
        // v2.0.1: seed the manual-keys cache from persistent storage so
        // entries survive server restart.
        refreshManualKeysCache();
        this.inv = Bukkit.createInventory(null, 54, Component.text()
                .append(Component.text("Placeholders for: ", NamedTextColor.GOLD))
                .append(Component.text(dataPointKey, NamedTextColor.WHITE))
                .build());
        redraw();
    }

    /** Pull the latest persistent manual-keys snapshot into the local cache. */
    private void refreshManualKeysCache() {
        com.hudboard.data.ManualKeysStorage storage = plugin.getManualKeys();
        manualKeysCache = new java.util.HashMap<>();
        if (storage != null) manualKeysCache.putAll(storage.all());
    }

    public void open(Player p) {
        OPEN.put(p.getUniqueId(), this);
        p.openInventory(inv);
        MenuSfx.open(p, plugin);
    }

    public Inventory getInventory() { return inv; }

    private String resolve(String key) {
        DataManager dm = plugin.getDataManager();
        Player sample = Bukkit.getOnlinePlayers().stream()
                .filter(pl -> pl.getUniqueId().toString().equals(getOwnerUuid()))
                .findFirst()
                .orElse(null);
        if (sample == null) sample = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        try {
            if (sample != null) return dm.resolve("%" + key + "%", sample, java.util.Collections.emptyMap());
            return dm.resolve("%" + key + "%", null, java.util.Collections.emptyMap());
        } catch (Throwable t) {
            return "§c(erreur)";
        }
    }

    private String getOwnerUuid() {
        for (var e : OPEN.entrySet()) if (e.getValue() == this) return e.getKey().toString();
        return null;
    }

    private void redraw() {
        ItemStack filler = glass(Material.GRAY_STAINED_GLASS_PANE, " ", new ArrayList<>());
        for (int i = 0; i < 45; i++) inv.setItem(i, filler);
        String header;
        if (mode == ViewMode.SOURCES) header = "Choisir une source";
        else {
            int count = placeholderCountForCurrentSource();
            int totalPages = Math.max(1, (count + PAPERS_PER_PAGE - 1) / PAPERS_PER_PAGE);
            header = "Placeholders dans " + currentSource + (currentSourceIsPapi ? " (papi)" : "")
                    + "  ·  page " + (currentPage + 1) + "/" + totalPages
                    + "  ·  " + count + " au total";
        }
        inv.setItem(0, glass(Material.LIME_STAINED_GLASS_PANE, "§a§l" + dataPointKey,
                List.of("§7Édition : §f" + dataPointKey, "§7Vue : §f" + header)));
        inv.setItem(4, glass(Material.YELLOW_STAINED_GLASS_PANE, "§6§l" + header,
                List.of("§7Sélection : §f" + (selectedPlaceholder == null ? "(aucun)" : "%" + selectedPlaceholder + "%"))));
        inv.setItem(8, glass(Material.LIME_STAINED_GLASS_PANE, "§a§l" + dataPointKey,
                List.of("§7Édition : §f" + dataPointKey, "§7Vue : §f" + header)));
        if (mode == ViewMode.SOURCES) drawSources();
        else drawPlaceholders();
        inv.setItem(45, glass(Material.LIME_WOOL, "§a§lInsérer",
                List.of("§7Insère le placeholder dans le texte.",
                        "§7Sélection : §f" + (selectedPlaceholder == null ? "(aucun)" : "%" + selectedPlaceholder + "%"))));
        inv.setItem(47, glass(Material.PAPER, "§e§lCopier",
                List.of("§7Envoie le placeholder dans le chat.")));
        inv.setItem(49, glass(Material.ARROW,
                mode == ViewMode.PLACEHOLDERS ? "§7← Sources" : "§7← Éditeur",
                List.of("§7Retour au menu précédent.")));
        inv.setItem(51, glass(Material.REDSTONE, "§c§lRafraîchir",
                List.of("§7Recharge les expansions PAPI.")));
        inv.setItem(53, glass(Material.BARRIER, "§7Fermer",
                List.of("§7Ferme ce menu.")));
    }

    /**
     * Effective placeholder count for the current source, taking the eCloud
     * fallback into account. Used by {@link #redraw} to compute totalPages
     * and the "X total" header line.
     */
    private int placeholderCountForCurrentSource() {
        List<String> keys = placeholdersForCurrentSource();
        if (keys.isEmpty() && currentSourceIsPapi && eCloudFetchedKeys != null) {
            keys = eCloudFetchedKeys;
        }
        return keys.size();
    }

    private void drawSources() {
        DataManager dm = plugin.getDataManager();
        com.hudboard.groups.PlaceholderGroupManager gm = plugin.getGroupManager();
        java.util.Set<String> papiIds = dm.discoverPapiIdentifiers();
        int slot = 9;
        for (String cat : BUILTIN_SOURCES) {
            if (slot > 44) break;
            List<String> hardcoded = hardcodedPlaceholdersFor(cat);
            inv.setItem(slot, glass(MAT_BUILTIN_PAPER, "§e§l" + cat,
                    List.of("§7Placeholders intégrés HudBoard.",
                            "§7" + hardcoded.size() + " placeholder" + (hardcoded.size() > 1 ? "s" : "") + " disponible" + (hardcoded.size() > 1 ? "s" : "") + ".",
                            "§7",
                            "§7Exemple : §f%" + hardcoded.get(0) + "%",
                            "§eCliquer pour parcourir")));
            slot++;
        }
        // v1.4.0 / Phase 2.1: user-defined placeholder groups appear
        // between the built-in categories and the PAPI expansions. Labeled
        // "group:<name>" so we never collide with a real PAPI identifier.
        if (gm != null) {
            for (var entry : gm.all().entrySet()) {
                if (slot > 44) break;
                String label = com.hudboard.groups.PlaceholderGroupManager.browserLabel(entry.getKey());
                List<String> lore = new java.util.ArrayList<>();
                lore.add("§dGroupe de placeholders personnalisé.");
                int n = entry.getValue().size();
                lore.add("§d" + n + " placeholder" + (n > 1 ? "s" : "") + " dans ce groupe.");
                if (!entry.getValue().isEmpty()) {
                    lore.add("§7");
                    lore.add("§7Exemple : §f%" + entry.getValue().get(0) + "%");
                }
                lore.add("§7");
                lore.add("§dCliquer pour parcourir");
                inv.setItem(slot, glass(Material.PURPLE_STAINED_GLASS_PANE,
                        "§d§l" + label, lore));
                slot++;
            }
        }
        if (!papiIds.isEmpty() && slot <= 44) {
            for (String id : papiIds) {
                if (slot > 44) break;
                java.util.List<String> keys = dm.placeholdersForExpansion(id);
                // v1.3.16: include any manually-added placeholders for this
                // source so the SOURCES count reflects what the user will
                // actually see when they click into it.
                int manualN = manualKeysCache.getOrDefault(id, java.util.Collections.emptyList()).size();
                int n = keys.size() + manualN;
                String countLabel;
                if (n == 0) countLabel = "§caucun placeholder déclaré (hook legacy)";
                else if (n == 1) countLabel = "§d1 placeholder" + (manualN > 0 ? " §7(+1 manuel)" : "");
                else countLabel = "§d" + n + " placeholders" + (manualN > 0 ? " §7(+" + manualN + " manuels)" : "");
                List<String> lore = new java.util.ArrayList<>();
                lore.add("§7Expansion PlaceholderAPI.");
                lore.add(countLabel);
                if (!keys.isEmpty()) {
                    lore.add("§7");
                    lore.add("§7Exemple : §f%" + keys.get(0) + "%");
                }
                lore.add("§7");
                lore.add("§dCliquer pour parcourir");
                inv.setItem(slot, glass(MAT_PAPI_PAPER, "§d§lpapi:" + id, lore));
                slot++;
            }
        }
    }

    private void drawPlaceholders() {
        // v1.3.16: placeholdersForCurrentSource already merges the base
        // hardcoded list + any eCloud-fetched keys + any manually-added
        // entries for this source. No fallback needed here — the empty
        // view below only fires when the merged list is truly empty
        // (browser has never been told about this source).
        List<String> keys = placeholdersForCurrentSource();
        // Pagination: slice for the current page using the static helper
        // (tested separately in PlaceholderBrowserPaginationTest). The
        // helper already clamps the page index to a valid range, so we
        // don't need extra clamping here.
        int[] slice = pageSlice(keys.size(), currentPage);
        int totalPages = slice[0];
        int from = slice[1];
        int to = slice[2];
        for (int i = from; i < to; i++) {
            int slot = 9 + (i - from);
            String key = keys.get(i);
            String resolved = resolve(key);
            boolean isSelected = key.equals(selectedPlaceholder);
            Material mat = isSelected ? MAT_SELECTED : MAT_PLACEHOLDER;
            List<String> lore = new ArrayList<>();
            lore.add("§7%" + key + "%");
            lore.add("§7");
            lore.add("§7Live value:");
            lore.add("§f" + truncate(resolved, 40));
            if (currentSourceIsPapi && eCloudFetchedKeys != null
                    && eCloudFetchedKeys.contains(key)
                    && !manualKeysCache.getOrDefault(currentSource,
                            java.util.Collections.emptyList()).contains(key)) {
                lore.add("§7");
                lore.add("§d▸ fetched from PAPI eCloud");
            }
            // v1.3.16: also surface manual-entry provenance so the user
            // can tell where each paper came from when they re-enter the
            // source after clicking Back.
            if (manualKeysCache.getOrDefault(currentSource,
                    java.util.Collections.emptyList()).contains(key)) {
                lore.add("§7");
                lore.add("§e▸ ajouté manuellement (persiste)");
            }
            lore.add("§7");
            lore.add(isSelected ? "§a▶ Sélectionné" : "§eCliquer pour sélectionner");
            inv.setItem(slot, glass(mat,
                    (isSelected ? "§a§l" : "§f§l") + "%" + key + "%",
                    lore));
        }
        // Page navigation buttons (slot 50 / 52) — only visible when more
        // than one page. The header (slot 4) is updated separately below to
        // show "Page X/Y" so the user always knows where they are.
        if (totalPages > 1) {
            if (currentPage > 0) {
                inv.setItem(50, glass(Material.ARROW,
                        "§e§l← Page " + currentPage + "/" + totalPages,
                        List.of(
                                "§7Page précédente",
                                "§7Entrées §f" + (from + 1) + "§7-§f" + to + "§7 sur §f" + keys.size()
                        )));
            }
            if (currentPage < totalPages - 1) {
                inv.setItem(52, glass(Material.ARROW,
                        "§e§lPage " + (currentPage + 2) + "/" + totalPages + " →",
                        List.of(
                                "§7Page suivante",
                                "§7Entrées §f" + (from + 1) + "§7-§f" + to + "§7 sur §f" + keys.size()
                        )));
            }
        }
        if (keys.isEmpty()) {
            String sourceName = currentSource == null ? "" : currentSource;
            inv.setItem(22, glass(Material.BARRIER, "§c§l(vide)",
                    List.of(
                            "§7Cette source n'a renvoyé aucun placeholder.",
                            "§7La plupart des expansions PAPI legacy-hook",
                            "§7(" + sourceName + " inclus) gèrent les placeholders",
                            "§7dynamiquement, sans les déclarer.",
                            "§7",
                            "§a§lVous pouvez quand même les utiliser !",
                            "§7Tapez le nom complet dans le texte du",
                            "§7point de données, par exemple :",
                            "§f  §a%§b" + sourceName + "_material_helmet§a%",
                            "§f  §a%§b" + sourceName + "_balance§a%",
                            "§7",
                            "§7Ou cliquez la case orange ci-dessous →"
                    )));
            inv.setItem(31, glass(Material.ORANGE_STAINED_GLASS_PANE,
                    "§6§l⌨ Saisir le placeholder manuellement",
                    List.of(
                            "§7Cliquez, puis tapez le nom du placeholder",
                            "§7(sans les §e%§7). Résolu en direct via PAPI.",
                            "§7",
                            "§7Exemples courants :",
                            "§f  §a%§b" + sourceName + "_<chose>§a%"
                    )));
            // Slot 30: "Fetch from eCloud" — only meaningful for PAPI sources
            // (built-in categories always have hardcoded placeholders, so the
            // empty case never shows for them).
            if (currentSourceIsPapi) {
                inv.setItem(30, glass(Material.CYAN_STAINED_GLASS_PANE,
                        "§b§l☁ Charger depuis PAPI eCloud",
                        List.of(
                                "§7Exécute §f/papi ecloud placeholders " + sourceName + "§7",
                                "§7et charge les placeholders officiels",
                                "§7depuis le cloud PAPI (la liste écrite",
                                "§7par l'auteur de l'expansion).",
                                "§7",
                                "§bCliquer pour charger"
                        )));
            }
        } else if (currentSourceIsPapi && eCloudFetchedKeys == null
                && placeholdersForCurrentSource().isEmpty()) {
            // We have keys to show (probably from eCloud previously) — but
            // if NOT, and the user is on a PAPI source that returned no
            // hardcoded keys, offer to fetch from eCloud as a slot 30 button
            // alongside the placeholder papers. This case shouldn't normally
            // fire (we re-render after fetch and fall through above), but
            // covers race conditions.
            String sourceName = currentSource == null ? "" : currentSource;
            inv.setItem(30, glass(Material.CYAN_STAINED_GLASS_PANE,
                    "§b§l☁ Rafraîchir depuis PAPI eCloud",
                    List.of(
                            "§7Relance §f/papi ecloud placeholders " + sourceName + "§7",
                            "§bCliquer pour rafraîchir"
                    )));
        }
    }

    /**
     * Effective placeholder list for the current source. Three layers,
     * merged in this order:
     *
     * <ol>
     *   <li><b>Base</b> — the hardcoded placeholders for built-in
     *       categories, or the local PAPI hook's declared placeholders
     *       for installed expansions (often empty for legacy-hook
     *       expansions like Armor).</li>
     *   <li><b>eCloud-fetched templates</b> — the {@link #eCloudFetchedKeys}
     *       list, which holds what was returned by
     *       {@code PapiECloud.fetchPlaceholders} for this source. Non-null
     *       after a successful Fetch / after the manual-insert dialog
     *       auto-fetches. Cleared on Back. We include this only for PAPI
     *       sources so the templates stay visible without re-fetching
     *       every click.</li>
     *   <li><b>Manual entries</b> — keys the user typed via the "Type
     *       manually" dialog. Persisted across navigation in
     *       {@link #manualKeysCache} so they survive a Back + re-click.</li>
     * </ol>
     *
     * <p>Returns an immutable {@link java.util.ArrayList} copy — callers
     * must not rely on the merge set leaking through.</p>
     */
    private List<String> placeholdersForCurrentSource() {
        if (currentSource == null) return List.of();
        List<String> base;
        if (currentSourceIsPapi) {
            base = plugin.getDataManager().placeholdersForExpansion(currentSource);
        } else if (currentSource.startsWith("group:")) {
            // Phase 2.1: user-defined placeholder group. The source label
            // is "group:<name>" — strip the prefix and look up the keys.
            String name = currentSource.substring("group:".length());
            com.hudboard.groups.PlaceholderGroupManager gm = plugin.getGroupManager();
            if (gm != null && gm.exists(name)) {
                base = new java.util.ArrayList<>(gm.all().get(name));
            } else {
                base = List.of();
            }
        } else {
            base = hardcodedPlaceholdersFor(currentSource);
        }
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(base);
        // Layer 2: eCloud-fetched templates (PAPI sources only).
        if (currentSourceIsPapi && eCloudFetchedKeys != null) {
            merged.addAll(eCloudFetchedKeys);
        }
        // Layer 3: manual entries from the dialog (any source).
        List<String> manual = manualKeysForCurrentSource();
        if (manual != null) merged.addAll(manual);
        return new java.util.ArrayList<>(merged);
    }

    private List<String> hardcodedPlaceholdersFor(String source) {
        return switch (source) {
            case "server" -> List.of(
                    "server_name", "server_motd", "server_version", "server_tps",
                    "server_mspt", "server_online", "server_max", "server_worlds",
                    "server_time", "server_weather"
            );
            case "player" -> List.of(
                    "player_name", "player_uuid", "player_health", "player_food",
                    "player_level", "player_xp", "player_world", "player_biome",
                    "player_gamemode", "player_kills", "player_deaths",
                    "player_kdr", "player_playtime", "player_balance"
            );
            case "world" -> List.of(
                    "world_name", "world_time", "world_weather", "world_difficulty",
                    "world_player_count"
            );
            case "economy" -> List.of(
                    "vault_eco_balance", "vault_eco_balance_formatted", "vault_currency"
            );
            case "top" -> List.of(
                    "top1_name", "top1_value", "top2_name", "top2_value",
                    "top3_name", "top3_value"
            );
            case "target" -> List.of(
                    "target_name", "target_health", "target_world"
            );
            case "runtime" -> List.of(
                    "event_name", "event_state", "playtime_label", "world_label"
            );
            case "hudboard-api" -> List.of(
                    "api_<your_key>"
            );
            case "misc" -> List.of(
                    "online_players", "loaded_chunks", "ram_used", "ram_max"
            );
            default -> List.of();
        };
    }

    private String truncate(String s, int max) {
        if (s == null) return "—";
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
        if (slot < 0 || slot >= 54) return true;
        Player p = (Player) e.getWhoClicked();
        MenuSfx.click(p, plugin);
        if (slot == 45) { insertSelected(p); return true; }
        if (slot == 47) { copySelected(p); return true; }
        if (slot == 49) {
            if (mode == ViewMode.PLACEHOLDERS) {
                mode = ViewMode.SOURCES;
                currentSource = null;
                currentSourceIsPapi = false;
                selectedPlaceholder = null;
                eCloudFetchedKeys = null;
                currentPage = 0;
                redraw();
                p.updateInventory();
            } else {
                p.closeInventory();
                var inst = plugin.getPanelManager().get(panelName, true);
                if (inst != null) new DataPointEditorMenu(plugin, inst, dpIndex).open(p);
            }
            return true;
        }
        // Page navigation (v1.3.14). Slots 50/52 only show when there's more
        // than one page; the click handler is harmless when no nav button
        // is rendered (nothing to do).
        if (slot == 50 && currentPage > 0) {
            currentPage--;
            redraw();
            p.updateInventory();
            return true;
        }
        if (slot == 52) {
            int count = placeholderCountForCurrentSource();
            int totalPages = Math.max(1, (count + PAPERS_PER_PAGE - 1) / PAPERS_PER_PAGE);
            if (currentPage < totalPages - 1) {
                currentPage++;
                redraw();
                p.updateInventory();
            }
            return true;
        }
        if (slot == 51) {
            // Refresh button: drop the PAPI eCloud installed-expansion cache
            // so the next "Fetch from eCloud" reflects any new expansion
            // the admin just installed via /papi ecloud download.
            com.hudboard.data.PapiECloud.clearInstalledCache();
            redraw();
            p.updateInventory();
            return true;
        }
        if (slot == 53) { p.closeInventory(); return true; }
        // Slot 31 is the "type placeholder manually" action (only when empty)
        if (slot == 31 && mode == ViewMode.PLACEHOLDERS
                && placeholdersForCurrentSource().isEmpty()) {
            promptManualPlaceholder(p);
            return true;
        }
        // Slot 30 is the "fetch from PAPI eCloud" action. Only meaningful for
        // PAPI expansions and only when the regular source is empty.
        if (slot == 30 && mode == ViewMode.PLACEHOLDERS && currentSourceIsPapi) {
            fetchFromECloud(p);
            return true;
        }
        if (slot >= 9 && slot <= 44) {
            int idx = slot - 9;
            if (mode == ViewMode.SOURCES) {
                java.util.Set<String> papiIds = plugin.getDataManager().discoverPapiIdentifiers();
                com.hudboard.groups.PlaceholderGroupManager gm = plugin.getGroupManager();
                int nBuiltin = BUILTIN_SOURCES.length;
                if (idx < nBuiltin) {
                    currentSource = BUILTIN_SOURCES[idx];
                    currentSourceIsPapi = false;
                } else {
                    int cursor = nBuiltin;
                    if (gm != null) {
                        for (String groupName : gm.all().keySet()) {
                            if (cursor == idx) {
                                currentSource = com.hudboard.groups.PlaceholderGroupManager.browserLabel(groupName);
                                currentSourceIsPapi = false;
                                break;
                            }
                            cursor++;
                            if (cursor > idx) break;
                        }
                    }
                    if (currentSource == null) {
                        int papiIdx = idx - cursor;
                        int i = 0;
                        for (String id : papiIds) {
                            if (i++ == papiIdx) { currentSource = id; currentSourceIsPapi = true; break; }
                            if (i > papiIdx) break;
                        }
                    }
                }
                if (currentSource != null) {
                    selectedPlaceholder = null;
                    mode = ViewMode.PLACEHOLDERS;
                    eCloudFetchedKeys = null; // reset eCloud cache when source changes
                    currentPage = 0;          // start at the first page on a new source
                    redraw();
                    p.updateInventory();
                }
            } else {
                // Page-local idx (0..35) maps to a global index in the
                // effective keys list, accounting for both pagination AND
                // the eCloud-fetched fallback that takes over when the
                // hardcoded source is empty.
                List<String> keys = placeholdersForCurrentSource();
                if (keys.isEmpty() && currentSourceIsPapi && eCloudFetchedKeys != null) {
                    keys = eCloudFetchedKeys;
                }
                int from = currentPage * PAPERS_PER_PAGE;
                int globalIdx = from + idx;
                if (globalIdx < keys.size()) {
                    selectedPlaceholder = keys.get(globalIdx);
                    redraw();
                    p.updateInventory();
                }
            }
        }
        return true;
    }

    /**
     * Run {@code /papi ecloud placeholders <currentSource>} against a
     * capturing command sender and load the resulting template list into
     * {@link #eCloudFetchedKeys}. If PAPI returns any placeholders we
     * re-draw the PLACEHOLDERS view so the user can click-to-insert them.
     * If PAPI returns nothing (expansion not in the eCloud), we keep the
     * empty view but show the user a chat message explaining the situation.
     */
    private void fetchFromECloud(Player p) {
        if (currentSource == null || !currentSourceIsPapi) return;
        // Run the fetch synchronously — it's a fast command and the user is
        // already waiting on the chat prompt.
        java.util.List<String> keys;
        try {
            keys = PapiECloud.fetchPlaceholders(currentSource);
        } catch (Throwable t) {
            p.sendMessage(Component.text()
                    .append(Component.text("Fetch failed: ", NamedTextColor.RED))
                    .append(Component.text(String.valueOf(t.getMessage()), NamedTextColor.RED))
                    .build());
            return;
        }
        eCloudFetchedKeys = keys;
        if (keys.isEmpty()) {
            // NOTE: never embed "§" colour codes in raw text of sendMessage.
            // Paper serialises outbound Component to JSON and rejects any
            // chat string that contains "§", so copy-pasted messages that
            // start with "/" then get treated as commands and crash the
            // server. We use pure Adventure styling instead — the visible
            // output is identical, but the underlying text data has no
            // forbidden characters.
            p.sendMessage(Component.text()
                    .append(Component.text("HudBoard", NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" \u2192 No placeholders found in PAPI eCloud for ",
                            NamedTextColor.GRAY))
                    .append(Component.text(currentSource, NamedTextColor.AQUA, TextDecoration.BOLD))
                    .append(Component.text(". The expansion might not be in the eCloud, or it's truly empty.",
                            NamedTextColor.GRAY))
                    .build());
            p.sendMessage(Component.text("You can still type the name manually via slot 31.",
                    NamedTextColor.GRAY));
        } else {
            int pages = Math.max(1, (keys.size() + PAPERS_PER_PAGE - 1) / PAPERS_PER_PAGE);
            p.sendMessage(Component.text()
                    .append(Component.text("HudBoard", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .append(Component.text(" \u2192 Fetched ", NamedTextColor.GRAY))
                    .append(Component.text(String.valueOf(keys.size()), NamedTextColor.AQUA, TextDecoration.BOLD))
                    .append(Component.text(" template" + (keys.size() > 1 ? "s" : "")
                            + " from PAPI eCloud for ", NamedTextColor.GRAY))
                    .append(Component.text(currentSource, NamedTextColor.AQUA, TextDecoration.BOLD))
                    .append(Component.text(". " + pages + " page" + (pages > 1 ? "s" : "")
                            + " of papers — use slots 50/52 to paginate.",
                            NamedTextColor.GRAY))
                    .build());
        }
        currentPage = 0; // jump to first page after a fetch so the user sees the new templates
        redraw();
        p.updateInventory();
    }

    /**
     * Open a Paper dialog for typing a placeholder name manually. Used for
     * legacy-hook PAPI expansions (armor, etc.) that don't declare their
     * placeholders but resolve them dynamically.
     *
     * <p>v1.3.9: replaced the legacy {@code ConversationFactory} chat
     * prompt with Paper's native Dialog API so the user stays in the menu
     * flow end-to-end — every other input in the plugin is a dialog, so
     * having one flow drop back to chat felt inconsistent and triggered
     * the "Disallowed chat character '§'" crash whenever the resulting
     * message ended up in a {@code SuggestCommand} click event.</p>
     */
    private void promptManualPlaceholder(Player p) {
        // Close the inventory first so the dialog can open cleanly.
        p.closeInventory();
        var inst = plugin.getPanelManager().get(panelName, true);
        if (inst == null) {
            // Panel vanished while we were in the browser — reopen nothing.
            return;
        }
        plugin.getDialogBridge().openManualPlaceholderDialog(
                p, this, inst, dpIndex, currentSource);
    }

    /**
     * Add a manually-typed placeholder as a new "paper" to this browser's
     * placeholder list (the same shape as the templates returned by
     * {@link com.hudboard.data.PapiECloud#fetchPlaceholders}). The user can
     * then click it like any other placeholder to select it, and finally
     * click the Insert (slot 45) button to write it into the data point.
     *
     * <p>v1.3.11: also auto-triggers a refresh of the eCloud list so the
     * user doesn't need to manually click Refresh (slot 51) after the
     * manual add. The fetched templates and the manual placeholder are
     * merged (de-duped, sorted, all clickable). If eCloud returns nothing
     * the manual placeholder is still added on its own.</p>
     */
    public void insertPlaceholderFromDialog(Player p, String key) {
        // Ensure we're in the placeholder view of the right source.
        if (mode != ViewMode.PLACEHOLDERS) {
            mode = ViewMode.PLACEHOLDERS;
        }
        // v2.0.1: PERSIST the manual entry to disk via ManualKeysStorage.
        // The local cache is updated too so the new entry shows up
        // immediately in the re-opened browser without a full refresh.
        com.hudboard.data.ManualKeysStorage storage = plugin.getManualKeys();
        if (storage != null) {
            storage.add(currentSource, key);
        }
        java.util.List<String> manualHistory = manualKeysCache
                .computeIfAbsent(currentSource, k -> new java.util.ArrayList<>());
        if (!manualHistory.contains(key)) manualHistory.add(key);

        // v1.3.11: auto-refresh eCloud cache + try fetching templates so the
        // browser shows both eCloud templates and the manual placeholder.
        // Falls through to the "merge with manual key" path either way.
        java.util.List<String> eCloudKeys = null;
        try {
            com.hudboard.data.PapiECloud.clearInstalledCache();
            eCloudKeys = com.hudboard.data.PapiECloud.fetchPlaceholders(currentSource);
        } catch (Throwable ignored) {}
        // Merge eCloud + ALL manual history (so any earlier manual entries
        // for this source also appear) into eCloudFetchedKeys (dedup + sort).
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (eCloudKeys != null) merged.addAll(eCloudKeys);
        merged.addAll(manualHistory);
        eCloudFetchedKeys = new java.util.ArrayList<>(merged);
        java.util.Collections.sort(eCloudFetchedKeys);
        // Reset to first page so the freshly-added (selected) paper is visible.
        currentPage = 0;
        selectedPlaceholder = key;
        // Notify in chat so the user knows what happened.
        String live = resolve(key);
        p.sendMessage(Component.text()
                .append(Component.text("Added ", NamedTextColor.GREEN))
                .append(Component.text("%" + key + "%", NamedTextColor.AQUA))
                .append(Component.text(" to the placeholder list \u2192 ", NamedTextColor.GRAY))
                .append(Component.text("live: " + truncate(live, 40), NamedTextColor.WHITE))
                .build());
        int total = eCloudFetchedKeys.size();
        p.sendMessage(Component.text(
                "Cliquez Insérer (slot 45) pour l'écrire dans le point de données, "
                        + "ou choisissez-en un autre. " + total + " placeholder"
                        + (total > 1 ? "s" : "") + " listé" + (total > 1 ? "s" : "") + ".",
                NamedTextColor.GRAY));
        // Re-open the browser asynchronously so the dialog's close frame
        // finishes before the chest UI re-mounts.
        Bukkit.getScheduler().runTask(plugin, () -> open(p));
    }

    /**
     * Drop every per-source manual placeholder the user has added in this
     * session. Used by tests and by the {@code /hudboard reload} path so
     * stale entries don't linger after a profile reload.
     */
    public void clearManualPlaceholders() {
        manualKeysCache.clear();
        // Note: we deliberately DO NOT touch ManualKeysStorage here —
        // this method is only used by the in-memory browser to reset its
        // session state. Use {@code /hudboard manual clear} for disk
        // mutation.
    }

    /** Insert `key` directly without going through the selectedPlaceholder flow. */
    private void insertDirect(Player p, String key) {
        var inst = plugin.getPanelManager().get(panelName, true);
        if (inst == null || dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) {
            p.sendMessage(Component.text("Panneau disparu — insertion impossible.", NamedTextColor.RED));
            return;
        }
        var dp = inst.profile.dataPoints.get(dpIndex);
        String current = dp.text == null ? "" : dp.text;
        String toInsert = "%" + key + "%";
        String newText;
        if (current.isEmpty()) newText = toInsert;
        else if (current.endsWith(" ") || current.endsWith("\n")) newText = current + toInsert;
        else newText = current + " " + toInsert;
        dp.text = newText;
        plugin.getPanelManager().saveProfileToDisk(inst.profile);
        plugin.getPanelManager().invalidate(inst);
        forceRenderAllTiles(inst);
        String live = resolve(key);
        p.sendMessage(Component.text()
                .append(Component.text("Inserted ", NamedTextColor.GREEN))
                .append(Component.text(toInsert, NamedTextColor.WHITE))
                .append(Component.text(" \u2192 live: ", NamedTextColor.GREEN))
                .append(Component.text(truncate(live, 40), NamedTextColor.WHITE))
                .build());
        // Re-open the data point editor
        Bukkit.getScheduler().runTask(plugin, () -> new DataPointEditorMenu(plugin, inst, dpIndex).open(p));
    }

    private void insertSelected(Player p) {
        if (selectedPlaceholder == null) {
            p.sendMessage(Component.text("No placeholder selected \u2014 pick one first.",
                    NamedTextColor.YELLOW));
            return;
        }
        var inst = plugin.getPanelManager().get(panelName, true);
        if (inst == null || dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) {
            p.sendMessage(Component.text("Panel or data point not found.", NamedTextColor.RED));
            return;
        }
        var dp = inst.profile.dataPoints.get(dpIndex);
        String current = dp.text == null ? "" : dp.text;
        String toInsert = "%" + selectedPlaceholder + "%";
        String newText;
        if (current.isEmpty()) newText = toInsert;
        else if (current.endsWith(" ") || current.endsWith("\n")) newText = current + toInsert;
        else newText = current + " " + toInsert;
        dp.text = newText;
        plugin.getPanelManager().saveProfileToDisk(inst.profile);
        plugin.getPanelManager().invalidate(inst);
        forceRenderAllTiles(inst);
        p.sendMessage(Component.text()
                .append(Component.text("Inserted ", NamedTextColor.GREEN))
                .append(Component.text(toInsert, NamedTextColor.WHITE))
                .append(Component.text(" into ", NamedTextColor.GREEN))
                .append(Component.text(dp.key, NamedTextColor.WHITE))
                .build());
        p.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> new DataPointEditorMenu(plugin, inst, dpIndex).open(p));
    }

    private void copySelected(Player p) {
        if (selectedPlaceholder == null) {
            p.sendMessage(Component.text("No placeholder selected \u2014 pick one first.",
                    NamedTextColor.YELLOW));
            return;
        }
        String text = "%" + selectedPlaceholder + "%";
        p.sendMessage(Component.text()
                .append(Component.text("Cliquer pour copier : ", NamedTextColor.GREEN))
                .append(Component.text(text)
                        .color(NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.copyToClipboard(text))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Cliquer pour copier " + text + " dans le presse-papier", NamedTextColor.YELLOW))))
                .build());
        p.sendMessage(Component.text("(Puis collez avec Ctrl+V où vous voulez.)", NamedTextColor.GRAY));
    }

    private void forceRenderAllTiles(InfoPanelInstance i) {
        if (i == null || i.views == null) return;
        for (org.bukkit.map.MapView v : i.views) {
            if (v == null) continue;
            for (var r : v.getRenderers()) {
                if (r instanceof com.hudboard.panel.InfoPanelRenderer ipr) ipr.forceFrameToViewers();
            }
        }
    }

    public static void onClose(InventoryCloseEvent e) {
        // OPEN map is cleaned by the listener
    }
}
