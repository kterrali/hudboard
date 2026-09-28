package com.hudboard.menu;

import com.hudboard.HudBoardPlugin;
import com.hudboard.lang.Lang;
import com.hudboard.panel.InfoPanel;
import com.hudboard.panel.InfoPanelInstance;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between HudBoard and Paper's Dialog API (1.21.7+, native Minecraft
 * 1.21.6+). Replaces the anvil and sign-based input with native dialogs that
 * support text, multi-line text, number ranges (sliders), single-choice lists,
 * and boolean checkboxes — all vanilla UI.
 *
 * <p>Dialogs are much nicer than the anvil (50-char single-line) or the sign
 * editor (4×30 chars, requires placing a sign block). The Dialog API is
 * native to Paper 1.21.7+, available on 1.21.11.
 *
 * <h2>Session tracking</h2>
 * When a dialog is shown, a {@link Session} is registered keyed by the
 * player's UUID. When the player clicks the dialog's confirm button,
 * {@link PlayerCustomClickEvent} fires, the session is looked up, the
 * value is extracted from {@code DialogResponseView} and applied, then the
 * session is removed and the editor is re-opened.
 */
public class DialogInputBridge implements Listener {

    private final HudBoardPlugin plugin;

    /** Active dialog sessions, keyed by player UUID. */
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    /** Active placeholder-browser sessions, keyed by player UUID. Used by
     *  MANUALPLACEHOLDER kind to look up the browser that opened the dialog
     *  and re-open it after the user submits. */
    private static final Map<UUID, com.hudboard.menu.PlaceholderBrowser> PLACEHOLDER_BROWSERS = new ConcurrentHashMap<>();

    /** Namespace for all HudBoard dialog identifiers. */
    public static final String NS = "hudboard";

    public DialogInputBridge(HudBoardPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * One session describes the dialog currently open for a player and the
     * action to take when the player submits the form. The action is a
     * pure function of the response map, so we can replay it later if
     * needed.
     */
    public record Session(
            SessionKind kind,
            InfoPanelInstance inst,
            int dpIndex,
            String property,
            @Nullable Component reOpenMenuTitle
    ) {}

    public enum SessionKind {
        TEXT,             // single-line text field (x, y, tile-x, tile-y, size, anim-ms, name)
        MULTILINE,        // multi-line text (the "text" property of a data point)
        GRADIENT_HELPER, // v2.3.0: sub-dialog that returns a gradient tag to the parent MULTILINE session
        ANIMATION,        // full animation editor: type (single-choice) + speed (number) + color (text)
        ANIMCOLOR,        // legacy single-line color for animation (still here for back-compat, not opened by the editor anymore)
        BASECOLOR,        // MiniMessage color tag for the data point's base text color
        BULK_BASECOLOR,   // Phase 2.2: apply one base-color tag to every data point in the panel at once
        ADDDATAPOINT,     // new data point creation (multi-line)
        RENAMEPANEL,      // single-line for the panel name
        MANUALPLACEHOLDER // manual entry of a legacy-hook placeholder name (from PlaceholderBrowser slot 31)
    }

    // ---------------------------------------------------------------------
    // Public entry points — one per use case
    // ---------------------------------------------------------------------

    /**
     * Open a single-line text input dialog for one property of a data point.
     * Use this for x, y, tile-x, tile-y, size, anim-ms.
     */
    public void openTextProperty(Player p, InfoPanelInstance inst, int dpIndex, String property) {
        if (dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) return;
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dpIndex);
        String current = String.valueOf(getPropertyValue(dp, property));
        String label = "Modifier §e" + property + "§r de §a" + dp.key;
        String hint = hintForProperty(property);
        showTextDialog(p, label, hint, current, 256, false, new Session(
                SessionKind.TEXT, inst, dpIndex, property, null));
    }

