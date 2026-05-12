package com.guild.plugin;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

public class MessageConfig {
    private final JavaPlugin plugin;
    private YamlConfiguration yaml;

    public MessageConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "message.yml");
        if (!file.exists()) plugin.saveResource("message.yml", false);
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    public String get(String path) {
        String raw = yaml.getString(path, path);
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public String get(String path, Map<String, String> placeholders) {
        String s = get(path);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }
}
