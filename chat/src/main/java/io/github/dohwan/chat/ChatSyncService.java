package io.github.dohwan.chat;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

public final class ChatSyncService {
    private final CrossChatPlugin plugin;
    private final String serverName;
    private final SqlDbBridge sqlDbBridge;
    private final String tableName;
    private final String logTableName;
    private final String modeTableName;
    private final String profileTableName;
    private final int pollTick;
    private final String nodeId;
    private BukkitTask pollTask;
    private long lastSeenId = 0L;

    public ChatSyncService(@NotNull CrossChatPlugin plugin, @NotNull String serverName) {
        this.plugin = plugin;
        this.serverName = serverName;
        this.sqlDbBridge = new SqlDbBridge(plugin);
        FileConfiguration cfg = plugin.getConfig();
        this.tableName = cfg.getString("chat.table", "global_chat");
        this.logTableName = cfg.getString("chat.log-table", "chat_logs");
        this.modeTableName = cfg.getString("chat.mode-table", "player_chat_mode");
        this.profileTableName = cfg.getString("chat.profile-table", "player_chat_profile");
        this.pollTick = Math.max(1, cfg.getInt("poll-tick", 20));
        this.nodeId = UUID.randomUUID().toString();
    }

    public boolean initialize() {
        if (!sqlDbBridge.initialize()) {
            return false;
        }
        try (Connection connection = sqlDbBridge.getConnection()) {
            ensureChatTable(connection);
            ensureChatTableColumns(connection);
            ensureLogTable(connection);
            ensureLogTableColumns(connection);
            ensureModeTable(connection);
            ensureProfileTable(connection);
            ensureProfileTableColumns(connection);
            lastSeenId = resolveLastId(connection);
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "채팅 테이블 초기화 실패", e);
            return false;
        }
    }

    public CrossChatPlugin.ChatMode loadPlayerMode(UUID uuid) {
        String sql = "SELECT mode FROM " + modeTableName + " WHERE player_uuid = ?";
        try (Connection connection = sqlDbBridge.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String mode = rs.getString("mode");
                return parseMode(mode);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "플레이어 채팅 모드 조회 실패: " + uuid, e);
            return null;
        }
    }

    public void savePlayerMode(UUID uuid, CrossChatPlugin.ChatMode mode) {
        String sql = "INSERT INTO " + modeTableName + " (player_uuid, mode, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE mode = VALUES(mode), updated_at = CURRENT_TIMESTAMP";
        try (Connection connection = sqlDbBridge.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, mode.name());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "플레이어 채팅 모드 저장 실패: " + uuid, e);
        }
    }

    public String loadNickname(UUID uuid) {
        String sql = "SELECT nickname FROM " + profileTableName + " WHERE player_uuid = ?";
        try (Connection connection = sqlDbBridge.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("nickname");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "닉네임 조회 실패: " + uuid, e);
            return null;
        }
    }

    public void saveNickname(UUID uuid, String nickname) {
        String sql = "INSERT INTO " + profileTableName + " (player_uuid, nickname, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), updated_at = CURRENT_TIMESTAMP";
        try (Connection connection = sqlDbBridge.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, nickname);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "닉네임 저장 실패: " + uuid, e);
        }
    }

    public boolean loadColorChatUnlocked(UUID uuid) {
        String sql = "SELECT color_chat_unlocked FROM " + profileTableName + " WHERE player_uuid = ?";
        try (Connection connection = sqlDbBridge.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean("color_chat_unlocked");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "색채팅권 활성화 조회 실패: " + uuid, e);
            return false;
        }
    }

    public void saveColorChatUnlocked(UUID uuid, boolean unlocked) {
        String sql = "INSERT INTO " + profileTableName + " (player_uuid, nickname, color_chat_unlocked, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE color_chat_unlocked = VALUES(color_chat_unlocked), updated_at = CURRENT_TIMESTAMP";
        try (Connection connection = sqlDbBridge.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, ""); // 기존 레코드 없을 때만 사용, 기존 닉네임은 UPDATE에서 유지됨
            ps.setBoolean(3, unlocked);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "색채팅권 활성화 저장 실패: " + uuid, e);
        }
    }

    public void startPolling() {
        this.pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::pollRemoteMessages,
                pollTick,
                pollTick
        );
    }

    public void shutdown() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    public void publishGlobal(String username, String nickname, String content) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO " + tableName + " (server_name, username, nickname, content, node_id) VALUES (?, ?, ?, ?, ?)";
            try (Connection connection = sqlDbBridge.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, serverName);
                ps.setString(2, username);
                ps.setString(3, nickname);
                ps.setString(4, content);
                ps.setString(5, nodeId);
                ps.executeUpdate();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "채팅 전송 실패", e);
            }
        });
    }

    public void appendChatLog(String chatType, String username, String content) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO " + logTableName + " (id, server_name, chat_type, username, content) VALUES (?, ?, ?, ?, ?)";
            try (Connection connection = sqlDbBridge.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, serverName);
                ps.setString(3, chatType);
                ps.setString(4, username);
                ps.setString(5, content);
                ps.executeUpdate();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "채팅 로그 저장 실패", e);
            }
        });
    }

    private void pollRemoteMessages() {
        String sql = "SELECT id, server_name, username, nickname, content, node_id FROM " + tableName + " WHERE id > ? ORDER BY id ASC";
        try (Connection connection = sqlDbBridge.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, lastSeenId);
            try (ResultSet rs = ps.executeQuery()) {
                long maxSeen = lastSeenId;
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String sourceServer = rs.getString("server_name");
                    String username = rs.getString("username");
                    String nickname = rs.getString("nickname");
                    String content = rs.getString("content");
                    String sourceNode = rs.getString("node_id");

                    if (id > maxSeen) {
                        maxSeen = id;
                    }
                    if (nodeId.equals(sourceNode)) {
                        continue;
                    }
                    plugin.broadcastRemoteGlobal(sourceServer, username, nickname == null ? username : nickname, content);
                }
                lastSeenId = maxSeen;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "원격 채팅 수신 실패", e);
        }
    }

    private void ensureChatTable(Connection connection) throws SQLException {
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  server_name VARCHAR(32) NOT NULL,
                  username VARCHAR(16) NOT NULL,
                  nickname VARCHAR(64) NOT NULL,
                  content VARCHAR(512) NOT NULL,
                  node_id VARCHAR(36) NOT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX idx_chat_id (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(tableName);
        try (PreparedStatement ps = connection.prepareStatement(ddl)) {
            ps.execute();
        }
    }

    private void ensureChatTableColumns(Connection connection) throws SQLException {
        if (!hasColumn(connection, tableName, "nickname")) {
            String sql = "ALTER TABLE " + tableName + " ADD COLUMN nickname VARCHAR(64) NOT NULL DEFAULT '' AFTER username";
            try (Statement st = connection.createStatement()) {
                st.execute(sql);
            }
        }
    }

    private void ensureModeTable(Connection connection) throws SQLException {
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                  player_uuid CHAR(36) NOT NULL PRIMARY KEY,
                  mode VARCHAR(16) NOT NULL,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(modeTableName);
        try (PreparedStatement ps = connection.prepareStatement(ddl)) {
            ps.execute();
        }
    }

    private void ensureLogTable(Connection connection) throws SQLException {
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                  id CHAR(36) NOT NULL PRIMARY KEY,
                  server_name VARCHAR(32) NOT NULL,
                  chat_type VARCHAR(16) NOT NULL,
                  username VARCHAR(16) NOT NULL,
                  content VARCHAR(512) NOT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX idx_logs_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(logTableName);
        try (PreparedStatement ps = connection.prepareStatement(ddl)) {
            ps.execute();
        }
    }

    private void ensureLogTableColumns(Connection connection) throws SQLException {
        if (!hasColumn(connection, logTableName, "id")) {
            String sql = "ALTER TABLE " + logTableName + " ADD COLUMN id CHAR(36) NOT NULL PRIMARY KEY FIRST";
            try (Statement st = connection.createStatement()) {
                st.execute(sql);
            }
            return;
        }

        String idType = getColumnType(connection, logTableName, "id");
        if (idType != null && !"char".equalsIgnoreCase(idType) && !"varchar".equalsIgnoreCase(idType)) {
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE " + logTableName + " MODIFY COLUMN id CHAR(36) NOT NULL");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "chat_logs.id 타입 변환 실패 (수동 확인 필요)", e);
            }
        }

        if (!hasColumn(connection, logTableName, "created_at")) {
            String sql = "ALTER TABLE " + logTableName + " ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP";
            try (Statement st = connection.createStatement()) {
                st.execute(sql);
            }
        }
    }

    private void ensureProfileTable(Connection connection) throws SQLException {
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                  player_uuid CHAR(36) NOT NULL PRIMARY KEY,
                  nickname VARCHAR(64) NOT NULL,
                  color_chat_unlocked TINYINT(1) NOT NULL DEFAULT 0,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(profileTableName);
        try (PreparedStatement ps = connection.prepareStatement(ddl)) {
            ps.execute();
        }
    }

    private void ensureProfileTableColumns(Connection connection) throws SQLException {
        if (!hasColumn(connection, profileTableName, "color_chat_unlocked")) {
            String sql = "ALTER TABLE " + profileTableName + " ADD COLUMN color_chat_unlocked TINYINT(1) NOT NULL DEFAULT 0 AFTER nickname";
            try (Statement st = connection.createStatement()) {
                st.execute(sql);
            }
        }
    }

    private long resolveLastId(Connection connection) throws SQLException {
        String sql = "SELECT COALESCE(MAX(id), 0) AS max_id FROM " + tableName;
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return 0L;
            }
            return rs.getLong("max_id");
        }
    }

    private boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        String sql = """
                SELECT COUNT(*) AS cnt
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("cnt") > 0;
            }
        }
    }

    private String getColumnType(Connection connection, String table, String column) throws SQLException {
        String sql = """
                SELECT DATA_TYPE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("DATA_TYPE");
            }
        }
    }

    private CrossChatPlugin.ChatMode parseMode(String modeText) {
        if (modeText == null || modeText.isBlank()) {
            return null;
        }
        try {
            return CrossChatPlugin.ChatMode.valueOf(modeText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
