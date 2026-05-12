package com.memu.servermenu;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MySqlServerDataProvider {
    private final Main plugin;
    private final Map<String, DynamicServerData> cache = new ConcurrentHashMap<>();

    private boolean enabled;
    private String jdbcUrl;
    private String username;
    private String password;
    private String query;
    private long refreshTicks;
    private int taskId = -1;

    public MySqlServerDataProvider(Main plugin) {
        this.plugin = plugin;
    }

    public void reload(List<String> serverIds) {
        stop();

        ConfigurationSection mysql = plugin.getConfig().getConfigurationSection("mysql");
        if (mysql == null) {
            enabled = false;
            cache.clear();
            return;
        }

        enabled = mysql.getBoolean("enabled", false);
        if (!enabled) {
            cache.clear();
            return;
        }

        String host = mysql.getString("host", "127.0.0.1");
        int port = mysql.getInt("port", 3306);
        String database = mysql.getString("database", "minecraft");
        boolean useSsl = mysql.getBoolean("use-ssl", false);
        String extraParams = mysql.getString("extra-params", "useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC");
        this.username = mysql.getString("username", "root");
        this.password = mysql.getString("password", "");
        this.query = mysql.getString(
                "query",
                "SELECT status, online_players, difficulty, inventory_mode FROM server_status WHERE server_id = ? LIMIT 1"
        );
        this.refreshTicks = Math.max(20L, mysql.getLong("refresh-ticks", 100L));
        this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=" + useSsl + "&" + extraParams;

        // 첫 실행 즉시 + 주기 갱신
        refreshNow(serverIds);
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                () -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> refreshNow(serverIds)),
                refreshTicks,
                refreshTicks
        );
    }

    public DynamicServerData getData(String serverId) {
        if (!enabled) {
            return null;
        }
        return cache.get(serverId);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void refreshNow(List<String> serverIds) {
        if (!enabled || serverIds.isEmpty()) {
            return;
        }

        List<String> ids = new ArrayList<>(serverIds);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement ps = connection.prepareStatement(query)) {
            for (String id : ids) {
                ps.clearParameters();
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        cache.put(id, new DynamicServerData(
                                value(rs, "status"),
                                value(rs, "online_players"),
                                value(rs, "difficulty"),
                                value(rs, "inventory_mode")
                        ));
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("MySQL 데이터 조회 실패: " + ex.getMessage());
        }
    }

    private String value(ResultSet rs, String column) {
        try {
            String v = rs.getString(column);
            return v == null ? "" : v;
        } catch (Exception ignored) {
            return "";
        }
    }
    public static final class DynamicServerData {
        public final String status;
        public final String onlinePlayers;
        public final String difficulty;
        public final String inventoryMode;

        public DynamicServerData(String status, String onlinePlayers, String difficulty, String inventoryMode) {
            this.status = status;
            this.onlinePlayers = onlinePlayers;
            this.difficulty = difficulty;
            this.inventoryMode = inventoryMode;
        }
    }
}
