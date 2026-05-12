package io.github.dohwan.sqldb.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.dohwan.sqldb.api.SqlDbApi;

import java.sql.Connection;
import java.sql.SQLException;

public final class MySqlManager implements SqlDbApi, AutoCloseable {
    private final HikariDataSource dataSource;

    public MySqlManager(MySqlConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        String jdbcUrl = "jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.database()
                + "?useSSL=" + config.useSsl() + "&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(config.maximumPoolSize());
        hikariConfig.setConnectionTimeout(config.connectionTimeoutMs());
        hikariConfig.setPoolName("sqldb-pool");
        this.dataSource = new HikariDataSource(hikariConfig);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public boolean isHealthy() {
        try (Connection connection = getConnection()) {
            return connection.isValid(2);
        } catch (SQLException ignored) {
            return false;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
