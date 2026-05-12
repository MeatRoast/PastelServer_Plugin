package com.money.plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EconomyService {
    private static final int SCALE = 2;
    private final MoneyPlugin plugin;
    private Object sqlDbApi;
    private Method getConnectionMethod;

    public EconomyService(MoneyPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void init() throws SQLException {
        resolveSqlDbApi();
        createTablesIfMissing();
    }

    public synchronized void reload() {
        shutdown();
        try {
            init();
        } catch (SQLException e) {
            plugin.getLogger().severe(plugin.messages().format("messages.db-reload-failed", java.util.Map.of("reason", e.getMessage())));
        }
    }

    public synchronized void shutdown() {
        sqlDbApi = null;
        getConnectionMethod = null;
    }

    public synchronized boolean isReady() {
        return sqlDbApi != null && getConnectionMethod != null;
    }

    public BigDecimal getBalance(UUID uuid) throws SQLException {
        ensureReady();
        String sql = "SELECT balance FROM player_balances WHERE uuid = ?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return normalize(rs.getBigDecimal("balance"));
                }
            }
        }
        createIfAbsent(uuid);
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.DOWN);
    }

    public boolean add(UUID uuid, BigDecimal amount) throws SQLException {
        ensureReady();
        return updateDelta(uuid, amount);
    }

    public boolean subtract(UUID uuid, BigDecimal amount) throws SQLException {
        ensureReady();
        return updateDelta(uuid, amount.negate());
    }

    public void set(UUID uuid, BigDecimal amount) throws SQLException {
        ensureReady();
        createIfAbsent(uuid);
        String sql = "UPDATE player_balances SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setBigDecimal(1, normalize(amount));
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public boolean transfer(UUID from, UUID to, BigDecimal amount) throws SQLException {
        ensureReady();
        BigDecimal value = normalize(amount);
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        createIfAbsent(from);
        createIfAbsent(to);

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                BigDecimal fromBalance = getBalanceForUpdate(connection, from);
                if (fromBalance.compareTo(value) < 0) {
                    connection.rollback();
                    return false;
                }

                updateBalance(connection, from, fromBalance.subtract(value));
                BigDecimal toBalance = getBalanceForUpdate(connection, to);
                updateBalance(connection, to, toBalance.add(value));
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public List<TopBalance> getTopBalances(int limit) throws SQLException {
        ensureReady();
        List<TopBalance> list = new ArrayList<>();
        String sql = "SELECT uuid, balance FROM player_balances ORDER BY balance DESC LIMIT ?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new TopBalance(UUID.fromString(rs.getString("uuid")), normalize(rs.getBigDecimal("balance"))));
                }
            }
        }
        return list;
    }

    public void logAction(String actorUuid,
                          String actorName,
                          String sourceType,
                          String actionType,
                          String targetUuid,
                          String targetName,
                          String amount,
                          String detail,
                          boolean success) {
        String console = "[AUDIT] source=" + sourceType
                + ", actor=" + actorName
                + ", action=" + actionType
                + ", target=" + (targetName == null ? "-" : targetName)
                + ", amount=" + (amount == null ? "-" : amount)
                + ", success=" + success
                + ", detail=" + (detail == null ? "-" : detail);
        plugin.getLogger().info(console);

        if (!isReady()) {
            return;
        }

        String sql = "INSERT INTO action_logs " +
                "(actor_uuid, actor_name, source_type, action_type, target_uuid, target_name, amount, detail, success, server_name) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, actorUuid);
            ps.setString(2, actorName);
            ps.setString(3, sourceType);
            ps.setString(4, actionType);
            ps.setString(5, targetUuid);
            ps.setString(6, targetName);
            ps.setString(7, amount);
            ps.setString(8, detail);
            ps.setBoolean(9, success);
            ps.setString(10, plugin.getServer().getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to persist audit log: " + e.getMessage());
        }
    }

    private boolean updateDelta(UUID uuid, BigDecimal delta) throws SQLException {
        createIfAbsent(uuid);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                BigDecimal current = getBalanceForUpdate(connection, uuid);
                BigDecimal next = current.add(normalize(delta));
                if (next.compareTo(BigDecimal.ZERO) < 0) {
                    connection.rollback();
                    return false;
                }
                updateBalance(connection, uuid, next);
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private BigDecimal getBalanceForUpdate(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT balance FROM player_balances WHERE uuid = ? FOR UPDATE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return BigDecimal.ZERO.setScale(SCALE, RoundingMode.DOWN);
                }
                return normalize(rs.getBigDecimal("balance"));
            }
        }
    }

    private void updateBalance(Connection connection, UUID uuid, BigDecimal balance) throws SQLException {
        String sql = "UPDATE player_balances SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setBigDecimal(1, normalize(balance));
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    private void createIfAbsent(UUID uuid) throws SQLException {
        String sql = "INSERT INTO player_balances (uuid, balance) VALUES (?, 0.00) ON DUPLICATE KEY UPDATE uuid = uuid";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    private void createTablesIfMissing() throws SQLException {
        String balancesSql = "CREATE TABLE IF NOT EXISTS player_balances (" +
                "uuid VARCHAR(36) PRIMARY KEY," +
                "balance DECIMAL(19,2) NOT NULL DEFAULT 0.00," +
                "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ")";
        String logsSql = "CREATE TABLE IF NOT EXISTS action_logs (" +
                "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "actor_uuid VARCHAR(36) NULL," +
                "actor_name VARCHAR(64) NOT NULL," +
                "source_type VARCHAR(16) NOT NULL," +
                "action_type VARCHAR(32) NOT NULL," +
                "target_uuid VARCHAR(36) NULL," +
                "target_name VARCHAR(64) NULL," +
                "amount VARCHAR(64) NULL," +
                "detail TEXT NULL," +
                "success TINYINT(1) NOT NULL," +
                "server_name VARCHAR(64) NOT NULL," +
                "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_created_at (created_at)," +
                "INDEX idx_actor_uuid (actor_uuid)," +
                "INDEX idx_action_type (action_type)" +
                ")";
        try (Connection connection = openConnection();
             PreparedStatement ps1 = connection.prepareStatement(balancesSql);
             PreparedStatement ps2 = connection.prepareStatement(logsSql)) {
            ps1.execute();
            ps2.execute();
        }
    }

    private BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.DOWN);
        }
        return value.setScale(SCALE, RoundingMode.DOWN);
    }

    public record TopBalance(UUID uuid, BigDecimal balance) {
    }

    private void ensureReady() throws SQLException {
        if (!isReady()) {
            throw new SQLException("Database is not connected");
        }
    }

    private void resolveSqlDbApi() throws SQLException {
        try {
            Class<?> serviceClass = Class.forName("io.github.dohwan.sqldb.api.SqlDbService");
            Method getMethod = findGetMethod(serviceClass);
            Object api = getMethod.invoke(null, plugin);
            if (api == null) {
                throw new SQLException("SQLDB returned null api");
            }
            Method connMethod = api.getClass().getMethod("getConnection");
            this.sqlDbApi = api;
            this.getConnectionMethod = connMethod;
        } catch (Exception e) {
            throw new SQLException("Failed to resolve SQLDB API: " + e.getMessage(), e);
        }
    }

    private Method findGetMethod(Class<?> serviceClass) throws NoSuchMethodException {
        for (Method method : serviceClass.getMethods()) {
            if (!"get".equals(method.getName())) {
                continue;
            }
            if (method.getParameterCount() != 1) {
                continue;
            }
            Class<?> param = method.getParameterTypes()[0];
            if (param.isAssignableFrom(plugin.getClass())
                    || param.isAssignableFrom(org.bukkit.plugin.Plugin.class)
                    || param.isAssignableFrom(org.bukkit.plugin.java.JavaPlugin.class)) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("No compatible SqlDbService.get(...) method found");
    }

    private Connection openConnection() throws SQLException {
        ensureReady();
        try {
            Object connection = getConnectionMethod.invoke(sqlDbApi);
            if (!(connection instanceof Connection c)) {
                throw new SQLException("SQLDB getConnection did not return java.sql.Connection");
            }
            return c;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Failed to obtain connection from SQLDB: " + e.getMessage(), e);
        }
    }
}
