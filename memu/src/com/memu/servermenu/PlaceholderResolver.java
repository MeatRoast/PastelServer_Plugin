package com.memu.servermenu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaceholderResolver {
    private static final Pattern BRACE_TOKEN = Pattern.compile("\\{([a-zA-Z0-9_.-]+)}");
    private final Main plugin;

    public PlaceholderResolver(Main plugin) {
        this.plugin = plugin;
    }

    public String format(Player player, String input, Map<String, String> context) {
        String text = ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
        text = replaceLocal(text, player, context);
        text = replacePercentTokens(text, context);
        return applyPlaceholderApi(player, text);
    }

    private String replaceLocal(String text, Player player, Map<String, String> context) {
        Matcher matcher = BRACE_TOKEN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = resolveValue(player, key, context);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String resolveValue(Player player, String key, Map<String, String> context) {
        if (context.containsKey(key)) {
            return context.get(key);
        }
        if ("player".equalsIgnoreCase(key) || "player_name".equalsIgnoreCase(key)) {
            return player.getName();
        }
        if ("display_name".equalsIgnoreCase(key)) {
            return player.getDisplayName();
        }
        if ("online".equalsIgnoreCase(key)) {
            return String.valueOf(Bukkit.getOnlinePlayers().size());
        }
        if ("max_online".equalsIgnoreCase(key)) {
            return String.valueOf(Bukkit.getMaxPlayers());
        }
        return "{" + key + "}";
    }

    private String applyPlaceholderApi(Player player, String text) {
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            return text;
        }
        try {
            Class<?> placeholderApi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method method = placeholderApi.getMethod("setPlaceholders", Player.class, String.class);
            Object value = method.invoke(null, player, text);
            return value == null ? text : String.valueOf(value);
        } catch (Exception ex) {
            plugin.getLogger().warning("PlaceholderAPI 호출 실패: " + ex.getMessage());
            return text;
        }
    }

    private String replacePercentTokens(String text, Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return text;
        }
        String out = text;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith("%") && key.endsWith("%")) {
                out = out.replace(key, entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return out;
    }
}
