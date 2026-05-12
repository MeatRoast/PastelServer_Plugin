package com.money.plugin;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ChequeCommand implements CommandExecutor {
    private static final DecimalFormat MONEY_FORMAT;
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        MONEY_FORMAT = new DecimalFormat("#,##0.##", symbols);
    }

    private final MoneyPlugin plugin;
    private final EconomyService economyService;
    private final NamespacedKey key;

    public ChequeCommand(MoneyPlugin plugin, EconomyService economyService) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.key = new NamespacedKey(plugin, "cheque_amount");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String raw = "/" + label + (args.length == 0 ? "" : " " + String.join(" ", args));
        audit(sender, "CHEQUE_COMMAND", null, null, raw, true);
        if (!plugin.isDatabaseAvailable()) {
            sender.sendMessage(msg("db-not-connected"));
            audit(sender, "CHEQUE_BLOCKED_DB_OFFLINE", null, null, raw, false);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("player-only"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(msg("cheque-usage"));
            return true;
        }
        try {
            BigDecimal amount = parseAmount(args[0]);
            int count = Integer.parseInt(args[1]);
            if (count <= 0) {
                sender.sendMessage(msg("cheque-count-positive"));
                return true;
            }
            BigDecimal minAmount = BigDecimal.valueOf(plugin.getConfig().getLong("cheque.minAmount", 10000L));
            if (amount.compareTo(minAmount) < 0) {
                sender.sendMessage(fmt("cheque-min", Map.of("amount", money(minAmount))));
                return true;
            }
            if (player.getInventory().firstEmpty() == -1) {
                sender.sendMessage(msg("cheque-no-space"));
                return true;
            }
            BigDecimal total = amount.multiply(BigDecimal.valueOf(count)).setScale(2, RoundingMode.DOWN);
            boolean ok = economyService.subtract(player.getUniqueId(), total);
            if (!ok) {
                sender.sendMessage(fmt("cheque-not-enough", Map.of("amount", money(total))));
                return true;
            }
            ItemStack stack = new ItemStack(Material.PAPER, count);
            ItemMeta meta = stack.getItemMeta();
            String name = plugin.messages().format("messages.cheque-item-name", Map.of("amount", money(amount)));
            List<String> lore = plugin.messages().getList("messages.cheque-item-lore");
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, amount.doubleValue());
            stack.setItemMeta(meta);
            player.getInventory().addItem(stack);
            BigDecimal balance = economyService.getBalance(player.getUniqueId());
            sender.sendMessage(fmt("cheque-created", Map.of(
                    "unit", money(amount),
                    "count", String.valueOf(count),
                    "total", money(total),
                    "balance", money(balance)
            )));
            audit(sender, "CHEQUE_ISSUED", player.getUniqueId(), player.getName(), total.toPlainString(), true);
            return true;
        } catch (Exception e) {
            sender.sendMessage(fmt("error-with-reason", Map.of("reason", e.getMessage())));
            audit(sender, "CHEQUE_ERROR", null, null, e.getMessage(), false);
            return true;
        }
    }

    private BigDecimal parseAmount(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.DOWN);
    }

    private String money(BigDecimal value) {
        return MONEY_FORMAT.format(value);
    }

    private String msg(String key) {
        return plugin.messages().get("messages." + key);
    }

    private String fmt(String key, Map<String, String> placeholders) {
        return plugin.messages().format("messages." + key, placeholders);
    }

    private void audit(CommandSender sender, String action, java.util.UUID targetUuid, String targetName, String detail, boolean success) {
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
}
