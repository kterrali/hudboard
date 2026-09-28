package com.hudboard.lang;

import com.hudboard.HudBoardPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public class Lang {

    private final HudBoardPlugin plugin;
    private final MiniMessage MM = MiniMessage.miniMessage();
    private YamlConfiguration yml;
    private String prefix = "<gray>[<gold>HudBoard</gold>]</gray> ";

    public Lang(HudBoardPlugin plugin) { this.plugin = plugin; }

    public void load() {
        File f = new File(plugin.getDataFolder(), "lang.yml");
        if (!f.exists()) plugin.saveResource("lang.yml", false);
        yml = YamlConfiguration.loadConfiguration(f);
        prefix = yml.getString("prefix", prefix);
    }

    public Component get(String key) {
        String raw = yml == null ? "<red>missing lang key: " + key + "</red>" : yml.getString(key, "<red>missing lang key: " + key + "</red>");
        return MM.deserialize(prefix + raw);
    }

    public Component get(String key, String... replacements) {
        String raw = yml == null ? "<red>missing lang key: " + key + "</red>" : yml.getString(key, "<red>missing lang key: " + key + "</red>");
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        return MM.deserialize(prefix + raw);
    }

    public String raw(String key) {
        return yml == null ? "" : yml.getString(key, "");
    }
}
