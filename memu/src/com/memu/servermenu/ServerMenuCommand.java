package com.memu.servermenu;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class ServerMenuCommand implements CommandExecutor, TabCompleter {
    private final MenuManager menuManager;

    public ServerMenuCommand(MenuManager menuManager) {
        this.menuManager = menuManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "플레이어만 사용할 수 있습니다.");
            return true;
        }

        Player player = (Player) sender;
        if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
            if (!player.hasPermission("servermenu.reload")) {
                player.sendMessage(ChatColor.RED + "권한이 없습니다.");
                return true;
            }
            menuManager.reload();
            player.sendMessage(ChatColor.GREEN + "ServerMenu 설정을 다시 불러왔습니다.");
            return true;
        }

        menuManager.openMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }
}
