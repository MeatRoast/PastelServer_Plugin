package io.github.dohwan.chat;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.logging.Level;

public final class SqlDbBridge {
    private static final String SERVICE_CLASS = "io.github.dohwan.sqldb.api.SqlDbService";
    private static final String API_CLASS = "io.github.dohwan.sqldb.api.SqlDbApi";

    private final Plugin plugin;
    private Object apiInstance;
    private Method dataSourceMethod;
    private Method connectionMethod;

    public SqlDbBridge(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean initialize() {
        try {
            Class<?> serviceClass = Class.forName(SERVICE_CLASS);
            Class<?> apiClass = Class.forName(API_CLASS);

            Method getMethod = findServiceGetMethod(serviceClass);
            Object serviceOrApi = getMethod.invoke(null, plugin);
            if (serviceOrApi == null) {
                throw new IllegalStateException("SqlDbService.get(plugin) returned null");
            }

            this.apiInstance = resolveApiInstance(serviceOrApi, apiClass);
            this.dataSourceMethod = findMethod(apiInstance.getClass(), "getDataSource", "dataSource");
            this.connectionMethod = findMethod(apiInstance.getClass(), "getConnection", "connection");

            if (dataSourceMethod == null && connectionMethod == null) {
                throw new IllegalStateException("No usable DataSource/Connection method found in SQLDB API");
            }

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "SQLDB API 초기화 실패", e);
            return false;
        }
    }

    public Connection getConnection() throws Exception {
        if (apiInstance == null) {
            throw new IllegalStateException("SQLDB bridge is not initialized");
        }
        if (dataSourceMethod != null) {
            Object dsObj = dataSourceMethod.invoke(apiInstance);
            if (dsObj instanceof DataSource dataSource) {
                return dataSource.getConnection();
            }
        }
        if (connectionMethod != null) {
            Object connectionObj = connectionMethod.invoke(apiInstance);
            if (connectionObj instanceof Connection connection) {
                return connection;
            }
        }
        throw new IllegalStateException("Unable to get SQL connection from SQLDB API");
    }

    private Object resolveApiInstance(Object serviceOrApi, Class<?> apiClass) throws Exception {
        if (apiClass.isInstance(serviceOrApi)) {
            return serviceOrApi;
        }

        Method apiGetter = findMethod(serviceOrApi.getClass(), "api", "getApi");
        if (apiGetter != null) {
            Object maybeApi = apiGetter.invoke(serviceOrApi);
            if (maybeApi != null && apiClass.isInstance(maybeApi)) {
                return maybeApi;
            }
        }
        throw new IllegalStateException("SQLDB API instance could not be resolved");
    }

    private Method findMethod(Class<?> type, String... names) {
        for (String name : names) {
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Keep searching.
            }
        }
        return null;
    }

    private Method findServiceGetMethod(Class<?> serviceClass) throws NoSuchMethodException {
        try {
            return serviceClass.getMethod("get", JavaPlugin.class);
        } catch (NoSuchMethodException ignored) {
            return serviceClass.getMethod("get", Plugin.class);
        }
    }
}