    /** Open a multi-line text dialog for the "text" property of a data point. */
    public void openMultilineText(Player p, InfoPanelInstance inst, int dpIndex) {
        if (dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) return;
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dpIndex);
        String current = dp.text == null ? " " : dp.text;
        String label = "Texte de §a" + dp.key;
        // Court et utile. Placeholders et couleurs sont détaillés via la
        // propriété `text` du menu d'édition ; ici on ne rappelle que
        // l'essentiel.
        String hint = "§7Texte affiché sur le panneau.\n" +
                "§7• Placeholders : §f%server_tps% §7· §f%player_name% §7· §f%vault_eco_balance%\n" +
                "§7• Couleurs : §f<red>...</red> <bold>...</bold> <gradient:red:blue>...</gradient>\n" +
                "§7• Saut de ligne : §f<newline> §7ou §f<n> §7ou §f<br>\n" +
                "§7• Bouton §dDégradé §7ci-dessous pour insérer un gradient.";
        // v1.4.1: per-player live preview. Resolve the current text for
        // THIS player (so %player_name% shows the editor's own name, not
        // "—") and render it through MiniMessage with the dp's base-color
        // prepended. The user can see the result before Save.
        Component preview = buildTextPreview(p, dp, current);
        showMultilineDialogWithGradient(p, label, hint, current, new Session(
                SessionKind.MULTILINE, inst, dpIndex, "text", null), preview);
    }

    /**
     * v2.3.0: multiline text dialog with an extra "Add gradient" action
     * button. Clicking it closes this dialog and opens a small sub-dialog
     * asking for the two gradient colours. After the sub-dialog Save, the
     * text dialog re-opens with the new gradient tag appended at the end.
     */
    private void showMultilineDialogWithGradient(Player p, String label, String hint,
                                                  String initial, Session session, Component preview) {
        SESSIONS.put(p.getUniqueId(), session);
        Component title = mm("§6" + label);
        List<DialogBody> bodies = new ArrayList<>();
        bodies.add(DialogBody.plainMessage(mm(hint)));
        if (preview != null) {
            bodies.add(DialogBody.plainMessage(Component.empty()));
            bodies.add(DialogBody.plainMessage(preview));
        }
        DialogBase.Builder base = DialogBase.builder(title)
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(bodies);
        TextDialogInput.Builder textBuilder = DialogInput.text("value", mm("§eValeur"))
                .initial(initial == null ? "" : initial)
                .maxLength(1024)
                .width(400)
                .multiline(TextDialogInput.MultilineOptions.create(6, 80));
        DialogInput textInput = textBuilder.build();
        base.inputs(List.of(textInput));
        ActionButton save = ActionButton.create(
                mm("§a§lValider"), null, 150,
                DialogAction.customClick(Key.key(NS, "dialog/save"), null));
        ActionButton gradient = ActionButton.create(
                mm("§d§lDégradé"), null, 150,
                DialogAction.customClick(Key.key(NS, "dialog/gradient"), null));
ActionButton cancel = ActionButton.create(
                mm("§c§lAnnuler"), null, 150,
                DialogAction.customClick(Key.key(NS, "dialog/cancel"), null));
        // Paper 1.21.11 DialogType.multiAction signature:
        // (List<ActionButton> actions, ActionButton exitButton, int columns).
        // We have 3 actions (Save / Gradient / Cancel) — pass 3 cols so
        // each action gets its own button on a single row.
        DialogType type = DialogType.multiAction(
                List.of(save, gradient, cancel), cancel, 3);
        Dialog dialog = Dialog.create(b -> b.empty().base(base.build()).type(type));
        p.showDialog(dialog);
    }

    /**
     * v2.3.0: small sub-dialog asking for two gradient colors. After
     * save, the resulting {@code <gradient:c1:c2>...</gradient>} tag
     * is appended to the parent text dialog's current value and the
     * text dialog re-opens. Implemented as a new SessionKind so the
     * existing dialog/save handler routes the right way back.
     */
    private void openGradientHelper(Player p, Session parent) {
        SESSIONS.put(p.getUniqueId(), new Session(
                SessionKind.GRADIENT_HELPER, parent.inst(), parent.dpIndex(),
                "gradient-helper", null));
        // Look up the actual key from the parent DataPoint so the title
        // shows the right name. Fall back to "data point" if the index
        // is out of range (theoretical — admin shouldn't be here then).
        String dpKey = "data point";
        if (parent.inst() != null && parent.dpIndex() >= 0
                && parent.dpIndex() < parent.inst().profile.dataPoints.size()) {
            dpKey = parent.inst().profile.dataPoints.get(parent.dpIndex()).key;
        }
        Component title = mm("§dDégradé pour " + dpKey);
        DialogBase.Builder base = DialogBase.builder(title)
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE);
        // Two single-line inputs side by side: color1 and color2.
        TextDialogInput c1 = DialogInput.text("c1", mm("§eCouleur 1"))
                .initial("red").maxLength(32).width(180).build();
        TextDialogInput c2 = DialogInput.text("c2", mm("§eCouleur 2"))
                .initial("blue").maxLength(32).width(180).build();
        base.inputs(List.of(c1, c2))
                .body(List.of(DialogBody.plainMessage(mm(
                        "§7Deux couleurs (nommées §fred§7/§fblue§7 ou hex §f#FF8800§7).\n" +
                                "§7Résultat : §f<gradient:c1:c2>...</gradient>\n" +
                                "§7Sera ajouté à la fin du texte actuel."))));
        ActionButton save = ActionButton.create(
                mm("§a§lInsérer"), null, 150,
                DialogAction.customClick(Key.key(NS, "dialog/save"), null));
        ActionButton cancel = ActionButton.create(
                mm("§c§lAnnuler"), null, 150,
                DialogAction.customClick(Key.key(NS, "dialog/cancel"), null));
        DialogType type = DialogType.confirmation(save, cancel);
        Dialog dialog = Dialog.create(b -> b.empty().base(base.build()).type(type));
        p.showDialog(dialog);
    }

    /**
     * v1.4.1: render a sample of the data-point's text as it would appear
     * to the editor. Placeholders are resolved for the editor's player so
     * {@code %player_name%} shows their own name, and the data-point's
     * base-color (if any) is prepended so the colour matches what the
     * final panel will render. Newlines are converted to literal newlines
     * because the dialog body renders one paragraph per line.
     *
     * <p>On any error (PAPI exception, MiniMessage parse, etc.) the
     * preview collapses to a yellow "(render error)" component — we'd
     * rather show something than crash the dialog.</p>
     */
    private Component buildTextPreview(Player editor, InfoPanel.DataPoint dp, String current) {
        if (current == null || current.isBlank()) {
            return Component.text("Preview → (empty)", NamedTextColor.GRAY);
        }
        String resolved;
        try {
            resolved = plugin.getDataManager().resolve(current, editor);
        } catch (Throwable t) {
            return Component.text("Preview → (PAPI error: " + t.getMessage() + ")", NamedTextColor.RED);
        }
        String withColor = (dp.baseColor == null || dp.baseColor.isBlank())
                ? resolved
                : dp.baseColor + resolved;
        try {
            return Component.text("Preview → ", NamedTextColor.GRAY)
                    .append(mm(withColor));
        } catch (Throwable t) {
            return Component.text("Preview → (render error)", NamedTextColor.RED);
        }
    }

    /**
     * Legacy single-option dialog — kept for back-compat with any code
     * path that still calls it, but the new editor uses {@link #openAnimationEditor}
     * which exposes type + speed + color in a single dialog.
     */
    public void openAnimationChoice(Player p, InfoPanelInstance inst, int dpIndex) {
        if (dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) return;
        openAnimationEditor(p, inst, dpIndex);
    }

    /**
     * Open a single-line text dialog for the animation color. Accepts any
     * MiniMessage color tag (named or hex or gradient). The user can type
     * "&lt;red&gt;", "&lt;#FF8800&gt;", or "&lt;gradient:red:blue&gt;" and the
     * renderer's animation code will use it.
     */
    public void openAnimColorChoice(Player p, InfoPanelInstance inst, int dpIndex) {
        if (dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) return;
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dpIndex);
        String current = colorToMiniMessage(dp.animColor);
        showTextDialog(p,
                "Couleur d'animation pour §a" + dp.key,
                "§7Tag de couleur MiniMessage :\n" +
                        "§f<red> <blue> <green> <gold> <yellow> <aqua> <gray> <white>\n" +
                        "§7Hex : §f<#FF8800>\n" +
                        "§7Dégradé : §f<gradient:red:blue>...</gradient>",
                current,
                64,
                false,
                new Session(SessionKind.ANIMCOLOR, inst, dpIndex, "anim-color", null));
    }

    /**
     * Open a single-line dialog for the data point's BASE text color. This
     * color is automatically prepended to the text (e.g. typing
     * {@code <red>} + text "hello world" renders as red). The user can still
     * embed other color tags inside the text to highlight one word.
     */
    public void openBaseColorChoice(Player p, InfoPanelInstance inst, int dpIndex) {
        if (dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) return;
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dpIndex);
        String current = dp.baseColor == null || dp.baseColor.isBlank() ? "" : dp.baseColor;
        // v1.4.1: include a live preview of the CURRENT tag applied to a
        // sample phrase. After Save, the dialog is re-opened from
        // reopenEditor() so the preview updates with the new value.
        Component preview = buildColorPreview(current, "Bonjour, " + dp.key + " !");
        showTextDialog(p,
                "Couleur de base pour §a" + dp.key,
                "§7Couleur appliquée à tout le texte.\n" +
                        "§7Exemple : §f<red> §7→ texte entier en rouge.\n" +
                        "§7Vous pouvez garder §f<white>...</white> §7à l'intérieur\n" +
                        "§7pour surligner un mot.\n" +
                        "§7Vide = utilise la couleur int d'origine.\n" +
                        "§7Tags : §f<red> <blue> <green> <gold> <yellow> <#FF8800> <gradient:red:blue>",
                current,
                64,
                false,
                new Session(SessionKind.BASECOLOR, inst, dpIndex, "base-color", null),
                preview);
    }

    /**
     * Phase 2.2 — open a dialog that lets the user apply ONE base-color tag
     * to EVERY data point in a panel at once. Existing per-data-point
     * overrides are REPLACED with the new value (use the per-data-point
     * editor to set individual exceptions afterwards).
     */
    public void openBulkBaseColorChoice(Player p, InfoPanelInstance inst) {
        if (inst == null || inst.profile.dataPoints.isEmpty()) {
            p.sendMessage(mm("§cCe panneau n'a aucun point de données à colorer."));
            return;
        }
        // v1.4.1: include a preview of the (empty by default) tag applied
        // to a sample so the admin sees the resulting color before saving.
        // The bulk dialog always starts empty — the preview is informational
        // for the SAMPLE, not for any existing values.
        Component preview = buildColorPreview("", "Tous les points de données ressembleront à ça.");
        showTextDialog(p,
                "Couleur de base globale pour §a" + inst.name,
                "§7Applique la couleur à TOUS les points de données du panneau.\n" +
                        "§6Les couleurs individuelles sont écrasées.§7\n" +
                        "§7Exemple : §f<red> §7→ tout en rouge.\n" +
                        "§7Vide = retire toutes les couleurs de base.\n" +
                        "§7Tags : §f<red> <blue> <green> <gold> <yellow> <#FF8800> <gradient:red:blue>",
                "",
                64,
                false,
                new Session(SessionKind.BULK_BASECOLOR, inst, -1, "bulk-base-color", null),
                preview);
    }

    /** Open a multi-line dialog to add a new data point definition. The
     *  default template is now the "Player name" preset so the admin
     *  has a working example to edit instead of an empty placeholder.
     *  See {@link #ADD_DP_TEMPLATES} for the full preset list — admins
     *  can use {@code /hudboard panel add} or the slot 37 button which
     *  now uses these as initial values. */
    public void openAddDataPoint(Player p, InfoPanelInstance inst) {
        openAddDataPointWithTemplate(p, inst, "player");
    }

    /** v2.3.0: open the add-data-point dialog pre-filled with one of
     *  {@link #ADD_DP_TEMPLATES}. Use the key from the map; unknown
     *  keys fall back to the "player" preset. */
    public void openAddDataPointWithTemplate(Player p, InfoPanelInstance inst, String templateKey) {
        String template = ADD_DP_TEMPLATES.getOrDefault(templateKey, ADD_DP_TEMPLATES.get("player"));
        String label = "Nouveau point pour §a" + inst.name
                + (templateKey != null && ADD_DP_TEMPLATES.containsKey(templateKey)
                ? " <gray>(modèle : " + templateKey + ")</gray>" : "");
        String hint = "§7Format : §fcle x=N y=N taille=N text=\"...\"\n" +
                "§7Placeholders utilisables : §f%server_tps%, %player_name%...\n" +
                "§6Modifiez le modèle avant de valider <gray>— seule la clé doit être unique.</gray>";
        showTextDialog(p, label, hint, template, 1024, true, new Session(
                SessionKind.ADDDATAPOINT, inst, -1, "add-dp:" + templateKey, null));
    }

    /**
     * v2.3.0: preset data-point definitions the admin can pick from a
     *  menu. Each value is the full string the dialog pre-fills, ready
     *  to be tweaked (key, x/y, text). Keep these short — they show in
     *  a sub-menu inside the panel editor.
     */
    public static final java.util.Map<String, String> ADD_DP_TEMPLATES = java.util.Map.of(
            "player",       "player x=4 y=4 size=14 text=\"%player_name%\"",
            "tps",          "tps x=4 y=24 size=12 text=\"TPS: %server_tps%\"",
            "balance",      "balance x=4 y=44 size=14 text=\"Balance: %vault_eco_balance%\"",
            "time",         "time x=64 y=4 size=12 text=\"<gold>%server_time_HH:mm%</gold>\"",
            "alert",        "alert x=4 y=4 size=16 text=\"<red>ALERT</red>\"",
            "blank",        "newkey x=4 y=4 size=14 text=\"\""
    );

    /** Open a single-line dialog to rename a placed panel. */
    public void openRenamePanel(Player p, InfoPanelInstance inst) {
        String label = "Renommer le panneau §a" + inst.name;
        String hint = "§7Lettres, chiffres, _, -, . — pas d'espaces";
        showTextDialog(p, label, hint, inst.name, 64, false, new Session(
                SessionKind.RENAMEPANEL, inst, -1, "rename-panel", null));
    }

    /** Open a multi-line dialog for the PlaceholderBrowser insertion result. */
    public void openInsertedText(Player p, InfoPanelInstance inst, int dpIndex, String text) {
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dpIndex);
        String label = "Texte de §a" + dp.key + "§r (placeholder inséré)";
        String hint = "§7Placeholder ajouté à la fin.\n" +
                "§7Modifiez ou cliquez Valider pour garder.";
        showTextDialog(p, label, hint, text, 1024, true, new Session(
                SessionKind.MULTILINE, inst, dpIndex, "text", null));
    }

    /**
     * Open a single-line text dialog for manual entry of a legacy-hook PAPI
     * placeholder name. This replaces the chat conversation that used to
     * handle slot 31 in PlaceholderBrowser. The dialog is consistent with
     * the rest of the plugin's input UI (everything else uses Paper
     * Dialogs — only this one legacy flow used to drop the user back to
     * the chat prompt).
     *
     * <p>On Save, the handler looks up the PlaceholderBrowser in
     * {@link #PLACEHOLDER_BROWSERS}, calls
     * {@link com.hudboard.menu.PlaceholderBrowser#insertPlaceholderFromDialog(Player, String)},
     * and re-opens the browser.</p>
     *
     * @param browser the live browser to re-open after the dialog submits
     */
    public void openManualPlaceholderDialog(Player p,
            com.hudboard.menu.PlaceholderBrowser browser,
            InfoPanelInstance inst, int dpIndex, String expansionId) {
        if (inst == null) return;
        String label = "Placeholder manuel pour §a" + expansionId;
        String hint = "§7Nom du placeholder (sans les §e%§7) pour l'expansion §b"
                + expansionId + "§7.\n" +
                "§7Exemples :\n" +
                "§f  armor_material_helmet\n" +
                "§f  vault_balance\n" +
                "§f  player_health";
        Session session = new Session(SessionKind.MANUALPLACEHOLDER, inst, dpIndex,
                expansionId, null);
        PLACEHOLDER_BROWSERS.put(p.getUniqueId(), browser);
        showTextDialog(p, label, hint, "", 64, false, session);
    }

    // ---------------------------------------------------------------------
    // Dialog builders
    // ---------------------------------------------------------------------

    private void showTextDialog(Player p, String label, String hint, String initial,
                                int maxLen, boolean multiline, Session session) {
        showTextDialog(p, label, hint, initial, maxLen, multiline, session, null);
    }

    /**
     * Overload with an optional live-preview line. The preview is added as
     * the LAST body paragraph, separated from the hint by an empty
     * paragraph so the admin can see both the rules AND what their
     * current value would look like applied to a sample phrase.
     *
     * <p>v1.4.1: Paper's {@code TextDialogInput.Builder} doesn't expose a
     * keystroke callback (the API is read-only on the input change), so
     * the preview is computed once per dialog open. After Save, the
     * caller typically re-opens the dialog so the preview reflects the
     * new value. That is enough to answer "what will my text look like
     * before I commit it?" for a color tag — the user types, Save
     * (Commit), then re-opens and sees the rendered preview.</p>
     */
    private void showTextDialog(Player p, String label, String hint, String initial,
                                int maxLen, boolean multiline, Session session,
                                Component preview) {
        SESSIONS.put(p.getUniqueId(), session);
        Component title = mm("§6" + label);
        List<DialogBody> bodies = new ArrayList<>();
        bodies.add(DialogBody.plainMessage(mm(hint)));
        if (preview != null) {
            // Spacer — Paper renders the message as a separate line
            // so the preview visually sits below the rules.
            bodies.add(DialogBody.plainMessage(Component.empty()));
            bodies.add(DialogBody.plainMessage(preview));
        }
        DialogBase.Builder base = DialogBase.builder(title)
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(bodies);
        // Build the text input (use the builder so we can set multiline)
        TextDialogInput.Builder textBuilder = DialogInput.text("value", mm("§eValeur"))
                .initial(initial == null ? "" : initial)
                .maxLength(maxLen)
                .width(400);
        if (multiline) {
            textBuilder = textBuilder.multiline(TextDialogInput.MultilineOptions.create(6, 80));
        }
        DialogInput textInput = textBuilder.build();
        base.inputs(List.of(textInput));
        // Buttons
        ActionButton save = ActionButton.create(
                mm("§a§lValider"),
                null,
                150,
                DialogAction.customClick(Key.key(NS, "dialog/save"), null)
        );
        ActionButton cancel = ActionButton.create(
                mm("§c§lAnnuler"),
                null,
                150,
                DialogAction.customClick(Key.key(NS, "dialog/cancel"), null)
        );
        DialogType type = DialogType.confirmation(save, cancel);
        Dialog dialog = Dialog.create(b -> b.empty().base(base.build()).type(type));
        p.showDialog(dialog);
    }

    /**
     * Build the "preview" body paragraph for a color-tag dialog. Renders
     * the supplied tag (or "no tag" when blank) applied to a sample
     * phrase via MiniMessage, so the admin sees the actual colour before
     * committing. Catches the {@code TagNotFound} / parse error so we
     * display "(invalid tag — will not be applied)" instead of letting
     * the dialog throw.
     */
    private Component buildColorPreview(String tag, String sampleText) {
        String currentDisplay = (tag == null || tag.isBlank()) ? "(none)" : tag;
        Component previewSample;
        if (tag == null || tag.isBlank()) {
            previewSample = Component.text(sampleText, NamedTextColor.WHITE);
        } else {
            try {
                previewSample = mm(tag + sampleText + "</" + extractFirstTagName(tag) + ">");
            } catch (Throwable t) {
                previewSample = Component.text("(" + currentDisplay + " — invalid tag)", NamedTextColor.RED);
            }
        }
        return Component.text("Preview → ", NamedTextColor.GRAY)
                .append(Component.text(currentDisplay, NamedTextColor.YELLOW))
                .append(Component.text(" applied to \"", NamedTextColor.GRAY))
                .append(previewSample)
                .append(Component.text("\"", NamedTextColor.GRAY));
    }

    /** Helper for {@link #buildColorPreview}: extract the opening tag's
     *  name so we know what closing tag to append. Returns "" if the
     *  input isn't a well-formed opening tag (e.g. {@code <#FF8800>}). */
    private static String extractFirstTagName(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (!t.startsWith("<") || !t.endsWith(">")) return "";
        String inner = t.substring(1, t.length() - 1);
        // <red>         → "red"
        // <#FF8800>     → "" (hex — no closing tag needed because MM self-closes)
        // <gradient:red:blue> → "gradient" (with :blue still open, so MM won't close properly anyway)
        if (inner.startsWith("#") || inner.contains(":")) return "";
        return inner;
    }

    /**
     * Open a dedicated dialog that contains ALL animation parameters in one
     * place: type (single-choice), speed (number), and color (text input for
     * any MiniMessage tag). This replaces the old "anim-ms" / "anim-color"
     * buttons that cluttered the main editor with two extra slots.
     */
    public void openAnimationEditor(Player p, InfoPanelInstance inst, int dpIndex) {
        if (dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) return;
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dpIndex);
        SESSIONS.put(p.getUniqueId(), new Session(SessionKind.ANIMATION, inst, dpIndex, "animation", null));
        // 5 visually-distinct animations. All positional (move the text) except
        // "pulse" which scales — none of them modulate color only like the old
        // pulse/breathe/blink/rainbow did.
        String[] choices = { "none", "bob", "scroll", "typewriter", "glitch", "pulse" };
        String[] labels   = {
                "§7aucune (statique)",
                "§dbob — oscillation verticale (±3px)",
                "§dscroll — bandeau défilant",
                "§dtypewriter — apparition lettre par lettre",
                "§dglitch — téléportations aléatoires (±4px / 80ms)",
                "§dpulse — respiration (1.0 ↔ 1.12)"
        };
        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
        for (int i = 0; i < choices.length; i++) {
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    choices[i],
                    mm(labels[i]),
                    choices[i].equals((dp.animation == null ? "none" : dp.animation.toLowerCase()))
            ));
        }
        DialogInput animInput = DialogInput.singleOption("anim", mm("§eType d'animation"), options).build();
        DialogInput speedInput = DialogInput.text("speed", mm("§eVitesse (ms par cycle)"))
                .initial(String.valueOf(dp.animMs))
                .maxLength(8).width(200).build();
        DialogInput colorInput = DialogInput.text("color", mm("§eCouleur secondaire (tag MiniMessage)"))
                .initial(colorToMiniMessage(dp.animColor))
                .maxLength(64).width(400).build();
        DialogBase base = DialogBase.builder(mm("§6Animation pour §a" + dp.key))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(List.of(DialogBody.plainMessage(mm(
                        "§75 animations : §fbob §7(oscillation), §fscroll §7(bandeau), §ftypewriter §7(lettre par lettre), §fglitch §7(aléatoire), §fpulse §7(respiration).\n" +
                        "§7Vitesse = ms par cycle complet (ex. §f1500 §7= 1,5s).\n" +
                        "§7Couleur = optionnelle (utilisée par typewriter, ignorée par les autres)."))))
                .inputs(List.of(animInput, speedInput, colorInput))
                .build();
        ActionButton save = ActionButton.create(
                mm("§a§lValider"),
                null,
                150,
                DialogAction.customClick(Key.key(NS, "dialog/save"), null)
        );
        ActionButton cancel = ActionButton.create(
                mm("§c§lAnnuler"),
                null,
                150,
                DialogAction.customClick(Key.key(NS, "dialog/cancel"), null)
        );
        DialogType type = DialogType.confirmation(save, cancel);
        Dialog dialog = Dialog.create(b -> b.empty().base(base).type(type));
        p.showDialog(dialog);
    }

    /** Color picker for the animation. Each option is a NamedTextColor
     *  plus a "custom" entry that opens a hex input. */
    private void showColorDialog(Player p, String dataPointKey, String current, Session session) {
        SESSIONS.put(p.getUniqueId(), session);
        Component title = mm("§6Couleur d'animation pour §a" + dataPointKey);
        String[][] choices = {
                { "white",       "§fblanc" },
                { "red",         "§crouge" },
                { "blue",        "§9bleu" },
                { "green",       "§avert" },
                { "gold",        "§6or" },
                { "yellow",      "§ejaune" },
                { "aqua",        "§bcyan" },
                { "gray",        "§7gris" },
                { "black",       "§0noir" },
                { "custom",      "§d#RRGGBB..." }
        };
        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
        for (String[] c : choices) {
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    c[0], mm(c[1]), c[0].equals(current)
            ));
        }
        DialogInput input = DialogInput.singleOption("color", mm("§eCouleur"), options).build();
        DialogBase base = DialogBase.builder(title)
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(List.of(DialogBody.plainMessage(mm("§7Couleur secondaire pour les animations (pulse, etc.)."))))
                .inputs(List.of(input))
                .build();
        ActionButton save = ActionButton.create(
                mm("§a§lValider"),
                null,
                150,
                DialogAction.customClick(Key.key(NS, "dialog/save"), null)
        );
        ActionButton cancel = ActionButton.create(
                mm("§c§lAnnuler"),
                null,
                150,
                DialogAction.customClick(Key.key(NS, "dialog/cancel"), null)
        );
        DialogType type = DialogType.confirmation(save, cancel);
        Dialog dialog = Dialog.create(b -> b.empty().base(base).type(type));
        p.showDialog(dialog);
    }

    // ---------------------------------------------------------------------
    // Event handler
    // ---------------------------------------------------------------------

    @EventHandler
    public void onDialogClick(PlayerCustomClickEvent e) {
        Key id = e.getIdentifier();
        String path = id.value();
        PlayerGameConnection conn = e.getCommonConnection() instanceof PlayerGameConnection pgc ? pgc : null;
        if (conn == null) return;
        Player p = conn.getPlayer();
        UUID uuid = p.getUniqueId();
        Session session = SESSIONS.remove(uuid);
        if (path.equals("dialog/cancel")) {
            p.sendMessage(mm("§eAnnulé."));
            p.closeDialog();
            reopenEditor(p, session);
            return;
        }
        // v2.3.0: "Add gradient" button — close the text dialog and open
        // a small sub-dialog asking for the two colors. After save, the
        // text dialog re-opens with the gradient tag inserted.
        if (path.equals("dialog/gradient")) {
            // Re-insert the session so the gradient dialog can resume it.
            SESSIONS.put(uuid, session);
            openGradientHelper(p, session);
            return;
        }
        if (path.equals("dialog/save")) {
            if (session == null) {
                p.closeDialog();
                return;
            }
            var view = e.getDialogResponseView();
            if (view == null) {
                p.closeDialog();
                reopenEditor(p, session);
                return;
            }
            try {
                applyResponse(p, session, view);
            } catch (Throwable t) {
                plugin.getLogger().warning("[HudBoard] Dialog apply failed: " + t);
                p.sendMessage(mm("§cÉchec : " + t.getMessage()));
            }
            p.closeDialog();
            reopenEditor(p, session);
        }
    }

    private void applyResponse(Player p, Session s,
                               DialogResponseView view) {
        Lang lang = plugin.getLang();
        switch (s.kind()) {
            case TEXT -> {
                String value = view.getText("value");
                if (value == null) return;
                s.inst().snapshotForUndo();
                applyTextProperty(s.inst(), s.dpIndex(), s.property(), value);
                p.sendMessage(lang.get("gui.saved"));
                plugin.getPanelManager().invalidate(s.inst());
                plugin.getPanelManager().saveProfileToDisk(s.inst().profile);
            }
            case MULTILINE -> {
                String value = view.getText("value");
                if (value == null || value.isBlank()) return;
                applyTextProperty(s.inst(), s.dpIndex(), s.property(), value);
                p.sendMessage(lang.get("gui.saved"));
                plugin.getPanelManager().invalidate(s.inst());
                plugin.getPanelManager().saveProfileToDisk(s.inst().profile);
            }
            case ANIMATION -> {
                // Full animation editor: type + speed + color in one shot.
                // Replaces the old "anim-ms" / "anim-color" buttons that lived
                // in the main data point editor.
                String animType = view.getText("anim");
                if (animType == null) return;
                applyTextProperty(s.inst(), s.dpIndex(), "animation",
                        "none".equals(animType) ? "" : animType);
                Double speed = null;
                String speedStr = view.getText("speed");
                if (speedStr != null && !speedStr.isBlank()) {
                    try { speed = Double.parseDouble(speedStr.trim()); }
                    catch (NumberFormatException ignored) {}
                }
                if (speed != null) {
                    applyAnimSpeed(s.inst(), s.dpIndex(), speed.longValue());
                }
                String colorStr = view.getText("color");
                if (colorStr != null) {
                    int rgb = colorStr.isBlank() ? 0xFFFFFF
                            : parseMiniMessageColor(colorStr.trim());
                    applyAnimColor(s.inst(), s.dpIndex(), rgb);
                }
                p.sendMessage(lang.get("gui.saved"));
                plugin.getPanelManager().invalidate(s.inst());
                plugin.getPanelManager().saveProfileToDisk(s.inst().profile);
            }
            case ANIMCOLOR -> {
                String value = view.getText("value");
                if (value == null || value.isBlank()) {
                    // empty = reset to default (white)
                    applyAnimColor(s.inst(), s.dpIndex(), 0xFFFFFF);
                } else {
                    // Accept MiniMessage tags: <red>, <#FF8800>, <gradient:red:blue>
                    int rgb = parseMiniMessageColor(value.trim());
                    applyAnimColor(s.inst(), s.dpIndex(), rgb);
                }
                p.sendMessage(lang.get("gui.saved"));
                plugin.getPanelManager().invalidate(s.inst());
                plugin.getPanelManager().saveProfileToDisk(s.inst().profile);
            }
            case BASECOLOR -> {
                // Base color is stored as a raw MiniMessage tag string, NOT as
                // an int. This lets the user keep full gradients / hex /
                // named colors in the data point, and even embed other tags
                // inside the text (e.g. text="hello <white>world</white>")
                // because the renderer just prepends this string.
                String value = view.getText("value");
                if (value == null) value = "";
                value = value.trim();
                // Wrap bare hex / named values so they parse as a tag.
                if (!value.isEmpty() && !value.startsWith("<")) {
                    // "FF8800" or "red" → "<red>" / "<#FF8800>"
                    if (value.startsWith("#")) value = "<" + value + ">";
                    else if (value.matches("[0-9A-Fa-f]{6}")) value = "<#" + value + ">";
                    else value = "<" + value + ">";
                }
                if (value.isBlank()) value = null;
                applyBaseColor(s.inst(), s.dpIndex(), value);
                p.sendMessage(lang.get("gui.saved"));
                plugin.getPanelManager().invalidate(s.inst());
                plugin.getPanelManager().saveProfileToDisk(s.inst().profile);
            }
            case BULK_BASECOLOR -> {
                // Phase 2.2: apply one base-color tag to every data point in
                // the panel. The same auto-wrap rules as BASECOLOR so admins
                // can type "red" or "#FF8800" instead of "<red>".
                String value = view.getText("value");
                if (value == null) value = "";
                value = value.trim();
                if (!value.isEmpty() && !value.startsWith("<")) {
                    if (value.startsWith("#")) value = "<" + value + ">";
                    else if (value.matches("[0-9A-Fa-f]{6}")) value = "<#" + value + ">";
                    else value = "<" + value + ">";
                }
                if (value.isBlank()) value = null;
                int n = applyBulkBaseColor(s.inst(), value);
                p.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                        "<green>Applied <gold>" + (value == null ? "(cleared)" : value)
                                + "</gold> as base-color to <gold>" + n
                                + "</gold> data point" + (n > 1 ? "s" : "") + ".</green>"));
                plugin.getPanelManager().invalidate(s.inst());
                plugin.getPanelManager().saveProfileToDisk(s.inst().profile);
            }
            case ADDDATAPOINT -> {
                String value = view.getText("value");
                if (value == null) return;
                s.inst().snapshotForUndo();
                boolean ok = applyAddDataPoint(s.inst(), value);
                p.sendMessage(ok
                        ? lang.get("gui.saved")
                        : Component.text("Failed to parse. Use: key x=N y=N size=N text=\"...\"",
                                NamedTextColor.RED));
                plugin.getPanelManager().invalidate(s.inst());
                plugin.getPanelManager().saveProfileToDisk(s.inst().profile);
            }
            case RENAMEPANEL -> {
                String value = view.getText("value");
                if (value == null || value.isBlank()) return;
                plugin.getPanelManager().renameInstance(s.inst().name, value.trim());
                p.sendMessage(lang.get("gui.saved"));
            }
            case MANUALPLACEHOLDER -> {
                // Manual placeholder entry from PlaceholderBrowser slot 31.
                // The dialog already closed; the PlaceholderBrowser is in
                // PLACEHOLDER_BROWSERS and re-opens itself once the user
                // submits (or cancels). We just validate the input here.
                String value = view.getText("value");
                if (value == null) return;
                String trimmed = value.trim();
                if (trimmed.isEmpty()) return;
                if (trimmed.equalsIgnoreCase("cancel")) return;
                // Surrounding % is optional — strip if present so the user
                // can paste "%vault_balance%" or "vault_balance" either way.
                if (trimmed.startsWith("%") && trimmed.endsWith("%") && trimmed.length() >= 2) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1);
                }
                final String key = trimmed.toLowerCase(java.util.Locale.ROOT);
                com.hudboard.menu.PlaceholderBrowser browser =
                        PLACEHOLDER_BROWSERS.remove(p.getUniqueId());
                if (browser != null) {
                    browser.insertPlaceholderFromDialog(p, key);
                }
            }
            case GRADIENT_HELPER -> {
                // v2.3.0: build <gradient:c1:c2>...</gradient> from the
                // two color inputs and re-open the parent MULTILINE dialog
                // with the tag appended at the end of the current text.
                String c1 = view.getText("c1");
                String c2 = view.getText("c2");
                if (c1 == null || c2 == null || c1.isBlank() || c2.isBlank()) {
                    p.sendMessage(mm("§cLes deux couleurs sont requises."));
                    return;
                }
                c1 = c1.trim();
                c2 = c2.trim();
                // Build a minimal "current text" from the parent DataPoint.
                InfoPanelInstance parentInst = s.inst();
                int dpIdx = s.dpIndex();
                if (parentInst == null || dpIdx < 0 || dpIdx >= parentInst.profile.dataPoints.size()) {
                    p.sendMessage(mm("§cPoint de données perdu — rouvrez l'éditeur."));
                    return;
                }
                InfoPanel.DataPoint dp = parentInst.profile.dataPoints.get(dpIdx);
                String currentText = dp.text == null ? "" : dp.text;
                String sample = " sample ";
                String gradientTag = "<gradient:" + c1 + ":" + c2 + ">" + sample + "</gradient>";
                String newText = currentText + gradientTag;
                // Persist and re-open the multiline dialog (which carries
                // the live preview based on the new text).
                applyTextChange(parentInst, dpIdx, newText);
                p.sendMessage(mm("§aGradient inserted. Preview updated."));
                // Re-open the multiline editor so the admin sees the
                // gradient appear in the text input AND in the preview.
                Bukkit.getScheduler().runTask(plugin, () -> openMultilineText(p, parentInst, dpIdx));
            }
        }
    }

    /** v2.3.0: helper for the GRADIENT_HELPER path. Sets the data-point
     *  text and saves the profile (mirrors what dialog/save does for
     *  MULTILINE, but bypasses the dialog response view). */
    private void applyTextChange(InfoPanelInstance inst, int dpIndex, String newText) {
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dpIndex);
        dp.text = newText;
        plugin.getPanelManager().invalidate(inst);
        plugin.getPanelManager().saveProfileToDisk(inst.profile);
    }

    private void reopenEditor(Player p, Session s) {
        if (s == null) return;
        // Force a render of all tiles for the panel so the user sees their
        // change IMMEDIATELY (the cache is cleared, but we also push the
        // new bytes via NMS direct so the client doesn't have to wait for
        // its next map render tick).
        if (s.inst() != null) {
            var inst = plugin.getPanelManager().get(s.inst().name, true);
            if (inst != null) {
                for (org.bukkit.map.MapView v : inst.views) {
                    if (v == null) continue;
                    for (var r : v.getRenderers()) {
                        if (r instanceof com.hudboard.panel.InfoPanelRenderer ipr) {
                            ipr.forceFrameToViewers();
                        }
                    }
                }
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (s.kind()) {
                case TEXT, MULTILINE, ANIMATION, BASECOLOR, ANIMCOLOR -> {
                    var inst = plugin.getPanelManager().get(s.inst().name, true);
                    if (inst != null) {
                        new DataPointEditorMenu(plugin, inst, s.dpIndex()).open(p);
                    }
                }
                case ADDDATAPOINT -> {
                    var inst = plugin.getPanelManager().get(s.inst().name, true);
                    if (inst != null) {
                        new PanelEditMenu(plugin, inst).open(p);
                    }
                }
                case RENAMEPANEL -> {
                    var inst = plugin.getPanelManager().get(s.inst().name, true);
                    if (inst != null) {
                        new PanelEditMenu(plugin, inst).open(p);
                    }
                }
                case BULK_BASECOLOR -> {
                    // Phase 2.2: re-open the panel editor so the admin sees
                    // the per-data-point rows reflect the new color.
                    var inst = plugin.getPanelManager().get(s.inst().name, true);
                    if (inst != null) {
                        new PanelEditMenu(plugin, inst).open(p);
                    }
                }
                case MANUALPLACEHOLDER -> {
                    // Re-open the PlaceholderBrowser the user was in. The
                    // browser itself handles the actual insert via
                    // insertPlaceholderFromDialog on the Save path; on
                    // cancel we just re-open so the user can try again or
                    // pick a different action.
                    var browser = PLACEHOLDER_BROWSERS.get(p.getUniqueId());
                    if (browser != null) {
                        browser.open(p);
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Apply helpers
    // ---------------------------------------------------------------------

    private void applyTextProperty(InfoPanelInstance inst, int dpIndex, String property, String value) {
        if (dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) return;
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dpIndex);
        try {
            switch (property) {
                case "x" -> { dp.x = Integer.parseInt(value.trim()); }
                case "y" -> { dp.y = Integer.parseInt(value.trim()); }
                case "tile-x" -> { dp.tileX = Integer.parseInt(value.trim()); }
                case "tile-y" -> { dp.tileY = Integer.parseInt(value.trim()); }
                case "size" -> { dp.size = Integer.parseInt(value.trim()); }
                case "anim-ms" -> { dp.animMs = Long.parseLong(value.trim()); }
                case "text" -> { dp.text = value; }
                case "animation" -> {
                    if (value.isEmpty() || value.equalsIgnoreCase("none")) dp.animation = null;
                    else dp.animation = value.toLowerCase();
                }
            }
        } catch (NumberFormatException ignored) {}
    }

    private void applyAnimColor(InfoPanelInstance inst, int dpIndex, int rgb) {
        if (dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) return;
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dpIndex);
        dp.animColor = (0xFF << 24) | (rgb & 0xFFFFFF);
    }

    /** Set the animation cycle duration in milliseconds (clamped to a sane range). */
    private void applyAnimSpeed(InfoPanelInstance inst, int dpIndex, long ms) {
        if (dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) return;
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dpIndex);
        dp.animMs = Math.max(200L, Math.min(60_000L, ms));
    }

    /** Set the data point's base text color (a MiniMessage tag like <red>,
     *  <#FF8800>, or <gradient:#CB0DFC:#0EEB7D>). Pass null/blank to clear
     *  and fall back to the legacy int color field. */
    private void applyBaseColor(InfoPanelInstance inst, int dpIndex, String miniTag) {
        if (dpIndex < 0 || dpIndex >= inst.profile.dataPoints.size()) return;
        InfoPanel.DataPoint dp = inst.profile.dataPoints.get(dpIndex);
        dp.baseColor = (miniTag == null || miniTag.isBlank()) ? null : miniTag;
    }

    /**
     * Phase 2.2 — apply the same MiniMessage base-color tag to every data
     * point of the panel. Returns the number of data points touched.
     * {@code miniTag == null} clears the base-color (revert to the legacy
     * int color set on the data point, which is usually default white).
     */
    private int applyBulkBaseColor(InfoPanelInstance inst, String miniTag) {
        if (inst == null) return 0;
        String normalized = (miniTag == null || miniTag.isBlank()) ? null : miniTag;
        int n = 0;
        for (InfoPanel.DataPoint dp : inst.profile.dataPoints) {
            dp.baseColor = normalized;
            n++;
        }
        return n;
    }

    /** Map a NamedTextColor name to its RGB int (0xRRGGBB). Returns -1
     *  for "custom" (the caller parses the hex themselves). */
    private static int colorNameToRgb(String name) {
        return switch (name.toLowerCase()) {
            case "white"  -> 0xFFFFFF;
            case "red"    -> 0xFF5555;
            case "blue"   -> 0x5555FF;
            case "green"  -> 0x55FF55;
            case "gold"   -> 0xFFAA00;
            case "yellow" -> 0xFFFF55;
            case "aqua"   -> 0x55FFFF;
            case "gray"   -> 0xAAAAAA;
            case "black"  -> 0x000000;
            default       -> -1;
        };
    }

    private static String colorToName(int argb) {
        int rgb = argb & 0xFFFFFF;
        if (rgb == 0xFFFFFF) return "white";
        if (rgb == 0xFF5555) return "red";
        if (rgb == 0x5555FF) return "blue";
        if (rgb == 0x55FF55) return "green";
        if (rgb == 0xFFAA00) return "gold";
        if (rgb == 0xFFFF55) return "yellow";
        if (rgb == 0x55FFFF) return "aqua";
        if (rgb == 0xAAAAAA) return "gray";
        if (rgb == 0x000000) return "black";
        return "custom";
    }

    /** Convert an ARGB int to a MiniMessage color tag (named color or hex). */
    private static String colorToMiniMessage(int argb) {
        int rgb = argb & 0xFFFFFF;
        // Map to a known MiniMessage named color
        if (rgb == 0xFFFFFF) return "<white>";
        if (rgb == 0xFF5555) return "<red>";
        if (rgb == 0x5555FF) return "<blue>";
        if (rgb == 0x55FF55) return "<green>";
        if (rgb == 0xFFAA00) return "<gold>";
        if (rgb == 0xFFFF55) return "<yellow>";
        if (rgb == 0x55FFFF) return "<aqua>";
        if (rgb == 0xAAAAAA) return "<gray>";
        if (rgb == 0x000000) return "<black>";
        // Fallback: hex tag
        return "<#" + String.format("%06X", rgb) + ">";
    }

    /** Parse a MiniMessage color tag to a 0xRRGGBB int. Returns white on error. */
    public static int parseMiniMessageColor(String s) {
        // Strip the surrounding <>
        if (s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length() - 1);
        // Hex: #RRGGBB or #RGB
        if (s.startsWith("#")) {
            try { return Integer.parseInt(s.substring(1), 16) & 0xFFFFFF; }
            catch (NumberFormatException e) { return 0xFFFFFF; }
        }
        // Gradient: gradient:color1:color2 — pick the first color
        if (s.toLowerCase().startsWith("gradient:")) {
            String[] parts = s.split(":");
            if (parts.length >= 2) return parseMiniMessageColor(parts[1]);
        }
        // Named
        return switch (s.toLowerCase()) {
            case "white"  -> 0xFFFFFF;
            case "red"    -> 0xFF5555;
            case "blue"   -> 0x5555FF;
            case "green"  -> 0x55FF55;
            case "gold"   -> 0xFFAA00;
            case "yellow" -> 0xFFFF55;
            case "aqua"   -> 0x55FFFF;
            case "gray", "grey" -> 0xAAAAAA;
            case "black"  -> 0x000000;
            default       -> 0xFFFFFF;
        };
    }

    private boolean applyAddDataPoint(InfoPanelInstance inst, String text) {
        // Format: "key x=N y=N size=N text=\"...\""
        String[] tokens = text.split("\\s+", 2);
        if (tokens.length < 1) return false;
        String key = tokens[0].trim();
        if (key.isEmpty()) return false;
        InfoPanel.DataPoint dp = new InfoPanel.DataPoint();
        dp.key = key;
        dp.text = " ";
        if (tokens.length == 2) {
            String props = tokens[1];
            for (String part : props.split("\\s+(?=\\w+=)")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String k = part.substring(0, eq);
                String v = part.substring(eq + 1);
                if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                try {
                    switch (k) {
                        case "x" -> dp.x = Integer.parseInt(v);
                        case "y" -> dp.y = Integer.parseInt(v);
                        case "tile-x" -> dp.tileX = Integer.parseInt(v);
                        case "tile-y" -> dp.tileY = Integer.parseInt(v);
                        case "size" -> dp.size = Integer.parseInt(v);
                        case "text" -> dp.text = v;
                        case "anim" -> dp.animation = v;
                        case "anim-ms" -> dp.animMs = Long.parseLong(v);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        inst.profile.dataPoints.add(dp);
        return true;
    }

    private Object getPropertyValue(InfoPanel.DataPoint dp, String property) {
        return switch (property) {
            case "x" -> dp.x;
            case "y" -> dp.y;
            case "tile-x" -> dp.tileX;
            case "tile-y" -> dp.tileY;
            case "size" -> dp.size;
            case "anim-ms" -> dp.animMs;
            case "text" -> dp.text == null ? " " : dp.text;
            case "animation" -> dp.animation == null ? "none" : dp.animation;
            default -> "";
        };
    }

    private String hintForProperty(String property) {
        return switch (property) {
            case "x", "y" -> "§7Position sur la tuile (0-127).\n§70 = haut-gauche, 127 = bas-droite.";
            case "tile-x", "tile-y" -> "§7Quelle tuile (commence à 0).\n§70 = première tuile, 1 = deuxième…";
            case "size" -> "§7Taille de la police en pixels.\n§78 = petit, 14 = par défaut, 24 = grand.";
            case "anim-ms" -> "§7Période de l'animation en millisecondes.\n§71000 = 1s, 200 = rapide.";
            case "text" -> "§7Texte affiché sur le panneau.\n§7• Placeholders : §f%server_tps% §7· §f%player_name%…\n§7• MiniMessage : §f<red>...</red> <gradient:red:blue>...</gradient>\n§7• Le texte long déborde sur la tuile suivante.";
            case "animation" -> "§7none / bob / scroll / typewriter / glitch / pulse";
            default -> "";
        };
    }

    // ---------------------------------------------------------------------
    // MiniMessage helpers
    // ---------------------------------------------------------------------

    private static Component mm(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(toMiniMessage(s))
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Convert legacy §-codes to MiniMessage tags so the dialog body and
     * title parse cleanly. We only handle the colors/decorations we use
     * elsewhere in the plugin (§6, §a, §c, §e, §7, §f, §b, §d, §l, etc.).
     * The mapping is intentionally conservative: anything not in the table
     * is left as-is (a literal `§X` won't break parsing because the next
     * char is not a valid hex digit).
     */
    private static String toMiniMessage(String s) {
        // Quick escape: if no §, return as-is
        if (s.indexOf('§') < 0) return s;
        StringBuilder out = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char code = s.charAt(i + 1);
                String tag = switch (code) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    case 'l' -> "<bold>";
                    case 'o' -> "<italic>";
                    case 'n' -> "<underlined>";
                    case 'm' -> "<strikethrough>";
                    case 'k' -> "<obfuscated>";
                    case 'r' -> "<reset>";
                    default  -> null;
                };
                if (tag != null) {
                    out.append(tag);
                    i++; // consume the code char
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
