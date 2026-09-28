package com.hudboard.command;

import com.hudboard.HudBoardPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class HudBoardTabCompleter implements TabCompleter {

    private final HudBoardPlugin plugin;

    // v2.6.1: top-level sub-commands — kept in sync with the switch in
    // HudBoardCommand#onCommand. We removed panel-level duplicates of
    // move/tp/rename/edit/remove/save, and added the new Phase 2 commands
    // (preview, group, manual, template) plus undo/redo/repair under panel.
    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "help", "list", "panels", "info", "stats", "reload", "debug",
            "preview",
            "panel",
            "move", "tp", "rename", "edit",
            "remove", "rm", "nuke",
            "group", "groups",
            "manual", "manuals",
            "template", "templates"
    );

    // v2.6.1: panel sub-commands (after removing duplicates).
    private static final List<String> PANEL_SUBS = Arrays.asList(
            "create", "place", "edit", "list", "info",
            "undo", "redo", "repair", "fix"
    );

    private static final List<String> FORMATS = Arrays.asList("png", "gif", "jpg");
    private static final List<String> SIZES = Arrays.asList(
            "1x1", "2x2", "2x3", "3x3", "4x4", "5x5", "6x6", "8x8", "10x10"
    );

    public HudBoardTabCompleter(HudBoardPlugin plugin) { this.plugin = plugin; }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("panel")) {
            if (args.length == 2) return filter(PANEL_SUBS, args[1]);
            String ps = args[1].toLowerCase();
            if (args.length == 3) {
                // v2.6.2: placed-panel commands (edit/info/undo/redo/repair)
                // suggest PLACED panel names, not profile names — so the admin
                // sees test-1, test-2, test-3, test-4 instead of just `test`.
                // `place` keeps suggesting profiles (it's about creating a new
                // placement from an existing profile).
                return switch (ps) {
                    case "create" -> new ArrayList<>();   // expects <name>
                    case "place" ->
                            filter(new ArrayList<>(plugin.getPanelManager().getProfileIds()), args[2]);
                    case "edit", "info", "undo", "redo", "repair", "fix" ->
                            filter(new ArrayList<>(plugin.getPanelManager().allPlaced().keySet()), args[2]);
                    default -> new ArrayList<>();
                };
            }
            if (args.length == 4 && ps.equals("create")) return filter(FORMATS, args[3]);
            if (args.length == 5 && ps.equals("create")) return filter(SIZES, args[4]);
            return new ArrayList<>();
        }

        // Top-level ops on placed panels
        if (args.length == 2) {
            return switch (sub) {
                case "remove", "rm", "tp", "move", "rename", "edit" ->
                        filter(new ArrayList<>(plugin.getPanelManager().allPlaced().keySet()), args[1]);
                case "nuke" -> filter(Arrays.asList("64", "128", "256", "500", "1000"), args[1]);
                case "preview" -> filter(new ArrayList<>(plugin.getPanelManager().getProfileIds()), args[1]);
                case "group", "groups", "manual", "manuals", "template", "templates" ->
                        filter(Arrays.asList("list", "ls", "create", "add", "remove", "delete", "del",
                                "clear", "show", "export", "import", "new"), args[1]);
                case "debug" -> filter(Arrays.asList("papi", "nms", "log", "loglevel", "purge", "scan", "fix"), args[1]);
                default -> new ArrayList<>();
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "nuke" -> filter(Arrays.asList("16", "32", "64", "128", "256"), args[2]);
                case "remove", "rm" -> filter(Arrays.asList("all"), args[2]);
                case "rename" -> filter(new ArrayList<>(plugin.getPanelManager().allPlaced().keySet()), args[2]);
                case "edit" -> new ArrayList<>();
                case "group", "groups" -> filter(new ArrayList<>(plugin.getGroupManager() == null
                                ? java.util.List.<String>of()
                                : plugin.getGroupManager().all().keySet()) {{
                                    add("create");
                                }}, args[2]);
                case "manual", "manuals" -> filter(Arrays.asList("player", "server", "world", "economy", "top", "runtime"), args[2]);
                case "template", "templates" -> filter(Arrays.asList("list", "show"), args[2]);
                case "debug" -> {
                    if (args[1].equalsIgnoreCase("log") || args[1].equalsIgnoreCase("loglevel"))
                        yield filter(Arrays.asList("off", "warning", "info", "fine", "all"), args[2]);
                    yield new ArrayList<>();
                }
                default -> new ArrayList<>();
            };
        }
        if (args.length == 4) {
            return switch (sub) {
                case "rename" -> new ArrayList<>();
                case "manual", "manuals" -> new ArrayList<>();
                case "template", "templates" -> filter(Arrays.asList("player", "tps", "balance", "time", "alert", "blank"), args[3]);
                case "debug" -> new ArrayList<>();
                default -> new ArrayList<>();
            };
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(p)).collect(Collectors.toList());
    }
}
