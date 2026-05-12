package quest.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class QuestTabCompleter implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("quest")) {
            return complete(args, List.of("menu", "list"));
        }
        if (name.equals("questadmin")) {
            if (args.length == 1) {
                return complete(args, List.of("reload", "dailyreset", "guildset"));
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("guildset")) {
                return completePlayers(args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("guildset")) {
                return List.of("<guildId>");
            }
        }
        return List.of();
    }

    private List<String> complete(String[] args, List<String> source) {
        String token = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : source) {
            if (s.toLowerCase(Locale.ROOT).startsWith(token)) {
                out.add(s);
            }
        }
        return out;
    }

    private List<String> completePlayers(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(p.getName());
            }
        });
        return out;
    }
}

