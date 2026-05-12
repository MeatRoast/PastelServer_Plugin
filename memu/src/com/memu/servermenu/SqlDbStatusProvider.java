package com.memu.servermenu;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SqlDbStatusProvider {
    private final Main plugin;
    private final Map<String, ServerState> cache = new ConcurrentHashMap<>();
    private int taskId = -1;
    private boolean enabled;
    private String query;
    private String initTableSql;
    private long refreshTicks;
    private long heartbeatTimeoutSeconds;

    public SqlDbStatusProvider(Main plugin) {
        this.plugin = plugin;
    }

    public void reload(List<String> serverIds) {
        stop();
        enabled = plugin.getConfig().getBoolean("sqldb.enabled", true);
        query = plugin.getConfig().getString(
                "sqldb.query",
                "SELECT status, online_players, last_seen FROM server_status WHERE server_id = ? LIMIT 1"
        );
        initTableSql = plugin.getConfig().getString(
                "sqldb.init_table_sql",
                "CREATE TABLE IF NOT EXISTS server_status (" +
                        "server_id VARCHAR(64) PRIMARY KEY," +
                        "status VARCHAR(32) NOT NULL DEFAULT '오프라인'," +
                        "online_players INT NOT NULL DEFAULT 0," +
                        "last_seen BIGINT NOT NULL DEFAULT 0" +
                        ")"
        );
        refreshTicks = Math.max(20L, plugin.getConfig().getLong("sqldb.refresh_ticks", 100L));
        heartbeatTimeoutSeconds = Math.max(5L, plugin.getConfig().getLong("sqldb.heartbeat_timeout_seconds", 20L));

        if (!enabled) {
            cache.clear();
            return;
        }

        List<String> ids = new ArrayList<>(serverIds);
        refresh(ids);
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                () -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> refresh(ids)),
                refreshTicks,
                refreshTicks
        );
    }

    public Map<String, String> placeholdersFor(String serverId) {
        if (!enabled || serverId == null || serverId.isEmpty()) {
            return Collections.emptyMap();
        }
        ServerState state = cache.get(serverId);
        if (state == null) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new ConcurrentHashMap<>();
        out.put("%server_status_" + serverId + "%", state.status);
        out.put("%server_online_" + serverId + "%", String.valueOf(state.onlinePlayers));
        out.put("%server_status%", state.status);
        out.put("%server_online%", String.valueOf(state.onlinePlayers));
        // PlaceholderAPI bungee 확장이 없거나 값이 비어도 메뉴 숫자 표시가 되도록 DB 값으로 대체 제공
        out.put("%bungee_" + serverId + "%", String.valueOf(state.onlinePlayers));
        out.put("%bungee_" + serverId.toLowerCase() + "%", String.valueOf(state.onlinePlayers));
        out.put("%bungee_" + serverId.toUpperCase() + "%", String.valueOf(state.onlinePlayers));
        out.put("%bungee_total%", String.valueOf(totalOnline()));
        return out;
    }

    public Map<String, String> allPlaceholders() {
        if (!enabled || cache.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new ConcurrentHashMap<>();
        for (Map.Entry<String, ServerState> entry : cache.entrySet()) {
            String id = entry.getKey();
            ServerState state = entry.getValue();
            out.put("%server_status_" + id + "%", state.status);
            out.put("%server_online_" + id + "%", String.valueOf(state.onlinePlayers));
            out.put("%bungee_" + id + "%", String.valueOf(state.onlinePlayers));
        }
        out.put("%bungee_total%", String.valueOf(totalOnline()));
        return out;
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void refresh(List<String> serverIds) {
        if (serverIds.isEmpty()) {
            return;
        }
        try {
            Object api = getSqlDbApi();
            if (api == null) {
                return;
            }
            Connection connection = resolveConnection(api);
            if (connection == null) {
                plugin.getLogger().warning("SQLDB API에서 Connection을 가져오지 못했습니다.");
                return;
            }
            try (Connection conn = connection) {
                ensureTable(conn);
                PreparedStatement ps = conn.prepareStatement(query);
                for (String id : serverIds) {
                    ps.clearParameters();
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String rawStatus = safeGet(rs, "status", "오프라인");
                            int online = safeGetInt(rs, "online_players", 0);
                            long lastSeen = readLastSeenSeconds(rs);
                            boolean alive = isAlive(lastSeen);
                            String status = alive ? rawStatus : "오프라인";
                            if (!alive) {
                                online = 0;
                            }
                            cache.put(id, new ServerState(status, online));
                        } else {
                            cache.put(id, new ServerState("오프라인", 0));
                        }
                    } catch (Exception rowEx) {
                        cache.put(id, new ServerState("오프라인", 0));
                    }
                }
                ps.close();
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("SQLDB 조회 실패: " + ex.getMessage());
            for (String id : serverIds) {
                cache.put(id, new ServerState("오프라인", 0));
            }
        }
    }

    private void ensureTable(Connection conn) {
        if (initTableSql == null || initTableSql.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(initTableSql)) {
            ps.execute();
        } catch (Exception ex) {
            plugin.getLogger().warning("SQLDB 테이블 생성 실패: " + ex.getMessage());
        }
    }

    private Object getSqlDbApi() {
        try {
            Class<?> serviceClass = Class.forName("io.github.dohwan.sqldb.api.SqlDbService");
            for (Method method : serviceClass.getMethods()) {
                if (!method.getName().equals("get")) {
                    continue;
                }
                if (method.getParameterCount() == 0) {
                    return method.invoke(null);
                }
                if (method.getParameterCount() == 1) {
                    Class<?> param = method.getParameterTypes()[0];
                    if (param.isAssignableFrom(plugin.getClass())) {
                        return method.invoke(null, plugin);
                    }
                    if (param.equals(Plugin.class)) {
                        return method.invoke(null, plugin);
                    }
                    if (param.equals(Object.class)) {
                        return method.invoke(null, plugin);
                    }
                }
            }
            plugin.getLogger().warning("SQLDB 서비스 get(...) 메서드를 찾지 못했습니다.");
            return null;
        } catch (Exception ex) {
            plugin.getLogger().warning("SQLDB 서비스 접근 실패: " + ex.getMessage());
            return null;
        }
    }

    private Connection resolveConnection(Object api) {
        for (String methodName : new String[]{"getConnection", "connection", "openConnection"}) {
            try {
                Method m = api.getClass().getMethod(methodName);
                Object result = m.invoke(api);
                if (result instanceof Connection) {
                    return (Connection) result;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String safeGet(ResultSet rs, String column, String fallback) {
        try {
            String v = rs.getString(column);
            return v == null ? fallback : v;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int safeGetInt(ResultSet rs, String column, int fallback) {
        try {
            return rs.getInt(column);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long readLastSeenSeconds(ResultSet rs) {
        try {
            long v = rs.getLong("last_seen");
            if (v > 0L) {
                return v;
            }
        } catch (Exception ignored) {
        }
        try {
            long v = rs.getLong("last_seen_epoch");
            if (v > 0L) {
                return v;
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private boolean isAlive(long lastSeenSeconds) {
        if (lastSeenSeconds <= 0L) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000L;
        return now - lastSeenSeconds <= heartbeatTimeoutSeconds;
    }

    private int totalOnline() {
        int total = 0;
        for (ServerState state : cache.values()) {
            total += Math.max(0, state.onlinePlayers);
        }
        return total;
    }

    private static final class ServerState {
        private final String status;
        private final int onlinePlayers;

        private ServerState(String status, int onlinePlayers) {
            this.status = status;
            this.onlinePlayers = onlinePlayers;
        }
    }
}
