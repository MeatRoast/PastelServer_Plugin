package com.money.plugin;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.stream.Collectors;

public class MessageService {
    private final MoneyPlugin plugin;
    private FileConfiguration config;

    public MessageService(MoneyPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            try (InputStream in = plugin.getResource("messages.yml")) {
                if (in != null) {
                    Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create messages.yml: " + e.getMessage());
            }
        }
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public String get(String key) {
        return colorize(applyPrefix(config.getString(key, key)));
    }

    public java.util.List<String> getList(String key) {
        return config.getStringList(key).stream().map(this::applyPrefix).map(this::colorize).collect(Collectors.toList());
    }

    public String format(String key, Map<String, String> placeholders) {
        String message = config.getString(key, key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return colorize(applyPrefix(message));
    }

    private String applyPrefix(String message) {
        String prefix = config.getString("messages.prefix", "");
        return message.replace("{prefix}", prefix);
    }

    private String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
