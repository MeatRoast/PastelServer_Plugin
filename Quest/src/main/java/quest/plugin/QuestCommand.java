package quest.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class QuestCommand implements CommandExecutor {
    private final QuestManager questManager;

    public QuestCommand(QuestManager questManager) {
        this.questManager = questManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player only.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("menu")) {
            new QuestGui(questManager, player).open();
            return true;
        }
        return false;
    }
}
