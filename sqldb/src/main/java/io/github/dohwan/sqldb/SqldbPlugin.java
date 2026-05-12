package io.github.dohwan.sqldb;

import io.github.dohwan.sqldb.api.SqlDbApi;
import io.github.dohwan.sqldb.command.DbPingCommand;
import io.github.dohwan.sqldb.command.DbSqlCommand;
import io.github.dohwan.sqldb.db.MySqlConfig;
import io.github.dohwan.sqldb.db.MySqlManager;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class SqldbPlugin extends JavaPlugin {
    private MySqlManager mySqlManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveGuideIfMissing();
        connectDatabase();

        if (getCommand("dbping") != null) {
            getCommand("dbping").setExecutor(new DbPingCommand(this));
        }
        if (getCommand("dbsql") != null) {
            getCommand("dbsql").setExecutor(new DbSqlCommand(this));
        }

        logInfo("messages.log.enabled", "SQLDB enabled.");
    }

    @Override
    public void onDisable() {
        if (mySqlManager != null) {
            getServer().getServicesManager().unregister(SqlDbApi.class, mySqlManager);
            mySqlManager.close();
        }
        logInfo("messages.log.disabled", "SQLDB disabled.");
    }

    public boolean startDatabase() {
        return connectDatabase();
    }

    public boolean stopDatabase() {
        if (mySqlManager == null) {
            return true;
        }
        getServer().getServicesManager().unregister(SqlDbApi.class, mySqlManager);
        mySqlManager.close();
        mySqlManager = null;
        return true;
    }

    public boolean reloadDatabase() {
        reloadConfig();
        stopDatabase();
        return connectDatabase();
    }

    public boolean isDatabaseConnected() {
        return mySqlManager != null && mySqlManager.isHealthy();
    }

    public SqlDbApi getSqlDbApiOrNull() {
        return mySqlManager;
    }

    public String message(String path, String fallback) {
        return getConfig().getString(path, fallback);
    }

    public String colorize(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public String coloredMessage(String path, String fallback) {
        return colorize(message(path, fallback));
    }

    public void sendMessage(CommandSender sender, String path, String fallback) {
        sender.sendMessage(coloredMessage(path, fallback));
    }

    private void saveGuideIfMissing() {
        java.io.File guideFile = new java.io.File(getDataFolder(), "guide.yml");
        if (!guideFile.exists()) {
            saveResource("guide.yml", false);
        }
    }

    private boolean connectDatabase() {
        try {
            MySqlConfig config = MySqlConfig.from(getConfig());
            mySqlManager = new MySqlManager(config);
            getServer().getServicesManager().register(SqlDbApi.class, mySqlManager, this, ServicePriority.Normal);
            return true;
        } catch (Exception e) {
            mySqlManager = null;
            logWarn("messages.log.dbConnectFailed", "디비연결이 실패했습니다 확인해주세요 !");
            String errorPrefix = message("messages.log.dbErrorPrefix", "DB error: ");
            getLogger().warning(errorPrefix + e.getMessage());
            return false;
        }
    }

    private void logInfo(String path, String fallback) {
        getLogger().info(message(path, fallback));
    }

    private void logWarn(String path, String fallback) {
        getLogger().warning(message(path, fallback));
    }
}
