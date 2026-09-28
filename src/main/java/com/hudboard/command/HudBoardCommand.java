package com.hudboard.command;

import com.hudboard.HudBoardPlugin;
import com.hudboard.menu.PanelEditMenu;
import com.hudboard.panel.InfoPanel;
import com.hudboard.panel.InfoPanelInstance;
import com.hudboard.panel.InfoPanelManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Locale;

public class HudBoardCommand implements CommandExecutor {

    private final HudBoardPlugin plugin;
    private final MiniMessage MM = MiniMessage.miniMessage();

    public HudBoardCommand(HudBoardPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("hudboard.use")) {
            sender.sendMessage(plugin.getLang().get("no-perm"));
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            // --- Core meta ---
            case "help"       -> sendHelp(sender);
            case "info"       -> handleInfo(sender);
            case "preview"    -> handlePreview(sender, args);
            case "stats"      -> handleStats(sender);
            case "reload"     -> handleReload(sender);
            case "debug"      -> handleDebug(sender, args);
            // --- List (profiles / placed) ---
            case "list"       -> handleList(sender);
            case "panels"     -> handlePanels(sender);
            // --- All panel ops go through `panel <subcmd>` ---
            case "panel"      -> handlePanel(sender, args);
            // --- Custom placeholder groups (Phase 2.1) ---
            case "group", "groups" -> handleGroup(sender, args);
            case "manual", "manuals" -> handleManual(sender, args);
            case "template", "templates" -> handleTemplate(sender, args);
            // --- Short aliases for the most common actions (muscle memory) ---
            case "move"       -> handleMove(sender, args);
            case "tp"         -> handleTp(sender, args);
            case "rename"     -> handleRename(sender, args);
            case "remove", "rm" -> handleRemove(sender, args);
            case "nuke"       -> handleNuke(sender, args);
            case "edit"       -> handleEdit(sender, args);
            default -> sender.sendMessage(MM.deserialize("<red>Unknown sub-command: <gold>" + sub + "</gold>. " +
                    "Try <gold>/hudboard help</gold>, or just <gold>/hudboard panel place <name></gold> to get started.</red>"));
        }
        return true;
    }

    /**
     * v2.6.2: when the admin types a profile name (a template) into a command
     * that expects a PLACED-panel name, give them a useful hint: "test is a
     * profile (template). Placed panels for that profile: test-1, test-2…".
     * Without this, admins spent a minute typing "/hudboard tp test" and
     * wondering why nothing happens — because the actual placed name is
     * "test-1" (auto-dedupe at placement time).
     */
    private void hintIfProfileName(CommandSender sender, String typed) {
        var profile = plugin.getPanelManager().get(typed);
        if (profile == null) return;   // truly unknown — leave it alone
        // It's a profile. List its placed panels.
        var placedKeys = plugin.getPanelManager().allPlaced().keySet();
        java.util.List<String> matches = new java.util.ArrayList<>();
        for (String k : placedKeys) {
            var inst = plugin.getPanelManager().get(k, true);
            if (inst != null && typed.equals(inst.profile.id)) matches.add(k);
        }
        sender.sendMessage(MM.deserialize("<yellow><gold>" + typed + "</gold> is a profile (template), not a placed panel.</yellow>"));
        if (matches.isEmpty()) {
            sender.sendMessage(MM.deserialize("<gray>  → No placed panels for this profile. Place it with <gold>/hudboard panel place " + typed + "</gold></gray>"));
        } else {
            java.util.Collections.sort(matches);
            String list = String.join("<gray>, </gray>", matches.stream().map(s -> "<gold>" + s + "</gold>").toList());
            sender.sendMessage(MM.deserialize("<gray>  → Placed panels for this profile: " + list + "</gray>"));
        }
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(MM.deserialize(""));
        s.sendMessage(MM.deserialize("<gradient:#c8aa6e:#785a28><bold>HudBoard v" + plugin.getPluginMeta().getVersion() + " — admin HUD panels</bold></gradient>"));
        s.sendMessage(MM.deserialize(""));
        s.sendMessage(MM.deserialize("<gold>Quick start</gold> <dark_gray>(look at a surface, then type the place command)</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel place <name></gold>     <dark_gray>auto-detects wall / floor / ceiling / in-air</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel create <name> <fmt> <w>x<h></gold>  <dark_gray>create a blank profile</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel edit <name></gold>      <dark_gray>open the editor GUI</dark_gray>"));
        s.sendMessage(MM.deserialize(""));
        s.sendMessage(MM.deserialize("<gold>Manage placed panels</gold> <dark_gray>(<name> = the placed-panel id like info-hub-1)</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard list</gold>                  <dark_gray>list profiles</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panels</gold>                <dark_gray>list placed panels</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel info <name></gold>     <dark_gray>show state of one panel</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel undo <name></gold>      <dark_gray>rollback the last edit (32-deep history)</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel redo <name></gold>      <dark_gray>replay an undone edit</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel repair <name|all></gold> <dark_gray>force-load chunk + re-spawn missing frames</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard preview <name></gold>        <dark_gray>render data points to chat (live preview)</dark_gray>"));
        s.sendMessage(MM.deserialize(""));
        s.sendMessage(MM.deserialize("<gold>Shortcuts</gold> <dark_gray>(top-level ops on placed panels)</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard move <name></gold>            <dark_gray>move to look target</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard tp <name></gold>              <dark_gray>teleport to a panel</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard rename <o> <new></gold>       <dark_gray>rename a placed panel</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard remove <name|all></gold>      <dark_gray>remove a placed panel (or all)</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard nuke [radius]</gold>          <dark_gray>remove every panel within radius</dark_gray>"));
        s.sendMessage(MM.deserialize(""));
        s.sendMessage(MM.deserialize("<gold>Custom placeholders</gold> <dark_gray>(PAPI + curated lists)</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard group <sub></gold>            <dark_gray>list / create / add / remove / delete / export / import</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard manual <sub></gold>           <dark_gray>list / add / remove / clear (per-PAPI-source manual keys)</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard template <sub></gold>        <dark_gray>list / show (player, tps, balance, time, alert, blank)</dark_gray>"));
        s.sendMessage(MM.deserialize(""));
        s.sendMessage(MM.deserialize("<gold>Plugin meta</gold>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard info</gold>      <dark_gray>version + profile count</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard stats</gold>     <dark_gray>global placeholders + render perf</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard reload</gold>    <dark_gray>hot-reload profiles + config</dark_gray>"));
        s.sendMessage(MM.deserialize(" <gray>/<gold>hudboard debug <sub></gold>  <dark_gray>papi · nms · log <level> · purge · scan · fix</dark_gray>"));
        s.sendMessage(MM.deserialize(""));
        s.sendMessage(MM.deserialize("<gold>In-game editing</gold>"));
        s.sendMessage(MM.deserialize(" <gray>Right-click a panel <dark_gray>→</dark_gray> editor GUI</gray>"));
        s.sendMessage(MM.deserialize(" <gray>Click a data point to edit, type the value, hit Save</gray>"));
        s.sendMessage(MM.deserialize(""));
    }

    private void handleList(CommandSender sender) {
        var ids = plugin.getPanelManager().getProfileIds();
        if (ids.isEmpty()) {
            sender.sendMessage(plugin.getLang().get("list.empty"));
            return;
        }
        sender.sendMessage(plugin.getLang().get("list.header", "%count%", String.valueOf(ids.size())));
        for (String id : ids) {
            InfoPanel p = plugin.getPanelManager().get(id);
            sender.sendMessage(MM.deserialize(" <gray>·</gray> <aqua>" + id + "</aqua> <gray>" + p.tilesW + "x" + p.tilesH + "</gray> <dark_gray>(" + p.dataPoints.size() + " data points)</dark_gray>"));
            if (p.description != null && !p.description.isEmpty())
                sender.sendMessage(MM.deserialize("     <dark_gray>" + p.description + "</dark_gray>"));
        }
    }

    private void handlePanels(CommandSender sender) {
        var placed = plugin.getPanelManager().allPlaced();
        if (placed.isEmpty()) { sender.sendMessage(plugin.getLang().get("panels.empty")); return; }
        sender.sendMessage(plugin.getLang().get("panels.header", "%count%", String.valueOf(placed.size())));
        // sort by name for predictable display
        var keys = new java.util.ArrayList<>(placed.keySet());
        java.util.Collections.sort(keys);
        for (String key : keys) {
            var i = placed.get(key);
            sender.sendMessage(MM.deserialize(" <gray>·</gray> <gold>" + key + "</gold> <dark_gray>(<gray>profile: " + i.profile.id + "</gray>, <gold>" + i.profile.tilesW + "x" + i.profile.tilesH + "</gold>)</dark_gray> <dark_gray>@</dark_gray> <gray>" + i.world + " <gold>" + i.x + "</gold>,<gold>" + i.y + "</gold>,<gold>" + i.z + "</gold></gray>"));
        }
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.place")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        if (args.length < 2) { sender.sendMessage(MM.deserialize("<red>Usage: /hudboard remove <name|all></red>")); return; }
        int n;
        if (args[1].equalsIgnoreCase("all")) {
            n = plugin.getPanelManager().removeAll();
            sender.sendMessage(MM.deserialize("<green>Removed <gold>" + n + "</gold> placed panels.</green>"));
        } else {
            if (plugin.getPanelManager().remove(args[1])) {
                sender.sendMessage(plugin.getLang().get("place.removed", "%name%", args[1], "%count%", "?"));
            } else {
                // v2.6.2: friendly hint when admin types a profile name
                // (which is a template, not a placed panel).
                sender.sendMessage(MM.deserialize("<red>No placed panel named <gold>" + args[1] + "</gold>.</red>"));
                hintIfProfileName(sender, args[1]);
            }
        }
        if (sender instanceof Player p) playSound(p, plugin.getConfigManager().soundOnRemove());
    }

    /**
     * Nuke every HudBoard item-frame (tracked, tagged, or orphan) within a radius.
     * /hudboard nuke [radius]
     * Default radius = 1000 blocks (entire loaded world).
     */
    private void handleNuke(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.admin")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        if (!(sender instanceof Player p)) { sender.sendMessage(plugin.getLang().get("no-player")); return; }
        int radius = 1000;
        if (args.length >= 2) {
            try { radius = Integer.parseInt(args[1]); }
            catch (NumberFormatException e) { sender.sendMessage(MM.deserialize("<red>Radius must be a number.</red>")); return; }
        }
        sender.sendMessage(MM.deserialize("<yellow>Wiping all HudBoard panels within <gold>" + radius + "</gold> blocks...</yellow>"));
        int n = plugin.getPanelManager().nuke(radius, 64);
        sender.sendMessage(MM.deserialize("<green>Nuked <gold>" + n + "</gold> item-frames. Done.</green>"));
        playSound(p, plugin.getConfigManager().soundOnRemove());
    }

    /**
     * /hudboard purge-orphans [radius] — remove all HudBoard-tagged
     * item-frames in loaded chunks that have NO entry in placed.yml and
     * NO recoverable profile. Useful for cleaning up ghosts from older
     * plugin versions or interrupted sessions.
     */
    private void handleTp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.admin")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        if (!(sender instanceof Player p)) { sender.sendMessage(plugin.getLang().get("no-player")); return; }
        if (args.length < 2) { sender.sendMessage(MM.deserialize("<red>Usage: /hudboard tp <name></red>")); return; }
        var inst = plugin.getPanelManager().get(args[1], true);
        if (inst == null) { sender.sendMessage(MM.deserialize("<red>No placed panel named <gold>" + args[1] + "</gold>.</red>")); hintIfProfileName(sender, args[1]); return; }
        var w = Bukkit.getWorld(inst.world);
        if (w == null) return;
        p.teleport(new org.bukkit.Location(w, inst.x + 0.5, inst.y + 0.5, inst.z + 0.5));
    }

    /**
     * /hudboard panel <sub> [args]
     *   create <name> <fmt> <w>x<h>   create a new blank profile (image + yml)
     *   place <name>                  place the profile at your look target
     *   edit <name>                   open the in-game editor GUI
     *   remove <name|all>             remove a placed panel
     *   list                          list placed panels
     *   info <name>                   show panel state
     *   move <name>                   move a placed panel
     *   tp <name>                     teleport to a placed panel
     *   rename <o> <new>              rename a placed panel
     *   save <name>                   re-save the yml (manual save)
     */
    private void handlePanel(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MM.deserialize("<gold>HudBoard panel commands</gold>"));
            sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel create <name> <png|gif|jpg> <w>x<h></gold>"));
            sender.sendMessage(MM.deserialize("  <dark_gray>creates a blank profile in plugins/HudBoard/panels/</dark_gray>"));
            sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel place <name></gold>"));
            sender.sendMessage(MM.deserialize("  <dark_gray>place at your look target (auto-centers vertically)</dark_gray>"));
            sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel edit <name></gold>"));
            sender.sendMessage(MM.deserialize("  <dark_gray>open the editor GUI (also via right-click)</dark_gray>"));
            sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel list</gold>"));
            sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel info <name></gold>"));
            sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel undo <name></gold>"));
            sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel redo <name></gold>"));
            sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard panel repair <name|all></gold>"));
            sender.sendMessage(MM.deserialize("<gray>Top-level ops on placed panels (short aliases):</gray>"));
            sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard move <name></gold> · <gray>/<gold>hudboard tp <name></gold> · <gray>/<gold>hudboard rename <o> <new></gold>"));
            sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard edit <name></gold> · <gray>/<gold>hudboard remove <name|all></gold> · <gray>/<gold>hudboard nuke [radius]</gold>"));
            return;
        }
        String sub = args[1].toLowerCase();
        switch (sub) {
            case "create"   -> handlePanelCreate(sender, args);
            case "place"    -> handlePanelPlace(sender, args);
            case "edit"     -> handlePanelEdit(sender, args);
            case "list"     -> handlePanelsList(sender);
            case "info"     -> handlePanelInfo(sender, args);
            case "undo", "redo" -> handlePanelHistory(sender, args);
            case "repair", "fix" -> handlePanelRepair(sender, args);
            // v2.6.1: removed duplicates — these top-level commands still
            // exist (move/tp/rename/edit/remove/rm/save). Use the short form.
            case "move", "tp", "rename", "remove", "rm", "save" ->
                sender.sendMessage(MM.deserialize("<yellow>Use <gold>/hudboard " + sub
                        + (sub.equals("rename") ? " <old> <new>" : (sub.equals("save") ? " <name>" : " <name>"))
                        + "</gold> directly (no <gold>panel</gold> prefix).</yellow>"));
            default -> sender.sendMessage(MM.deserialize("<red>Unknown <gold>/hudboard panel " + sub + "</gold>. Try <gold>/hudboard panel</gold>.</red>"));
        }
    }

    /** /hudboard panel create <name> <png|gif|jpg> <w>x<h> */
    private void handlePanelCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.place")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        if (args.length < 5) {
            sender.sendMessage(MM.deserialize("<red>Usage: /hudboard panel create <name> <png|gif|jpg> <w>x<h></red>"));
            sender.sendMessage(MM.deserialize("<gray>Example: <gold>/hudboard panel create mypanel png 3x3</gold></gray>"));
            sender.sendMessage(MM.deserialize("<gray>         <gold>/hudboard panel create loader gif 2x2</gold></gray>"));
            return;
        }
        String name = args[2];
        String format = args[3].toLowerCase();
        String[] size = args[4].toLowerCase().split("x", 2);
        if (size.length != 2) {
            sender.sendMessage(MM.deserialize("<red>Size must be <gold>WxH</gold> (e.g. <gold>3x3</gold>).</red>"));
            return;
        }
        int tilesW, tilesH;
        try {
            tilesW = Integer.parseInt(size[0]);
            tilesH = Integer.parseInt(size[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(MM.deserialize("<red>Size must be numbers (e.g. <gold>3x3</gold>).</red>"));
            return;
        }
        if (!format.equals("png") && !format.equals("gif") && !format.equals("jpg") && !format.equals("jpeg")) {
            sender.sendMessage(MM.deserialize("<red>Format must be <gold>png, gif, jpg</gold> (or <gold>jpeg</gold>).</red>"));
            return;
        }
        var profile = plugin.getPanelManager().createBlankProfile(name, format, tilesW, tilesH);
        if (profile == null) {
            sender.sendMessage(MM.deserialize("<red>A panel named <gold>" + name + "</gold> already exists, or the size is invalid (1-10).</red>"));
            return;
        }
        sender.sendMessage(MM.deserialize("<green>Created blank panel profile <gold>" + profile.id + "</gold> <dark_gray>(" + tilesW + "x" + tilesH + ", " + format + ")</dark_gray> in <gold>plugins/HudBoard/panels/</gold></green>"));
        sender.sendMessage(MM.deserialize("<gray>Now place it: <gold>/hudboard panel place " + profile.id + "</gold></gray>"));
        sender.sendMessage(MM.deserialize("<gray>Or right-click any existing panel of this profile to edit it.</gray>"));
    }

    /** /hudboard panel place <name> — place at the player's look target. */
    private void handlePanelPlace(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.place")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        if (!(sender instanceof Player p)) { sender.sendMessage(plugin.getLang().get("no-player")); return; }
        if (args.length < 3) { sender.sendMessage(MM.deserialize("<red>Usage: /hudboard panel place <name></red>")); return; }
        String id = args[2];
        var profile = plugin.getPanelManager().get(id);
        if (profile == null) { sender.sendMessage(MM.deserialize("<red>No profile named <gold>" + id + "</gold>. Use <gold>/hudboard panel create</gold> to make one.</red>")); return; }

        // Unified placement: look at what the player is aiming at and pick
        // the right path automatically. No more /hudboard flat, /here, /spawn
        // — just look at the surface and place. This is the single way to
        // put a panel in the world.
        //
        // getTargetBlockFace returns the face of the block the player is
        // looking AT, relative to that block:
        //   face=UP   → player is looking at the TOP of a block = looking DOWN at the floor
        //   face=DOWN → player is looking at the BOTTOM of a block = looking UP at the ceiling
        // (the previous build had these swapped, which made the ceiling case
        //  call placeFlat(ceiling=true) and the floor case call placeFlat(ceiling=false)
        //  correctly via placeFlat BUT with face flipped — the user looking at
        //  the floor got "Need a solid block above your head" because the code
        //  thought they were looking at a ceiling. This version fixes it.)
        org.bukkit.block.Block target = p.getTargetBlockExact(8);
        org.bukkit.block.BlockFace face = target == null ? null : p.getTargetBlockFace(8);
        String name = null;
        String mode = "wall";
        if (target != null && face == org.bukkit.block.BlockFace.DOWN) {
            // Player is looking UP at the bottom of a block → ceiling
            name = plugin.getPanelManager().placeFlat(p, id, null, true);
            mode = "ceiling";
        } else if (target != null && face == org.bukkit.block.BlockFace.UP) {
            // Player is looking DOWN at the top of a block → floor
            name = plugin.getPanelManager().placeFlat(p, id, null, false);
            mode = "floor";
        } else if (target != null && face != null) {
            // Wall (NORTH/SOUTH/EAST/WEST)
            int shiftDown = profile.tilesH / 2;
            org.bukkit.block.Block centered = target.getRelative(org.bukkit.block.BlockFace.DOWN, shiftDown);
            name = plugin.getPanelManager().placeAt(p, id, centered, face, null);
        } else {
            // Nothing in reach → in-air placement 2 blocks ahead of the player,
            // facing them. Same logic as the old /hudboard here.
            org.bukkit.Location eyes = p.getEyeLocation();
            org.bukkit.util.Vector forward = eyes.getDirection().normalize().multiply(2.0);
            org.bukkit.Location airCenter = eyes.clone().add(forward);
            org.bukkit.block.Block inFront = airCenter.getBlock();
            org.bukkit.block.BlockFace airFace = p.getFacing().getOppositeFace();
            name = plugin.getPanelManager().placeAt(p, id, inFront, airFace, null);
            mode = "in-air";
        }
        if (name == null) {
            sender.sendMessage(MM.deserialize("<red>Could not place the panel. <dark_gray>Try a different angle or move closer.</dark_gray></red>"));
            return;
        }
        sender.sendMessage(plugin.getLang().get("place.done", "%name%", name, "%tiles%", String.valueOf(profile.tilesW * profile.tilesH)));
        sender.sendMessage(MM.deserialize("<gray>Placed on <gold>" + mode + "</gold>. " +
                "Right-click to edit, or <gold>/hudboard panel move " + name + "</gold> to relocate.</gray>"));
        playSound(p, plugin.getConfigManager().soundOnPlace());
    }

    /** /hudboard panel edit <name> — opens the editor GUI. */
    private void handlePanelEdit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.edit")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        if (!(sender instanceof Player p)) { sender.sendMessage(plugin.getLang().get("no-player")); return; }
        if (args.length < 3) { sender.sendMessage(MM.deserialize("<red>Usage: /hudboard panel edit <name></red>")); return; }
        var inst = plugin.getPanelManager().get(args[2], true);
        if (inst == null) {
            var profile = plugin.getPanelManager().get(args[2]);
            if (profile == null) { sender.sendMessage(plugin.getLang().get("panel.not-found", "%name%", args[2])); return; }
            sender.sendMessage(MM.deserialize("<yellow>That profile isn't placed anywhere. Use <gold>/hudboard panel place " + profile.id + "</gold> to put it in the world first.</yellow>"));
            return;
        }
        new com.hudboard.menu.PanelEditMenu(plugin, inst).open(p);
    }

    /** /hudboard panel undo|redo <name> — rollback the last data-point edit.
     *  History lives on the InfoPanelInstance (v2.3.0); up to 32 edits deep. */
    private void handlePanelHistory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.admin")) {
            sender.sendMessage(plugin.getLang().get("no-perm"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(MM.deserialize(
                    "<red>Usage: /hudboard panel undo <name> | /hudboard panel redo <name></red>"));
            return;
        }
        com.hudboard.panel.InfoPanelInstance inst =
                plugin.getPanelManager().get(args[2], true);
        if (inst == null) {
            sender.sendMessage(MM.deserialize("<red>Placed panel <gold>" + args[2] + "</gold> not found.</red>"));
            hintIfProfileName(sender, args[2]);
            return;
        }
        boolean ok;
        String verb;
        if (args[1].equalsIgnoreCase("undo")) {
            ok = inst.undo();
            verb = "Undo";
        } else if (args[1].equalsIgnoreCase("redo")) {
            ok = inst.redo();
            verb = "Redo";
        } else {
            sender.sendMessage(MM.deserialize("<red>Unknown history action <gold>"
                    + args[1] + "</gold>. Try undo or redo.</red>"));
            return;
        }
        if (!ok) {
            sender.sendMessage(MM.deserialize("<yellow>Nothing to " + args[1].toLowerCase()
                    + " — history is empty for that panel.</yellow>"));
            return;
        }
        plugin.getPanelManager().invalidate(inst);
        plugin.getPanelManager().saveProfileToDisk(inst.profile);
        sender.sendMessage(MM.deserialize("<green>" + verb
                + " applied to <gold>" + inst.name + "</gold>. "
                + inst.profile.dataPoints.size() + " data points now.</green>"));
        // Re-open the editor menu if the admin was already inside one.
        if (sender instanceof Player p) {
            new PanelEditMenu(plugin, inst).open(p);
        }
    }

    /**
     * /hudboard panel repair [name|all]
     * Force-repair a placed panel (or every panel in the admin's world
     * with "all"). v2.5.0: this used to be only automatic at startup —
     * now admins can trigger it on demand, with proper chunk force-load
     * so it works even when they're not standing on the panel.
     */
    private void handlePanelRepair(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.admin")) {
            sender.sendMessage(plugin.getLang().get("no-perm"));
            return;
        }
        // Determine which panels to repair. If the sender is a Player,
        // we can also force-load chunks around their position so panels
        // within a reasonable radius are eligible (admin doesn't have
        // to walk to each one).
        if (args.length < 3) {
            sender.sendMessage(MM.deserialize(
                    "<red>Usage: /hudboard panel repair <name>|all</red>"));
            return;
        }
        String target = args[2].toLowerCase();
        java.util.List<com.hudboard.panel.InfoPanelInstance> targets = new java.util.ArrayList<>();
        if (target.equals("all")) {
            // Default to the admin's world if they're a player; else
            // "every world". v2.5.0: we also force-load chunks within
            // 64 blocks of the player so panels that were placed far
            // away can be repaired without admin travel.
if (sender instanceof Player p) {
                forceLoadChunksAround(p, 64);
                String wn = p.getWorld().getName();
                for (var inst : plugin.getPanelManager().allPlaced().values()) {
                    if (wn.equals(inst.world)) targets.add(inst);
                }
            } else {
                targets.addAll(plugin.getPanelManager().allPlaced().values());
            }
        } else {
            var inst = plugin.getPanelManager().get(args[2], true);
            if (inst == null) {
                sender.sendMessage(MM.deserialize(
                        "<red>Placed panel <gold>" + args[2] + "</gold> not found.</red>"));
                hintIfProfileName(sender, args[2]);
                return;
            }
            targets.add(inst);
        }
        if (targets.isEmpty()) {
            sender.sendMessage(MM.deserialize(
                    "<yellow>No placed panels to repair in your world.</yellow>"));
            return;
        }
        int fixed = 0;
        for (var inst : targets) {
            if (plugin.getPanelManager().repairPlaced(inst.name)) fixed++;
        }
        sender.sendMessage(MM.deserialize("<green>Repaired <gold>" + fixed
                + "</gold> panel" + (fixed > 1 ? "s" : "") + ".</green>"));
    }

    /** Force-load every chunk in a square of side 2*radius around the
     *  player. Cheap on memory (chunks auto-unload after 30s) and
     *  necessary because the placed-panel retry loop checks
     *  {@code isChunkLoaded()} — without forcing, panels placed 200+
     *  blocks away would stay in the pending queue forever. */
    private static void forceLoadChunksAround(Player p, int radius) {
        org.bukkit.World w = p.getWorld();
        int cx = p.getLocation().getBlockX() >> 4;
        int cz = p.getLocation().getBlockZ() >> 4;
        for (int dx = -radius / 16; dx <= radius / 16; dx++) {
            for (int dz = -radius / 16; dz <= radius / 16; dz++) {
                w.getChunkAt(cx + dx, cz + dz).load(true);
            }
        }
    }

    /** /hudboard panel save <name> — REMOVED in v2.6.1 (auto-save covers it). */

    private void handlePanelsList(CommandSender sender) {
        if (!sender.hasPermission("hudboard.use")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        var placed = plugin.getPanelManager().allPlaced();
        if (placed.isEmpty()) { sender.sendMessage(plugin.getLang().get("panel.list-empty")); return; }
        sender.sendMessage(MM.deserialize("<gold>Placed panels (<aqua>" + placed.size() + "</aqua>):</gold>"));
        for (var e : placed.entrySet()) {
            var inst = e.getValue();
            sender.sendMessage(MM.deserialize(" <gray>·</gray> <gold>" + e.getKey() + "</gold> <dark_gray>(profile=" + inst.profile.id + ", " + inst.profile.tilesW + "x" + inst.profile.tilesH + ", refresh=" + inst.refreshSec + "s)</dark_gray>"));
        }
    }

    private void handlePanelInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.use")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        if (args.length < 3) { sender.sendMessage(MM.deserialize("<red>Usage: /hudboard panel info <name></red>")); return; }
        var inst = plugin.getPanelManager().get(args[2], true);
        if (inst == null) { sender.sendMessage(plugin.getLang().get("panel.not-found", "%name%", args[2])); hintIfProfileName(sender, args[2]); return; }
        sender.sendMessage(MM.deserialize("<gold>Panel <aqua>" + inst.name + "</aqua></gold>"));
        sender.sendMessage(MM.deserialize("  <gray>Profile: <white>" + inst.profile.id + "</white> <dark_gray>(" + inst.profile.tilesW + "x" + inst.profile.tilesH + ")</dark_gray></gray>"));
        sender.sendMessage(MM.deserialize("  <gray>Position: <white>" + inst.world + " " + inst.x + " " + inst.y + " " + inst.z + "</white></gray>"));
        sender.sendMessage(MM.deserialize("  <gray>Face: <white>" + inst.face + "</white></gray>"));
        sender.sendMessage(MM.deserialize("  <gray>Refresh: <white>" + inst.refreshSec + "s</white></gray>"));
        sender.sendMessage(MM.deserialize("  <gray>Data points: <white>" + inst.profile.dataPoints.size() + "</white> <dark_gray>(right-click to edit)</dark_gray></gray>"));
    }

    private void handleMove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.place")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        if (!(sender instanceof Player p)) { sender.sendMessage(plugin.getLang().get("no-player")); return; }
        if (args.length < 2) { sender.sendMessage(MM.deserialize("<red>Usage: /hudboard move <name></red>")); return; }
        var inst = plugin.getPanelManager().get(args[1], true);
        if (inst == null) { sender.sendMessage(MM.deserialize("<red>No placed panel named <gold>" + args[1] + "</gold>.</red>")); hintIfProfileName(sender, args[1]); return; }
        Block target = p.getTargetBlockExact(8);
        if (target == null) { sender.sendMessage(MM.deserialize("<red>Look at a wall.</red>")); return; }
        BlockFace face = p.getTargetBlockFace(8);
        if (face == null) face = BlockFace.NORTH;
        // remove + re-place
        plugin.getPanelManager().remove(args[1]);
        String newName = plugin.getPanelManager().placeAt(p, inst.profile.id, target, face);
        if (newName != null) {
            var ni = plugin.getPanelManager().get(newName, true);
            ni.refreshSec = inst.refreshSec;
            plugin.savePlaced();
            sender.sendMessage(MM.deserialize("<green>Moved panel to new spot as <gold>" + newName + "</gold>.</green>"));
        }
    }

    /**
     * /hudboard setpos <name> — reposition an existing panel in-place
     * (keeps its name, no re-placement). Useful for panels whose stored
     * position drifted (e.g. legacy yml with z=0): aim at the wall where
     * the real item-frames live, run the command, and the panel is
     * moved back without losing its name or settings.
     */
    private void handleRename(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.admin")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        if (args.length < 3) { sender.sendMessage(MM.deserialize("<red>Usage: /hudboard rename <old> <new></red>")); return; }
        var src = plugin.getPanelManager().get(args[1]);
        if (src == null || src.file == null) { sender.sendMessage(MM.deserialize("<red>Unknown profile.</red>")); return; }
        File subdir = src.file.getParentFile();
        String ext = src.file.getName().substring(src.file.getName().lastIndexOf('.'));
        File fromPng = new File(subdir, src.id + ext);
        File toPng = new File(subdir, args[2].toLowerCase() + ext);
        if (!fromPng.exists()) { sender.sendMessage(MM.deserialize("<red>Source image missing.</red>")); return; }
        if (toPng.exists()) { sender.sendMessage(MM.deserialize("<red>Target already exists.</red>")); return; }
        fromPng.renameTo(toPng);
        new File(subdir, src.id + ".yml").renameTo(new File(subdir, args[2].toLowerCase() + ".yml"));
        plugin.getPanelManager().loadAll();
        sender.sendMessage(MM.deserialize("<green>Renamed.</green>"));
    }

    private void handleEdit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.edit")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        if (args.length < 4) { sender.sendMessage(MM.deserialize("<red>Usage: /hudboard edit <profile> <key> <value></red>")); return; }
        var p = plugin.getPanelManager().get(args[1]);
        if (p == null || p.file == null) { sender.sendMessage(MM.deserialize("<red>Unknown profile.</red>")); return; }
        // The yml lives in the same per-format subdir as the image.
        File yml = new File(p.file.getParentFile(), p.id + ".yml");
        org.bukkit.configuration.file.YamlConfiguration cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(yml);
        String key = args[2];
        String value = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
        cfg.set("data-points." + key + ".text", value);
        try { cfg.save(yml); } catch (Exception e) { sender.sendMessage(MM.deserialize("<red>Save failed: " + e.getMessage() + "</red>")); return; }
        plugin.getPanelManager().loadAll();
        sender.sendMessage(plugin.getLang().get("edit.done", "%name%", p.id, "%key%", key, "%value%", value));
    }

    private void handleStats(CommandSender sender) {
        sender.sendMessage(plugin.getLang().get("stats.header"));
        sender.sendMessage(plugin.getLang().get("stats.line", "%key%", "profiles", "%value%", String.valueOf(plugin.getPanelManager().getProfileIds().size())));
        sender.sendMessage(plugin.getLang().get("stats.line", "%key%", "placed", "%value%", String.valueOf(plugin.getPanelManager().allPlaced().size())));
        sender.sendMessage(plugin.getLang().get("stats.line", "%key%", "data-points", "%value%", String.valueOf(plugin.getPanelManager().getProfileIds().stream().mapToInt(id -> plugin.getPanelManager().get(id).dataPoints.size()).sum())));
        sender.sendMessage(plugin.getLang().get("stats.line", "%key%", "papi", "%value%", plugin.getDataManager().hasPapi() ? "<green>hooked</green>" : "<red>not detected</red>"));
    }

    /** Dump the loaded data points of a profile / placed panel for debugging. */
    private void handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.edit")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        if (args.length < 2) {
            // Show all loaded profiles
            sender.sendMessage(MM.deserialize("<gold>Loaded profiles:</gold>"));
            for (String id : plugin.getPanelManager().getProfileIds()) {
                var p = plugin.getPanelManager().get(id);
                sender.sendMessage(MM.deserialize(" <gray>·</gray> <aqua>" + id + "</aqua> <gray>" + p.tilesW + "x" + p.tilesH + "</gray> <dark_gray>data points: " + p.dataPoints.size() + ", user placeholders: " + p.userPlaceholders.size() + "</dark_gray>"));
            }
            sender.sendMessage(MM.deserialize("<gold>Placed panels:</gold>"));
            for (var e : plugin.getPanelManager().allPlaced().entrySet()) {
                var i = e.getValue();
                sender.sendMessage(MM.deserialize(" <gray>·</gray> <gold>" + e.getKey() + "</gold> <dark_gray>(profile=" + i.profile.id + ", dataPoints=" + i.profile.dataPoints.size() + ")</dark_gray>"));
            }
            // NMS diagnostic
            boolean nms = com.hudboard.nms.MapDirectSender.isAvailable();
            String reason = com.hudboard.nms.MapDirectSender.getFailReason();
            sender.sendMessage(MM.deserialize("<gold>NMS direct map sender:</gold> " + (nms ? "<green>available</green>" : "<red>unavailable</red>") + (reason != null ? " <dark_gray>(" + reason + ")</dark_gray>" : "")));
            return;
        }
        if (args[1].equalsIgnoreCase("nms")) {
            boolean nms = com.hudboard.nms.MapDirectSender.isAvailable();
            String reason = com.hudboard.nms.MapDirectSender.getFailReason();
            sender.sendMessage(MM.deserialize("<gold>NMS direct map sender:</gold> " + (nms ? "<green>available</green> <dark_gray>(20Hz animation enabled)</dark_gray>" : "<red>unavailable</red> <dark_gray>(falling back to vanilla map render, slower)</dark_gray>")));
            if (reason != null) sender.sendMessage(MM.deserialize(" <gray>Reason: <white>" + reason + "</white></gray>"));
            return;
        }
        // v2.6.0: runtime log-level toggle. Lets the admin bump the plugin
        // to FINE without restarting — useful for diagnosing without
        // spamming the console forever. Levels: off | warning | info | fine.
        if (args[1].equalsIgnoreCase("log") || args[1].equalsIgnoreCase("loglevel")) {
            if (args.length < 3) {
                sender.sendMessage(MM.deserialize("<gold>Current log level:</gold> <white>"
                        + plugin.getLogger().getLevel().getName() + "</white>"));
                sender.sendMessage(MM.deserialize("<gray>Usage: /hudboard debug log <off|warning|info|fine|all></gray>"));
                return;
            }
            java.util.logging.Level target;
            switch (args[2].toLowerCase()) {
                case "off", "silent", "mute" -> target = java.util.logging.Level.OFF;
                case "warning", "warn" -> target = java.util.logging.Level.WARNING;
                case "info" -> target = java.util.logging.Level.INFO;
                case "fine", "debug" -> target = java.util.logging.Level.FINE;
                case "all", "finer" -> target = java.util.logging.Level.ALL;
                default -> {
                    sender.sendMessage(MM.deserialize("<red>Unknown level.</red> Use <off|warning|info|fine|all>."));
                    return;
                }
            }
            plugin.getLogger().setLevel(target);
            sender.sendMessage(MM.deserialize("<green>Log level set to <gold>"
                    + target.getName() + "</gold>.</green>"));
            return;
        }
        if (args[1].equalsIgnoreCase("papi")) {
            sender.sendMessage(MM.deserialize("<gold>PAPI diagnostic:</gold>"));
            sender.sendMessage(MM.deserialize(" <gray>·</gray> <white>Plugin present:</white> " + (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null ? "<green>yes</green>" : "<red>no</red>")));
            sender.sendMessage(MM.deserialize(" <gray>·</gray> <white>DataManager.hasPapi():</white> " + (plugin.getDataManager().hasPapi() ? "<green>yes</green>" : "<red>no</red>")));
            if (!plugin.getDataManager().hasPapi()) {
                sender.sendMessage(MM.deserialize(" <gray>  → HudBoard couldn't load the PAPI class. Check that PlaceholderAPI 2.10+ is installed and that plugin.yml has `depend: [PlaceholderAPI]`.</gray>"));
                return;
            }
            // Live diagnostic with full logging + a chat dump
            java.util.Set<String> ids = plugin.getDataManager().discoverPapiIdentifiers(true);
            sender.sendMessage(MM.deserialize(" <gray>·</gray> <white>Identifiers discovered:</white> <gold>" + ids.size() + "</gold>"));
            if (ids.isEmpty()) {
                sender.sendMessage(MM.deserialize(" <gray>  → Both modern + legacy PAPI paths returned empty.</gray>"));
                sender.sendMessage(MM.deserialize(" <gray>  → Run <gold>/papi register</gold> first if you have expansions in PlaceholderAPI/expansions/</gray>"));
                sender.sendMessage(MM.deserialize(" <gray>  → If /papi list shows expansions but PAPI returns empty, the JARs may have failed to register (check /papi status)</gray>"));
                sender.sendMessage(MM.deserialize(" <gray>  → See server console for [HudBoard PAPI] log line with sample identifiers</gray>"));
            } else {
                int shown = 0;
                StringBuilder sb = new StringBuilder();
                for (String id : ids) {
                    if (shown++ >= 12) break;
                    if (sb.length() > 0) sb.append("<gray>, </gray>");
                    sb.append("<aqua>").append(id).append("</aqua>");
                }
                sender.sendMessage(MM.deserialize(" <gray>·</gray> <white>First identifiers:</white> " + sb + (ids.size() > 12 ? " <dark_gray>… and " + (ids.size() - 12) + " more</dark_gray>" : "")));
            }
            return;
        }
        var p = plugin.getPanelManager().get(args[1]);
        if (p != null) {
            sender.sendMessage(MM.deserialize("<gold>Profile <aqua>" + p.id + "</aqua> has <gold>" + p.dataPoints.size() + "</gold> data points:</gold>"));
            for (var dp : p.dataPoints) {
                sender.sendMessage(MM.deserialize(" <gray>·</gray> <gold>" + dp.key + "</gold> <dark_gray>tile=(" + dp.tileX + "," + dp.tileY + "), pos=(" + dp.x + "," + dp.y + "), text=</dark_gray><white>" + (dp.text.length() > 60 ? dp.text.substring(0, 60) + "..." : dp.text) + "</white>"));
            }
            return;
        }
        var inst = plugin.getPanelManager().get(args[1], true);
        if (inst != null) {
            sender.sendMessage(MM.deserialize("<gold>Placed panel <aqua>" + inst.name + "</aqua> profile <gold>" + inst.profile.id + "</gold> has <gold>" + inst.profile.dataPoints.size() + "</gold> data points.</gold>"));
            return;
        }
        sender.sendMessage(MM.deserialize("<red>Unknown profile or placed panel: <gold>" + args[1] + "</gold>.</red>"));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("hudboard.reload")) { sender.sendMessage(plugin.getLang().get("no-perm")); return; }
        long t0 = System.currentTimeMillis();
        plugin.reloadAll();
        // Phase 2.1: refresh the list of PAPI identifiers considered reserved
        // by the group manager — the admin may have installed / uninstalled
        // expansions since the last reload, and we don't want a stale
        // "group:server" to be allowed just because the manager thinks
        // nothing is registered yet.
        plugin.refreshGroupReservations();
        sender.sendMessage(plugin.getLang().get("reload.done", "%ms%", String.valueOf(System.currentTimeMillis() - t0), "%panels%", String.valueOf(plugin.getPanelManager().getProfileIds().size()), "%placed%", String.valueOf(plugin.getPanelManager().allPlaced().size())));
    }

    // ---------------------------------------------------------------------
    // Phase 2.1 — Custom placeholder groups
    //
    // /hudboard group list
    // /hudboard group create <name>
    // /hudboard group add <name> <placeholder...>
    // /hudboard group remove <name> <placeholder...>
    // /hudboard group delete <name>
    //
    // Groups are stored in plugins/HudBoard/groups.yml and appear as
    // additional sources ("group:<name>") in the PlaceholderBrowser.
    // ---------------------------------------------------------------------
    private void handleGroup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.admin")) {
            sender.sendMessage(plugin.getLang().get("no-perm"));
            return;
        }
        if (args.length < 2) { sendGroupHelp(sender); return; }
        // Refresh PAPI reservation list so a freshly-installed expansion
        // can't be shadowed by a same-named group.
        plugin.refreshGroupReservations();
        com.hudboard.groups.PlaceholderGroupManager gm = plugin.getGroupManager();
        String sub = args[1].toLowerCase();
        switch (sub) {
            case "list", "ls" -> {
                var all = gm.all();
                if (all.isEmpty()) {
                    sender.sendMessage(MM.deserialize(
                            "<gray>No groups yet. Create one with <gold>/hudboard group create <name></gold>.</gray>"));
                    return;
                }
                sender.sendMessage(MM.deserialize("<gold>Custom groups <gray>(" + all.size() + ")</gray>:</gold>"));
                for (var entry : all.entrySet()) {
                    sender.sendMessage(MM.deserialize("<gold> " + entry.getKey()
                            + "</gold> <gray>(" + entry.getValue().size() + " keys)</gray>"));
                    for (String k : entry.getValue()) {
                        sender.sendMessage(MM.deserialize("  <dark_gray>- <white>%" + k + "%</white></dark_gray>"));
                    }
                }
            }
            case "create", "new" -> {
                if (args.length < 3) {
                    sender.sendMessage(MM.deserialize("<red>Usage: /hudboard group create <name></red>"));
                    return;
                }
                String err = gm.create(args[2], java.util.Collections.emptyList());
                if (err != null) {
                    sender.sendMessage(MM.deserialize("<red>" + err + "</red>"));
                    return;
                }
                sender.sendMessage(MM.deserialize("<green>Group <gold>" + args[2]
                        + "</gold> created. Add placeholders with <yellow>/hudboard group add "
                        + args[2] + " <placeholder></yellow>.</green>"));
            }
            case "add" -> {
                if (args.length < 4) {
                    sender.sendMessage(MM.deserialize(
                            "<red>Usage: /hudboard group add <name> <placeholder...></red>"));
                    return;
                }
                String name = args[2];
                if (!gm.exists(name)) {
                    sender.sendMessage(MM.deserialize("<red>Group <gold>" + name
                            + "</gold> doesn't exist. Create it first.</red>"));
                    return;
                }
                int added = 0;
                for (int i = 3; i < args.length; i++) {
                    if (gm.addKey(name, args[i])) added++;
                }
                if (added > 0) {
                    sender.sendMessage(MM.deserialize("<green>Added <gold>" + added
                            + "</gold> placeholder" + (added > 1 ? "s" : "")
                            + " to group <gold>" + name + "</gold>.</green>"));
                } else {
                    sender.sendMessage(MM.deserialize("<yellow>No new placeholders added (already present or empty).</yellow>"));
                }
            }
            case "remove", "rm" -> {
                if (args.length < 4) {
                    sender.sendMessage(MM.deserialize(
                            "<red>Usage: /hudboard group remove <name> <placeholder...></red>"));
                    return;
                }
                String name = args[2];
                if (!gm.exists(name)) {
                    sender.sendMessage(MM.deserialize("<red>Group <gold>" + name + "</gold> doesn't exist.</red>"));
                    return;
                }
                int removed = 0;
                for (int i = 3; i < args.length; i++) {
                    if (gm.removeKey(name, args[i])) removed++;
                }
                if (removed > 0) {
                    sender.sendMessage(MM.deserialize("<green>Removed <gold>" + removed
                            + "</gold> placeholder" + (removed > 1 ? "s" : "") + " from group <gold>" + name + "</gold>.</green>"));
                } else {
                    sender.sendMessage(MM.deserialize("<yellow>No matching placeholder found in group.</yellow>"));
                }
            }
            case "delete", "del" -> {
                if (args.length < 3) {
                    sender.sendMessage(MM.deserialize("<red>Usage: /hudboard group delete <name></red>"));
                    return;
                }
                if (gm.delete(args[2])) {
                    sender.sendMessage(MM.deserialize("<green>Group <gold>" + args[2] + "</gold> deleted.</green>"));
                } else {
                    sender.sendMessage(MM.deserialize("<red>Group <gold>" + args[2] + "</gold> doesn't exist.</red>"));
                }
            }
            // --- v1.4.1: YAML import / export --------------------------------
            case "export" -> {
                // Dump the YAML to chat. Admins can /copy it from there.
                sender.sendMessage(MM.deserialize("<gold>Custom groups (YAML) — copy below:</gold>"));
                // Don't render the YAML through MiniMessage — the contents
                // contain `<` characters (clickEvent / hoverEvent syntax in
                // YAML, but also accidental MiniMessage tags like `<r>`).
                // The YAML itself is YAML, not chat markup — escape any
                // angle brackets into their entity form by routing through
                // MM with explicit escape. We use the lower-level
                // Component serialiser for safety (YAML may contain
                // arbitrary unicode).
                sender.sendMessage(net.kyori.adventure.text.Component.text(
                        gm.exportYaml(), net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
                sender.sendMessage(MM.deserialize("<gray>Use <gold>/hudboard group import</gold> to load this back on another server.</gray>"));
            }
            case "import" -> {
                // MVP: expects the YAML on the same line OR after.
                // Example: bash $ /hudboard group import <<< "$(cat groups.yml)"
                if (args.length < 3) {
                    sender.sendMessage(MM.deserialize(
                            "<red>Usage: bash $ /hudboard group import <<< \"$(cat groups.yml)\"</red>"));
                    sender.sendMessage(MM.deserialize(
                            "<gray>(single-line YAML on stdin; pipes accepted)</gray>"));
                    return;
                }
                // Reassemble everything past the import token
                StringBuilder sb = new StringBuilder();
                for (int i = 2; i < args.length; i++) {
                    if (i > 2) sb.append(' ');
                    sb.append(args[i]);
                }
                String yaml = sb.toString();
                java.util.List<String> warnings = new java.util.ArrayList<>();
                int added = gm.importYaml(yaml, warnings::add);
                sender.sendMessage(MM.deserialize("<green>Imported <gold>" + added
                        + "</gold> new key" + (added > 1 ? "s" : "") + ".</green>"));
                for (String w : warnings) {
                    sender.sendMessage(MM.deserialize("<yellow>Warning: " + w + "</yellow>"));
                }
            }
            default -> sendGroupHelp(sender);
        }
    }

    private void sendGroupHelp(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gold>Custom placeholder groups <gray>(Phase 2.1)</gray>:</gold>"));
        sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard group list</gold>            <dark_gray>= list all groups</dark_gray>"));
        sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard group create <name></gold>    <dark_gray>= create an empty group</dark_gray>"));
        sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard group add <name> <key...></gold>  <dark_gray>= add placeholder(s)</dark_gray>"));
        sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard group remove <name> <key...></gold>  <dark_gray>= remove placeholder(s)</dark_gray>"));
        sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard group delete <name></gold>  <dark_gray>= delete the group</dark_gray>"));
        sender.sendMessage(MM.deserialize("<gray>Groups appear as <gold>group:<name></gold> sources in the placeholder browser.</gray>"));
    }

    // ---------------------------------------------------------------------
    // v2.0.1 — Manual placeholder keys (persistent across restarts)
    //
    //   /hudboard manual list
    //   /hudboard manual add <source> <key>
    //   /hudboard manual remove <source> <key>
    //   /hudboard manual clear <source|all>
    //
    // The browser's "Type manually" dialog (slot 31) writes here
    // automatically. The commands are mostly for admin bookkeeping
    // and for cleaning up dead placeholders that the admin no longer
    // needs.
    // ---------------------------------------------------------------------
    private void handleManual(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.admin")) {
            sender.sendMessage(plugin.getLang().get("no-perm"));
            return;
        }
        if (args.length < 2) { sendManualHelp(sender); return; }
        com.hudboard.data.ManualKeysStorage storage = plugin.getManualKeys();
        if (storage == null) {
            sender.sendMessage(MM.deserialize("<red>ManualKeysStorage not initialized yet.</red>"));
            return;
        }
        String sub = args[1].toLowerCase();
        switch (sub) {
            case "list", "ls" -> {
                var all = storage.all();
                if (all.isEmpty()) {
                    sender.sendMessage(MM.deserialize("<gray>No manual keys yet. Open <gold>/hudboard placeholders</gold> and use <gold>Type Manually</gold> to add some.</gray>"));
                    return;
                }
                sender.sendMessage(MM.deserialize("<gold>Manual placeholder keys <gray>(" + all.size()
                        + " source" + (all.size() > 1 ? "s" : "")
                        + ", survives restart)</gray>:</gold>"));
                for (var entry : all.entrySet()) {
                    sender.sendMessage(MM.deserialize("<gold> " + entry.getKey()
                            + "</gold> <gray>(" + entry.getValue().size() + " key" + (entry.getValue().size() > 1 ? "s" : "") + ")</gray>"));
                    for (String k : entry.getValue()) {
                        sender.sendMessage(MM.deserialize("  <dark_gray>- <white>%" + k + "%</white></dark_gray>"));
                    }
                }
            }
            case "add" -> {
                if (args.length < 4) {
                    sender.sendMessage(MM.deserialize("<red>Usage: /hudboard manual add <source> <key></red>"));
                    return;
                }
                if (storage.add(args[2], args[3])) {
                    sender.sendMessage(MM.deserialize("<green>Added <gold>%" + args[3] + "%</gold> under source <gold>" + args[2] + "</gold>.</green>"));
                } else {
                    sender.sendMessage(MM.deserialize("<yellow>Already present or invalid.</yellow>"));
                }
            }
            case "remove", "rm" -> {
                if (args.length < 4) {
                    sender.sendMessage(MM.deserialize("<red>Usage: /hudboard manual remove <source> <key></red>"));
                    return;
                }
                if (storage.remove(args[2], args[3])) {
                    sender.sendMessage(MM.deserialize("<green>Removed <gold>%" + args[3] + "%</gold> from source <gold>" + args[2] + "</gold>.</green>"));
                } else {
                    sender.sendMessage(MM.deserialize("<yellow>Key not found under that source.</yellow>"));
                }
            }
            case "clear" -> {
                if (args.length < 3) {
                    sender.sendMessage(MM.deserialize("<red>Usage: /hudboard manual clear <source|all></red>"));
                    return;
                }
                if (args[2].equalsIgnoreCase("all")) {
                    int totalKeys = storage.all().values().stream().mapToInt(java.util.List::size).sum();
                    int sources = storage.all().size();
                    storage.clear();
                    sender.sendMessage(MM.deserialize("<green>Cleared <gold>" + sources
                            + "</gold> source" + (sources > 1 ? "s" : "")
                            + " / <gold>" + totalKeys
                            + "</gold> manual key" + (totalKeys > 1 ? "s" : "") + ".</green>"));
                } else {
                    var keys = storage.keysFor(args[2]);
                    if (storage.clearSource(args[2])) {
                        sender.sendMessage(MM.deserialize("<green>Cleared source <gold>" + args[2]
                                + "</gold> (" + keys.size() + " key" + (keys.size() > 1 ? "s" : "") + ").</green>"));
                    } else {
                        sender.sendMessage(MM.deserialize("<yellow>No manual keys under that source.</yellow>"));
                    }
                }
            }
            default -> sendManualHelp(sender);
        }
    }

    private void sendManualHelp(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gold>Manual placeholder keys <gray>(v2.0.1 — persists across restarts)</gray>:</gold>"));
        sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard manual list</gold>                   <dark_gray>= list every manual entry</dark_gray>"));
        sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard manual add <source> <key></gold>    <dark_gray>= add manually</dark_gray>"));
        sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard manual remove <source> <key></gold> <dark_gray>= remove one</dark_gray>"));
        sender.sendMessage(MM.deserialize(" <gray>/<gold>hudboard manual clear <source|all></gold>  <dark_gray>= wipe</dark_gray>"));
        sender.sendMessage(MM.deserialize("<gray>Saved in <gold>plugins/HudBoard/manual-keys.yml</gold>.</gray>"));
    }

    // ---------------------------------------------------------------------
    // v2.3.0 — Data-point templates (preset strings to seed the add dialog)
    //   /hudboard template list         show every preset key + body
    //   /hudboard template show <key>   dump one preset to chat
    // ---------------------------------------------------------------------
    private void handleTemplate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.admin")) {
            sender.sendMessage(plugin.getLang().get("no-perm"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(MM.deserialize(
                    "<red>Usage: /hudboard template list | show <key></red>"));
            return;
        }
        var templates = com.hudboard.menu.DialogInputBridge.ADD_DP_TEMPLATES;
        switch (args[1].toLowerCase()) {
            case "list", "ls" -> {
                sender.sendMessage(MM.deserialize("<gold>Data-point templates <gray>(v2.3.0 — used by the Add Data Point dialog)</gray>:</gold>"));
                for (var entry : templates.entrySet()) {
                    sender.sendMessage(MM.deserialize("<gold> " + entry.getKey()
                            + "</gold> <gray>→ <white>" + entry.getValue() + "</white></gray>"));
                }
                sender.sendMessage(MM.deserialize("<gray>To use one, click \"+\" Add data point in the panel editor and edit the prefilled value.</gray>"));
            }
            case "show" -> {
                if (args.length < 3) {
                    sender.sendMessage(MM.deserialize("<red>Usage: /hudboard template show <key></red>"));
                    return;
                }
                String val = templates.get(args[2].toLowerCase());
                if (val == null) {
                    sender.sendMessage(MM.deserialize("<red>Unknown template key.</red>"));
                    return;
                }
                sender.sendMessage(MM.deserialize("<gold>Template <aqua>" + args[2]
                        + "</aqua>:</gold> <white>" + val + "</white>"));
            }
            default -> sender.sendMessage(MM.deserialize(
                    "<red>Usage: /hudboard template list | show <key></red>"));
        }
    }

    private void handleInfo(CommandSender sender) {
        sender.sendMessage(plugin.getLang().get("info.version", "%version%", plugin.getPluginMeta().getVersion()));
        sender.sendMessage(plugin.getLang().get("info.java"));
        sender.sendMessage(MM.deserialize("<gray>Profiles: <gold>" + plugin.getPanelManager().getProfileIds().size() + "</gold>  ·  Placed: <gold>" + plugin.getPanelManager().allPlaced().size() + "</gold>  ·  PAPI: <gold>" + (plugin.getDataManager().hasPapi() ? "yes" : "no") + "</gold></gray>"));
    }

    // ---------------------------------------------------------------------
    // v2.3.0 — /hudboard preview <profile>
    //
    // Renders every data point of a profile with the editor's own
    // PAPI values, sends back via chat so the admin sees what the panel
    // will look like without walking to the physical placement.
    // Useful for sanity-checking gradients/colors/placeholders before
    // re-rendering the live panel.
    // ---------------------------------------------------------------------
    private void handlePreview(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hudboard.admin")) {
            sender.sendMessage(plugin.getLang().get("no-perm"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(MM.deserialize("<red>Usage: /hudboard preview <profileId></red>"));
            return;
        }
        String profileId = args[1];
        InfoPanel profile = plugin.getPanelManager().get(profileId);
        if (profile == null) {
            sender.sendMessage(MM.deserialize("<red>Profile <gold>" + profileId + "</gold> not found.</red>"));
            return;
        }
        Player viewer = (sender instanceof Player p) ? p : null;
        sender.sendMessage(MM.deserialize("<gold>Preview of <aqua>" + profileId
                + "</aqua> <gray>(as " + (viewer != null ? viewer.getName() : "server") + ")</gray>:</gold>"));
        if (profile.dataPoints.isEmpty()) {
            sender.sendMessage(MM.deserialize("<gray>  (no data points)</gray>"));
            return;
        }
        for (InfoPanel.DataPoint dp : profile.dataPoints) {
            String resolved;
            try {
                resolved = (viewer != null)
                        ? plugin.getDataManager().resolve(dp.text, viewer)
                        : plugin.getDataManager().resolve(dp.text, null);
            } catch (Throwable t) {
                resolved = "(PAPI error: " + t.getMessage() + ")";
            }
            // Apply the optional baseColor prefix so the preview matches
            // what the panel will actually render.
            if (dp.baseColor != null && !dp.baseColor.isBlank() && !resolved.startsWith("(")) {
                resolved = dp.baseColor + resolved;
            }
            sender.sendMessage(MM.deserialize(
                    "<dark_gray>[" + dp.key + " x=" + dp.x + " y=" + dp.y + " size=" + dp.size
                            + (dp.bg != null && !dp.bg.isBlank() ? " bg=" + dp.bg : "")
                            + (!"left".equalsIgnoreCase(dp.align) ? " align=" + dp.align : "")
                            + (dp.padding != 0 ? " pad=" + dp.padding : "")
                            + "]</dark_gray> "
                            + (resolved.isEmpty() ? "<gray>(empty)</gray>" : "<white>" + resolved + "</white>")));
        }
        sender.sendMessage(MM.deserialize("<gray>" + profile.dataPoints.size()
                + " data point" + (profile.dataPoints.size() > 1 ? "s" : "") + " total.</gray>"));
    }

    private void playSound(Player p, String sound) {
        try { p.playSound(p.getLocation(), Sound.valueOf(sound), 0.7f, 1.2f); }
        catch (Throwable t) { /* ignore */ }
    }
}
