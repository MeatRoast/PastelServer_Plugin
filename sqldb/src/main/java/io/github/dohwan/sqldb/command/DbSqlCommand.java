package io.github.dohwan.sqldb.command;

import io.github.dohwan.sqldb.SqldbPlugin;
import io.github.dohwan.sqldb.api.SqlDbApi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public final class DbSqlCommand implements CommandExecutor {
    private final SqldbPlugin plugin;

    public DbSqlCommand(SqldbPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length < 1) {
            plugin.sendMessage(sender, "messages.command.usageDbsql", "&eUsage: /dbsql <reload|start|stop|tables|table <name>>");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                boolean ok = plugin.reloadDatabase();
                plugin.sendMessage(sender, ok ? "messages.command.dbReloaded" : "messages.command.dbReloadFailed",
                        ok ? "&aDB reloaded." : "&cDB reload failed.");
            }
            case "start" -> {
                boolean ok = plugin.startDatabase();
                plugin.sendMessage(sender, ok ? "messages.command.dbStarted" : "messages.command.dbStartFailed",
                        ok ? "&aDB started." : "&cDB start failed.");
            }
            case "stop" -> {
                plugin.stopDatabase();
                plugin.sendMessage(sender, "messages.command.dbStopped", "&eDB stopped.");
            }
            case "tables" -> sendTables(sender);
            case "table" -> sendTableColumns(sender, args);
            default -> plugin.sendMessage(sender, "messages.command.usageDbsql", "&eUsage: /dbsql <reload|start|stop|tables|table <name>>");
        }
        return true;
    }

    private void sendTables(CommandSender sender) {
        SqlDbApi api = plugin.getSqlDbApiOrNull();
        if (api == null) {
            plugin.sendMessage(sender, "messages.command.dbNotConnected", "&cMySQL not connected.");
            return;
        }
        try (Connection connection = api.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = metaData.getTables(catalog, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            if (tables.isEmpty()) {
                plugin.sendMessage(sender, "messages.command.tablesEmpty", "&eNo tables found.");
                return;
            }
            String header = plugin.coloredMessage("messages.command.tablesHeader", "&bTables:");
            sender.sendMessage(header + " " + plugin.colorize("&f" + String.join("&7, &f", tables)));
        } catch (Exception e) {
            plugin.sendMessage(sender, "messages.command.tableQueryFailed", "&cFailed to query table info.");
        }
    }

    private void sendTableColumns(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.sendMessage(sender, "messages.command.usageTable", "&eUsage: /dbsql table <name>");
            return;
        }
        SqlDbApi api = plugin.getSqlDbApiOrNull();
        if (api == null) {
            plugin.sendMessage(sender, "messages.command.dbNotConnected", "&cMySQL not connected.");
            return;
        }
        String tableName = args[1];
        try (Connection connection = api.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = metaData.getColumns(catalog, null, tableName, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String type = rs.getString("TYPE_NAME");
                    columns.add(name + "(" + type + ")");
                }
            }
            if (columns.isEmpty()) {
                sender.sendMessage(
                        plugin.coloredMessage("messages.command.tableNotFound", "&cTable not found or no columns:")
                                + " " + plugin.colorize("&f" + tableName)
                );
                return;
            }
            String header = plugin.coloredMessage("messages.command.tableHeader", "&bColumns of");
            sender.sendMessage(header + " " + plugin.colorize("&f" + tableName + "&7: &f" + String.join("&7, &f", columns)));
        } catch (Exception e) {
            plugin.sendMessage(sender, "messages.command.tableQueryFailed", "&cFailed to query table info.");
        }
    }
}
