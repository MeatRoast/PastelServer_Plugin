package quest.plugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class QuestAdminCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final QuestManager manager;

    public QuestAdminCommand(JavaPlugin plugin, QuestManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("quest.admin")) {
            sender.sendMessage("No permission.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("/questadmin reload | dailyreset | guildset <player> <guildId>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                manager.loadAll();
                sender.sendMessage("Reloaded.");
                return true;
            }
            case "dailyreset" -> {
                manager.resetDailyQuests();
                sender.sendMessage("Daily reset done.");
                return true;
            }
            case "guildset" -> {
                if (args.length < 3) return false;
                Player p = Bukkit.getPlayerExact(args[1]);
                if (p == null) {
                    sender.sendMessage("Player offline/not found.");
                    return true;
                }
                manager.assignGuild(p, args[2]);
                sender.sendMessage("Guild assigned.");
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}

