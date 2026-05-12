package io.github.dohwan.sqldb.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class SqlDbService {
    private SqlDbService() {
    }

    public static SqlDbApi get(JavaPlugin requester) {
        RegisteredServiceProvider<SqlDbApi> provider =
                Bukkit.getServicesManager().getRegistration(SqlDbApi.class);
        if (provider == null) {
            throw new IllegalStateException("SQLDB service not found. Check plugin dependency/load order.");
        }
        return provider.getProvider();
    }
}
