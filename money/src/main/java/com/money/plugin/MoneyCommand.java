package com.money.plugin;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MoneyCommand implements CommandExecutor, TabCompleter {
    private static final DecimalFormat MONEY_FORMAT;

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        MONEY_FORMAT = new DecimalFormat("#,##0.##", symbols);
    }

    private final MoneyPlugin plugin;
    private final EconomyService economyService;

    public MoneyCommand(MoneyPlugin plugin, EconomyService economyService) {
        this.plugin = plugin;
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String raw = "/" + label + (args.length == 0 ? "" : " " + String.join(" ", args));
        audit(sender, "MONEY_COMMAND", null, null, raw, true);
        if (!plugin.isDatabaseAvailable() && (args.length == 0 || !"reload".equalsIgnoreCase(args[0]))) {
            sender.sendMessage(msg("db-not-connected"));
            audit(sender, "MONEY_BLOCKED_DB_OFFLINE", null, null, raw, false);
            return true;
        }
        try {
            if (args.length == 0) {
                return showMain(sender);
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            return switch (sub) {
                case "보내기", "pay" -> handlePay(sender, args);
                case "순위", "top" -> handleTop(sender);
                case "주기", "add" -> handleAdmin(sender, args, "add");
                case "차감", "take" -> handleAdmin(sender, args, "take");
                case "설정", "set" -> handleAdmin(sender, args, "set");
                case "확인", "balance" -> handleCheck(sender, args);
                case "reload" -> handleReload(sender);
                default -> {
                    sender.sendMessage(msg("unknown-subcommand"));
                    yield true;
                }
            };
        } catch (Exception e) {
            sender.sendMessage(fmt("error-with-reason", Map.of("reason", e.getMessage())));
            audit(sender, "MONEY_COMMAND_ERROR", null, null, e.getMessage(), false);
            return true;
        }
    }

    private boolean showMain(CommandSender sender) throws Exception {
        UUID uuid = sender instanceof Player p ? p.getUniqueId() : null;
        if (uuid == null) {
            sender.sendMessage(msg("console-needs-subcommand"));
            return true;
        }
        BigDecimal balance = economyService.getBalance(uuid);
        sender.sendMessage(fmt("main-balance", Map.of("balance", format(balance))));
        for (String line : plugin.messages().getList("messages.main-help")) {
            sender.sendMessage(line);
        }
        if (sender.hasPermission("money.admin")) {
            for (String line : plugin.messages().getList("messages.main-help-admin")) {
                sender.sendMessage(line);
            }
        }
        return true;
    }

    private boolean handlePay(CommandSender sender, String[] args) throws Exception {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("player-only"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(msg("pay-needs-player"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(msg("target-never-seen"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(msg("pay-needs-amount"));
            return true;
        }
        BigDecimal amount = parseAmount(args[2]);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            sender.sendMessage(msg("amount-positive-only"));
            return true;
        }

        boolean ok = economyService.transfer(player.getUniqueId(), target.getUniqueId(), amount);
        if (!ok) {
            sender.sendMessage(msg("insufficient-balance"));
            return true;
        }

        BigDecimal senderBalance = economyService.getBalance(player.getUniqueId());
        sender.sendMessage(fmt("pay-sender", Map.of(
                "target", safeName(target.getName()),
                "amount", format(amount),
                "balance", format(senderBalance)
        )));
        audit(player, "PAY", target.getUniqueId(), safeName(target.getName()), amount.toPlainString(), true);
        if (target.isOnline() && target.getPlayer() != null) {
            BigDecimal targetBalance = economyService.getBalance(target.getUniqueId());
            target.getPlayer().sendMessage(fmt("pay-target", Map.of(
                    "sender", player.getName(),
                    "amount", format(amount),
                    "balance", format(targetBalance)
            )));
        }
        return true;
    }

    private boolean handleTop(CommandSender sender) throws Exception {
        int limit = plugin.getConfig().getInt("rankings.topLimit", 10);
        List<EconomyService.TopBalance> topBalances = economyService.getTopBalances(limit);
        sender.sendMessage(fmt("top-header", Map.of("count", String.valueOf(topBalances.size()))));
        int i = 1;
        for (EconomyService.TopBalance entry : topBalances) {
            String name = safeName(Bukkit.getOfflinePlayer(entry.uuid()).getName());
            sender.sendMessage(fmt("top-line", Map.of(
                    "rank", String.valueOf(i++),
                    "player", name,
                    "balance", format(entry.balance())
            )));
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args, String mode) throws Exception {
        if (!sender.hasPermission("money.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(msg("admin-needs-player"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(msg("target-never-seen"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(msg("admin-needs-amount"));
            return true;
        }
        BigDecimal amount = parseAmount(args[2]);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            sender.sendMessage(msg("amount-positive-only"));
            return true;
        }

        if ("set".equals(mode)) {
            economyService.set(target.getUniqueId(), amount);
        } else if ("add".equals(mode)) {
            economyService.add(target.getUniqueId(), amount);
        } else {
            boolean ok = economyService.subtract(target.getUniqueId(), amount);
            if (!ok) {
                sender.sendMessage(msg("target-balance-low"));
                return true;
            }
        }
        BigDecimal current = economyService.getBalance(target.getUniqueId());
        sender.sendMessage(fmt("admin-updated", Map.of(
                "player", safeName(target.getName()),
                "balance", format(current)
        )));
        audit(sender, "ADMIN_" + mode.toUpperCase(Locale.ROOT), target.getUniqueId(), safeName(target.getName()), amount.toPlainString(), true);
        if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().sendMessage(fmt("admin-notify-target", Map.of(
                    "amount", format(amount),
                    "balance", format(current),
                    "mode", mode
            )));
        }
        return true;
    }

    private boolean handleCheck(CommandSender sender, String[] args) throws Exception {
        if (args.length < 2) {
            return showMain(sender);
        }
        if (!sender.hasPermission("money.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        BigDecimal balance = economyService.getBalance(target.getUniqueId());
        sender.sendMessage(fmt("balance-other", Map.of("player", safeName(target.getName()), "balance", format(balance))));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("money.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        plugin.reloadPluginConfig();
        sender.sendMessage(msg("reloaded"));
        return true;
    }

    private BigDecimal parseAmount(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.DOWN);
    }

    private String format(BigDecimal value) {
        String symbol = plugin.getConfig().getString("economy.currencySymbol", "원");
        return MONEY_FORMAT.format(value) + symbol;
    }

    private String safeName(String name) {
        return name == null ? "unknown" : name;
    }

    private String msg(String key) {
        return plugin.messages().get("messages." + key);
    }

    private String fmt(String key, Map<String, String> placeholders) {
        return plugin.messages().format("messages." + key, placeholders);
    }

    private void audit(CommandSender sender, String action, UUID targetUuid, String targetName, String detail, boolean success) {
        String sourceType = sender instanceof Player ? "PLAYER" : "CONSOLE";
        String actorName = sender.getName();
        String actorUuid = sender instanceof Player p ? p.getUniqueId().toString() : null;
        plugin.economyService().logAction(
                actorUuid,
                actorName,
                sourceType,
                action,
                targetUuid == null ? null : targetUuid.toString(),
                targetName,
                null,
                detail,
                success
        );
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            list.add("보내기");
            list.add("순위");
            list.add("확인");
            if (sender.hasPermission("money.admin")) {
                list.add("주기");
                list.add("차감");
                list.add("설정");
                list.add("reload");
            }
        } else if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                list.add(p.getName());
            }
        }
        return list;
    }
}
