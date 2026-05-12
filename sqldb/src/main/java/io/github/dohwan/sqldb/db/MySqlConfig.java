package io.github.dohwan.sqldb.db;

import org.bukkit.configuration.file.FileConfiguration;

public record MySqlConfig(
        String host,
        int port,
        String database,
        String username,
        String password,
        boolean useSsl,
        int maximumPoolSize,
        long connectionTimeoutMs
) {
    public static MySqlConfig from(FileConfiguration config) {
        return new MySqlConfig(
                config.getString("mysql.host", "127.0.0.1"),
                config.getInt("mysql.port", 3306),
                config.getString("mysql.database", "minecraft"),
                config.getString("mysql.username", "root"),
                config.getString("mysql.password", ""),
                config.getBoolean("mysql.useSsl", false),
                config.getInt("mysql.maximumPoolSize", 10),
                config.getLong("mysql.connectionTimeoutMs", 10000)
        );
    }
}
