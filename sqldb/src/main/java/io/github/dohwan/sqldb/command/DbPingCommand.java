package io.github.dohwan.sqldb.command;

import io.github.dohwan.sqldb.SqldbPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public final class DbPingCommand implements CommandExecutor {
    private final SqldbPlugin plugin;

    public DbPingCommand(SqldbPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        plugin.sendMessage(
                sender,
                plugin.isDatabaseConnected() ? "messages.command.dbConnected" : "messages.command.dbNotConnected",
                plugin.isDatabaseConnected() ? "&aMySQL connected." : "&cMySQL not connected."
        );
        return true;
    }
}
