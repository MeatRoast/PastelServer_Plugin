package io.github.dohwan.sqldb.api;

import java.sql.Connection;
import java.sql.SQLException;

public interface SqlDbApi {
    Connection getConnection() throws SQLException;
    boolean isHealthy();
}
